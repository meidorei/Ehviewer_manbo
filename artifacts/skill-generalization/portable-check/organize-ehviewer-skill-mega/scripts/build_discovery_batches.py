#!/usr/bin/env python3
import argparse
import json
from common import read_json, loads
from pathlib import Path


FORMAT_VERSION = 1
DEFAULT_BATCH_SIZE = 75


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_items(catalog):
    if catalog.get("formatVersion") not in (2, 3) or not isinstance(catalog.get("snapshotFingerprint"), str):
        raise RuntimeError("catalog format version or snapshot fingerprint is invalid")
    items = catalog.get("items")
    if not isinstance(items, list):
        raise RuntimeError("catalog must contain at least one item")
    gids = set()
    result = []
    for item in items:
        gid = item.get("gid")
        position = item.get("originalPosition")
        if isinstance(gid, bool) or not isinstance(gid, int) or gid in gids:
            raise RuntimeError("catalog GIDs must be unique integers")
        if isinstance(position, bool) or not isinstance(position, int) or position < 1:
            raise RuntimeError(f"GID {gid}: originalPosition must be a positive integer")
        title = item.get("title")
        title_jpn = item.get("titleJpn")
        if title is not None and not isinstance(title, str):
            raise RuntimeError(f"GID {gid}: title must be a string or null")
        if title_jpn is not None and not isinstance(title_jpn, str):
            raise RuntimeError(f"GID {gid}: titleJpn must be a string or null")
        gids.add(gid)
        result.append({"gid": gid, "originalPosition": position, "title": title, "titleJpn": title_jpn})
    return result


def build_batches(catalog, output_dir, manifest_path, batch_size=DEFAULT_BATCH_SIZE):
    if batch_size < 1 or batch_size > 100:
        raise ValueError("batch size must be between 1 and 100")
    output_dir = output_dir.resolve()
    manifest_path = manifest_path.resolve()
    if manifest_path.parent != output_dir:
        raise RuntimeError("manifest must be stored directly in the discovery output directory")
    if manifest_path.exists():
        raise FileExistsError(f"refusing to overwrite {manifest_path}")
    items = safe_items(catalog)
    if catalog.get("formatVersion") == 3:
        from common import validate_catalog
        validate_catalog(catalog)
    output_dir.mkdir(parents=True, exist_ok=True)
    batches = []
    artifacts = []
    fingerprint = catalog["snapshotFingerprint"]
    for offset in range(0, len(items), batch_size):
        batch_number = offset // batch_size + 1
        file_name = f"discovery-batch-{batch_number:05d}.json"
        result_name = f"discovery-result-{batch_number:05d}.json"
        batch_path = output_dir / file_name
        if batch_path.exists():
            raise FileExistsError(f"refusing to overwrite {batch_path}")
        batch = items[offset:offset + batch_size]
        batch_id = f"discovery:{batch_number:05d}"
        write_json(batch_path, {
            "formatVersion": FORMAT_VERSION,
            "snapshotFingerprint": fingerprint,
            "metadataFingerprint": catalog.get("metadataFingerprint"),
            "batchId": batch_id,
            "itemCount": len(batch),
            "items": batch,
        })
        batches.append({
            "batchId": batch_id,
            "file": file_name,
            "resultFile": result_name,
            "gids": [item["gid"] for item in batch],
        })
        artifacts.append({"path": file_name, "retention": "temporary"})
    manifest = {
        "formatVersion": FORMAT_VERSION,
        "kind": "ehviewer-discovery-run",
        "snapshotFingerprint": fingerprint,
        "metadataFingerprint": catalog.get("metadataFingerprint"),
        "batchSize": batch_size,
        "itemCount": len(items),
        "batchCount": len(batches),
        "batches": batches,
        "artifacts": artifacts,
        "verification": {},
        "cleanup": {"state": "pending"},
    }
    write_json(manifest_path, manifest)
    return manifest


def main():
    parser = argparse.ArgumentParser(description="Create fixed-size safe metadata batches for full-library multilingual discovery.")
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    args = parser.parse_args()
    catalog = loads(args.catalog.read_text(encoding="utf-8"))
    manifest = build_batches(catalog, args.output_dir, args.manifest, args.batch_size)
    print(json.dumps({"itemCount": manifest["itemCount"], "batchCount": manifest["batchCount"], "batchSize": manifest["batchSize"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
