#!/usr/bin/env python3
import argparse
import hashlib
import json
from common import read_json, loads
from pathlib import Path


def strict_json(path):
    return read_json(path)


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_artifact(root, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise RuntimeError("manifest contains an invalid artifact path")
    path = (root / relative).resolve()
    try:
        path.relative_to(root)
    except ValueError as error:
        raise RuntimeError("artifact path escapes the run directory") from error
    return path


def load_verified_manifest(path):
    manifest = strict_json(path)
    if manifest.get("formatVersion") != 1 or manifest.get("kind") != "ehviewer-discovery-run":
        raise RuntimeError("invalid discovery run manifest")
    verification = manifest.get("verification")
    if not isinstance(verification, dict) or set(verification) != {"preLaunch", "postLaunch"}:
        raise RuntimeError("run has not completed pre-launch and post-launch verification")
    root = path.parent
    for name, entry in verification.items():
        if not isinstance(entry, dict):
            raise RuntimeError(f"{name} verification entry is invalid")
        report_path = resolve_artifact(root, entry.get("path"))
        if not report_path.is_file() or sha256_file(report_path) != entry.get("sha256"):
            raise RuntimeError(f"{name} verification report is missing or changed")
        if strict_json(report_path).get("status") != "passed":
            raise RuntimeError(f"{name} verification report is not passed")
    from common import require
    pre = strict_json(resolve_artifact(root, verification["preLaunch"]["path"]))
    post = strict_json(resolve_artifact(root, verification["postLaunch"]["path"]))
    require(pre.get("kind") == post.get("kind") == "database-verification" and pre.get("phase") == "pre-launch" and post.get("phase") == "post-launch", "typed pre/post launch reports required")
    for field in ("snapshotFingerprint", "metadataFingerprint", "orderSha256", "modelSha256", "sortedSha256", "nonTimeDigest"):
        require(isinstance(pre.get(field), str) and len(pre[field]) == 64 and pre[field] == post.get(field), "verification binding mismatch")
    require(pre["snapshotFingerprint"] == manifest["snapshotFingerprint"], "verification belongs to another run")
    if manifest.get("metadataFingerprint"):
        require(pre["metadataFingerprint"] == manifest["metadataFingerprint"], "verification metadata mismatch")
    require(pre.get("integrityCheck") == post.get("integrityCheck") == "ok" and pre.get("orderMatches") is True and post.get("orderMatches") is True, "incomplete database verification")
    return manifest


def main():
    parser = argparse.ArgumentParser(description="Preview or confirm cleanup of temporary artifacts after verified EhViewer database replacement.")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--confirm", action="store_true", help="Delete the previewed temporary artifacts after explicit user confirmation.")
    args = parser.parse_args()
    manifest_path = args.manifest.resolve()
    manifest = load_verified_manifest(manifest_path)
    root = manifest_path.parent
    temporary = []
    resolved = {}
    for entry in manifest.get("artifacts", []):
        artifact = resolve_artifact(root, entry.get("path"))
        if artifact == manifest_path or artifact in resolved:
            raise RuntimeError("duplicate artifact path or attempted manifest cleanup")
        resolved[artifact] = entry.get("retention")
    for entry in manifest["verification"].values():
        if resolved.get(resolve_artifact(root, entry["path"])) == "temporary":
            raise RuntimeError("verification reports must be preserved")
    for entry in manifest.get("artifacts", []):
        if not isinstance(entry, dict) or entry.get("retention") not in {"temporary", "preserve"}:
            raise RuntimeError("manifest has an invalid artifact retention entry")
        if entry["retention"] == "temporary":
            path = resolve_artifact(root, entry.get("path"))
            if not path.is_file():
                raise RuntimeError(f"temporary artifact is missing: {entry.get('path')}")
            temporary.append(path)
    result = {"status": "deleted" if args.confirm else "preview", "temporaryArtifacts": [path.relative_to(root).as_posix() for path in temporary]}
    if args.confirm:
        for path in temporary:
            path.unlink()
        manifest["cleanup"] = {"state": "complete", "deletedTemporaryArtifacts": result["temporaryArtifacts"]}
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
