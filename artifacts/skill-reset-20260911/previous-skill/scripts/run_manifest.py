#!/usr/bin/env python3
import argparse
import hashlib
import json
from common import read_json, loads
from pathlib import Path


def strict_json(path):
    return read_json(path)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def relative_existing_file(root, value):
    path = Path(value).resolve()
    if not path.is_file():
        raise FileNotFoundError(path)
    try:
        return path.relative_to(root).as_posix(), path
    except ValueError as error:
        raise RuntimeError("artifact must be inside the run directory") from error


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path):
    manifest = strict_json(path)
    if manifest.get("formatVersion") != 1 or manifest.get("kind") != "ehviewer-discovery-run":
        raise RuntimeError("invalid discovery run manifest")
    if not isinstance(manifest.get("artifacts"), list) or not isinstance(manifest.get("verification"), dict):
        raise RuntimeError("manifest artifacts or verification are invalid")
    return manifest


def main():
    parser = argparse.ArgumentParser(description="Initialize an EhViewer run, register artifacts, and record verified reports.")
    subparsers = parser.add_subparsers(dest="command", required=True)
    init = subparsers.add_parser("init", help="Start a run without requiring discovery batches.")
    init.add_argument("--manifest", required=True, type=Path)
    init.add_argument("--catalog", required=True, type=Path)
    register = subparsers.add_parser("register", help="Register a generated file for temporary or preserved retention.")
    register.add_argument("--manifest", required=True, type=Path)
    register.add_argument("--path", required=True, type=Path)
    register.add_argument("--retention", required=True, choices=("temporary", "preserve"))
    verify = subparsers.add_parser("verify", help="Record passed pre-launch and post-launch validation reports.")
    verify.add_argument("--manifest", required=True, type=Path)
    verify.add_argument("--pre-launch-report", required=True, type=Path)
    verify.add_argument("--post-launch-report", required=True, type=Path)
    args = parser.parse_args()
    manifest_path = args.manifest.resolve()
    root = manifest_path.parent
    if args.command == "init":
        from common import validate_catalog, write_json as write_new
        catalog = strict_json(args.catalog)
        validate_catalog(catalog)
        relative, _ = relative_existing_file(root, args.catalog)
        if args.catalog.resolve() == manifest_path:
            raise RuntimeError("manifest cannot overwrite catalog")
        write_new(manifest_path, dict(formatVersion=1, kind="ehviewer-discovery-run",
                  snapshotFingerprint=catalog["snapshotFingerprint"], metadataFingerprint=catalog["metadataFingerprint"],
                  itemCount=len(catalog["items"]), discoveryPerformed=False, batchCount=0, batches=[],
                  artifacts=[dict(path=relative, retention="preserve")], verification={}, cleanup={"state":"pending"}))
        print(json.dumps({"status":"passed", "command":"init"}))
        return
    manifest = load_manifest(manifest_path)
    if args.command == "register":
        relative, _path = relative_existing_file(root, args.path)
        if relative == manifest_path.name:
            raise RuntimeError("the run manifest cannot register itself as an artifact")
        if any(entry.get("path") == relative for entry in manifest["artifacts"]):
            raise RuntimeError(f"artifact is already registered: {relative}")
        manifest["artifacts"].append({"path": relative, "retention": args.retention})
    else:
        reports = {}
        for name, source in (("preLaunch", args.pre_launch_report), ("postLaunch", args.post_launch_report)):
            relative, path = relative_existing_file(root, source)
            report = strict_json(path)
            if report.get("status") != "passed":
                raise RuntimeError(f"{name} report does not have status=passed")
            reports[name] = {"path": relative, "sha256": sha256_file(path)}
        pre = strict_json(root / reports["preLaunch"]["path"])
        post = strict_json(root / reports["postLaunch"]["path"])
        from common import require
        require(pre.get("kind") == post.get("kind") == "database-verification", "typed database reports required")
        require(pre.get("phase") == "pre-launch" and post.get("phase") == "post-launch", "verification phases mismatch")
        for field in ("snapshotFingerprint", "metadataFingerprint", "orderSha256", "modelSha256", "sortedSha256", "nonTimeDigest"):
            require(isinstance(pre.get(field), str) and len(pre[field]) == 64 and pre[field] == post.get(field), "verification binding mismatch: " + field)
        require(pre["snapshotFingerprint"] == manifest["snapshotFingerprint"], "verification belongs to another run")
        if manifest.get("metadataFingerprint"):
            require(pre["metadataFingerprint"] == manifest["metadataFingerprint"], "verification title metadata mismatch")
        require(pre.get("integrityCheck") == post.get("integrityCheck") == "ok" and pre.get("orderMatches") is True and post.get("orderMatches") is True, "incomplete database verification")
        manifest["verification"] = reports
    write_json(manifest_path, manifest)
    print(json.dumps({"status": "passed", "command": args.command}, ensure_ascii=False))


if __name__ == "__main__":
    main()
