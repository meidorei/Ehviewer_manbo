import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BUILD = load_script("build_discovery_batches")
VALIDATE = load_script("validate_discovery")


def fingerprint(gids):
    return hashlib.sha256("\n".join(str(gid) for gid in sorted(gids)).encode("utf-8")).hexdigest()


def catalog(items):
    return {"formatVersion": 2, "snapshotFingerprint": fingerprint([item["gid"] for item in items]), "items": items}


def annotation(gid, key=None, aliases=None, authors=None):
    return {
        "gid": gid,
        "authorAliases": authors or [],
        "workAliases": aliases or [],
        "semanticTitleKey": key,
        "partKind": "unknown",
        "partNumber": None,
        "confidence": 0.8,
        "needsHumanReview": True,
    }


class DiscoveryWorkflowTest(unittest.TestCase):
    def test_3184_items_create_43_complete_safe_batches(self):
        items = [
            {"gid": gid, "originalPosition": gid, "title": f"Title {gid}", "titleJpn": f"作品 {gid}", "label": "private", "state": 1}
            for gid in range(1, 3185)
        ]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = BUILD.build_batches(catalog(items), root, root / "run-manifest.json")
            self.assertEqual(43, manifest["batchCount"])
            self.assertEqual(3184, sum(len(batch["gids"]) for batch in manifest["batches"]))
            seen = [gid for batch in manifest["batches"] for gid in batch["gids"]]
            self.assertEqual(list(range(1, 3185)), seen)
            first_batch = json.loads((root / manifest["batches"][0]["file"]).read_text(encoding="utf-8"))
            self.assertEqual({"gid", "originalPosition", "title", "titleJpn"}, set(first_batch["items"][0]))
            self.assertEqual(75, first_batch["itemCount"])

    def test_validator_rejects_missing_annotations_and_fingerprint_mismatch(self):
        items = [
            {"gid": 1, "originalPosition": 1, "title": "one", "titleJpn": None},
            {"gid": 2, "originalPosition": 2, "title": "two", "titleJpn": None},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = BUILD.build_batches(catalog(items), root, root / "run-manifest.json", batch_size=2)
            batch = manifest["batches"][0]
            (root / batch["resultFile"]).write_text(json.dumps({
                "formatVersion": 1,
                "snapshotFingerprint": "wrong",
                "batchId": batch["batchId"],
                "annotations": [annotation(1)],
            }), encoding="utf-8")
            _annotations, errors = VALIDATE.validate(root / "run-manifest.json", root)
            self.assertTrue(any("mismatch" in error or "count mismatch" in error for error in errors))

    def test_cross_language_aliases_become_candidates_but_large_collision_does_not_merge(self):
        items = [
            {"gid": 1, "originalPosition": 1, "title": "月光", "titleJpn": "月光"},
            {"gid": 2, "originalPosition": 2, "title": "月光之歌", "titleJpn": None},
            {"gid": 3, "originalPosition": 3, "title": "Moonlight Song", "titleJpn": None},
            {"gid": 4, "originalPosition": 4, "title": "Gekkou no Uta", "titleJpn": None},
            {"gid": 5, "originalPosition": 5, "title": "[artist] zebra universe", "titleJpn": None},
            {"gid": 6, "originalPosition": 6, "title": "[artist] turtle galaxy", "titleJpn": None},
        ]
        items.extend({"gid": gid, "originalPosition": gid, "title": f"unrelated {gid}", "titleJpn": None} for gid in range(100, 141))
        source_catalog = catalog(items)
        annotations = [
            annotation(1, "moonlight song", ["月光", "moonlight song"]),
            annotation(2, "moonlight song", ["月光之歌", "moonlight song"]),
            annotation(3, "moonlight song", ["Moonlight Song"]),
            annotation(4, "moonlight song", ["Gekkou no Uta", "Moonlight Song"]),
            annotation(5, "zebra universe", ["zebra universe"], ["artist"]),
            annotation(6, "turtle galaxy", ["turtle galaxy"], ["artist"]),
        ]
        annotations.extend(annotation(gid, "generic series", [f"unique {gid}"]) for gid in range(100, 141))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog_path = root / "catalog.json"
            discovery_path = root / "discovery.json"
            candidates_path = root / "candidates.json"
            authors_path = root / "authors.json"
            unresolved_path = root / "unresolved.json"
            catalog_path.write_text(json.dumps(source_catalog), encoding="utf-8")
            discovery_path.write_text(json.dumps({"formatVersion": 1, "snapshotFingerprint": source_catalog["snapshotFingerprint"], "annotations": annotations}), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPTS / "generate_candidates.py"), "--catalog", str(catalog_path), "--discovery", str(discovery_path), "--candidates", str(candidates_path), "--author-buckets", str(authors_path), "--unresolved", str(unresolved_path)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            candidates = json.loads(candidates_path.read_text(encoding="utf-8"))
            candidate_sets = [{item["gid"] for item in row["items"]} for row in candidates["candidates"]]
            self.assertIn({1, 2, 3, 4}, candidate_sets)
            self.assertNotIn({5, 6}, candidate_sets)
            self.assertTrue(any(row["kind"] == "discovery-semantic-title-key" and row["confidence"] == "low" for row in candidates["collisionAudits"]))
            unresolved = json.loads(unresolved_path.read_text(encoding="utf-8"))
            self.assertIn(5, [item["gid"] for item in unresolved["items"]])
            self.assertIn(6, [item["gid"] for item in unresolved["items"]])

    def test_cleanup_requires_two_passed_reports_and_keeps_preserved_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_path = root / "run-manifest.json"
            manifest = BUILD.build_batches(catalog([{"gid": 1, "originalPosition": 1, "title": "one", "titleJpn": None}]), root, manifest_path)
            temporary_file = root / manifest["batches"][0]["file"]
            preserved = root / "final-order.json"
            pre = root / "pre-launch.json"
            post = root / "post-launch.json"
            preserved.write_text("{}", encoding="utf-8")
            report={"status":"passed", "kind":"database-verification", "snapshotFingerprint":manifest["snapshotFingerprint"],
                    "metadataFingerprint":"a"*64, "orderSha256":"b"*64,"modelSha256":"c"*64,"sortedSha256":"d"*64,
                    "nonTimeDigest":"e"*64,"integrityCheck":"ok","orderMatches":True}
            pre.write_text(json.dumps({**report,"phase":"pre-launch"}),encoding="utf-8")
            post.write_text(json.dumps({**report,"phase":"post-launch"}),encoding="utf-8")
            for command in (
                ["register", "--path", str(preserved), "--retention", "preserve"],
                ["verify", "--pre-launch-report", str(pre), "--post-launch-report", str(post)],
            ):
                result = subprocess.run([sys.executable, str(SCRIPTS / "run_manifest.py"), *command[:1], "--manifest", str(manifest_path), *command[1:]], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
            preview = subprocess.run([sys.executable, str(SCRIPTS / "cleanup_run.py"), "--manifest", str(manifest_path)], capture_output=True, text=True)
            self.assertEqual(0, preview.returncode, preview.stderr)
            self.assertTrue(temporary_file.exists())
            confirmed = subprocess.run([sys.executable, str(SCRIPTS / "cleanup_run.py"), "--manifest", str(manifest_path), "--confirm"], capture_output=True, text=True)
            self.assertEqual(0, confirmed.returncode, confirmed.stderr)
            self.assertFalse(temporary_file.exists())
            self.assertTrue(preserved.exists())


if __name__ == "__main__":
    unittest.main()
