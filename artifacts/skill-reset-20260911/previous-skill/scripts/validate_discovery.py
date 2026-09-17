#!/usr/bin/env python3
import argparse
import json
from common import read_json, loads
from pathlib import Path


FORMAT_VERSION = 1
PART_KINDS = {"unknown", "chapter", "episode", "volume", "part", "collection", "extra", "remaster"}
ANNOTATION_FIELDS = {"gid", "authorAliases", "workAliases", "semanticTitleKey", "partKind", "partNumber", "confidence", "needsHumanReview"}


def strict_json(path):
    return read_json(path)


def under(root, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise RuntimeError("manifest contains an invalid relative path")
    path = (root / relative).resolve()
    try:
        path.relative_to(root)
    except ValueError as error:
        raise RuntimeError("manifest path escapes its run directory") from error
    return path


def validate_aliases(gid, field, values, maximum, errors):
    if not isinstance(values, list) or len(values) > maximum:
        errors.append(f"GID {gid}: {field} must contain at most {maximum} values")
        return
    normalized = set()
    for value in values:
        if not isinstance(value, str) or not value.strip() or len(value) > 80:
            errors.append(f"GID {gid}: {field} values must be non-empty strings up to 80 characters")
            return
        folded = value.casefold().strip()
        if folded in normalized:
            errors.append(f"GID {gid}: {field} contains duplicate values")
            return
        normalized.add(folded)


def validate_annotation(value, expected_gid, errors):
    if not isinstance(value, dict) or set(value) != ANNOTATION_FIELDS:
        errors.append(f"GID {expected_gid}: annotation fields do not match the contract")
        return
    gid = value.get("gid")
    if isinstance(gid, bool) or not isinstance(gid, int) or gid != expected_gid:
        errors.append(f"GID {expected_gid}: annotation GID is invalid")
    validate_aliases(expected_gid, "authorAliases", value.get("authorAliases"), 3, errors)
    validate_aliases(expected_gid, "workAliases", value.get("workAliases"), 5, errors)
    key = value.get("semanticTitleKey")
    if key is not None and (not isinstance(key, str) or not key.strip() or len(key) > 96):
        errors.append(f"GID {expected_gid}: semanticTitleKey must be null or a string up to 96 characters")
    if value.get("partKind") not in PART_KINDS:
        errors.append(f"GID {expected_gid}: partKind is invalid")
    number = value.get("partNumber")
    if number is not None and (not isinstance(number, str) or not number.strip() or len(number) > 32):
        errors.append(f"GID {expected_gid}: partNumber must be null or a string up to 32 characters")
    confidence = value.get("confidence")
    if isinstance(confidence, bool) or not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
        errors.append(f"GID {expected_gid}: confidence must be within 0..1")
    if not isinstance(value.get("needsHumanReview"), bool):
        errors.append(f"GID {expected_gid}: needsHumanReview must be boolean")


def validate(manifest_path, results_dir):
    manifest_path = manifest_path.resolve()
    root = manifest_path.parent
    results_dir = results_dir.resolve()
    manifest = strict_json(manifest_path)
    errors = []
    if manifest.get("formatVersion") != FORMAT_VERSION or manifest.get("kind") != "ehviewer-discovery-run":
        errors.append("manifest format is invalid")
    fingerprint = manifest.get("snapshotFingerprint")
    batches = manifest.get("batches")
    if not isinstance(fingerprint, str) or not isinstance(batches, list) :
        errors.append("manifest fingerprint or batches are invalid")
        return [], errors
    all_annotations = []
    seen = set()
    for batch in batches:
        batch_id = batch.get("batchId") if isinstance(batch, dict) else None
        expected = batch.get("gids") if isinstance(batch, dict) else None
        if not isinstance(batch_id, str) or not isinstance(expected, list) or not expected:
            errors.append("manifest batch is invalid")
            continue
        if any(isinstance(gid, bool) or not isinstance(gid, int) for gid in expected) or len(expected) != len(set(expected)):
            errors.append(f"{batch_id}: manifest GIDs are invalid")
            continue
        if seen.intersection(expected):
            errors.append(f"{batch_id}: manifest GIDs appear in more than one batch")
            continue
        seen.update(expected)
        result_path = under(results_dir, batch.get("resultFile"))
        if not result_path.is_file():
            errors.append(f"{batch_id}: result file is missing")
            continue
        try:
            result = strict_json(result_path)
        except (OSError, ValueError, json.JSONDecodeError) as error:
            errors.append(f"{batch_id}: invalid result JSON: {error}")
            continue
        if manifest.get("metadataFingerprint") and result.get("metadataFingerprint") != manifest["metadataFingerprint"]:
            errors.append(f"{batch_id}: title metadata fingerprint mismatch")
            continue
        if result.get("formatVersion") != FORMAT_VERSION or result.get("snapshotFingerprint") != fingerprint or result.get("batchId") != batch_id:
            errors.append(f"{batch_id}: result format, fingerprint, or batch ID mismatch")
            continue
        annotations = result.get("annotations")
        if not isinstance(annotations, list) or len(annotations) != len(expected):
            errors.append(f"{batch_id}: annotation count mismatch")
            continue
        by_gid = {}
        for annotation in annotations:
            gid = annotation.get("gid") if isinstance(annotation, dict) else None
            if gid in by_gid:
                errors.append(f"{batch_id}: duplicate annotation GID {gid}")
                continue
            by_gid[gid] = annotation
        if set(by_gid) != set(expected):
            errors.append(f"{batch_id}: annotation GIDs do not match the batch")
            continue
        for gid in expected:
            validate_annotation(by_gid[gid], gid, errors)
            all_annotations.append(by_gid[gid])
    if len(seen) != manifest.get("itemCount") or len(batches) != manifest.get("batchCount"):
        errors.append("manifest item or batch count mismatch")
    import hashlib
    actual_fingerprint = hashlib.sha256("\n".join(str(gid) for gid in sorted(seen)).encode()).hexdigest()
    if actual_fingerprint != fingerprint:
        errors.append("manifest GID fingerprint mismatch")
    return all_annotations, errors


def main():
    parser = argparse.ArgumentParser(description="Strictly validate complete full-library multilingual discovery results.")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--results-dir", required=True, type=Path)
    parser.add_argument("--annotations", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    if args.annotations.exists() or args.report.exists():
        raise FileExistsError("refusing to overwrite annotations or report")
    manifest = strict_json(args.manifest)
    annotations, errors = validate(args.manifest, args.results_dir)
    output = {"formatVersion": FORMAT_VERSION, "snapshotFingerprint": manifest.get("snapshotFingerprint"), "metadataFingerprint": manifest.get("metadataFingerprint"), "annotationCount": len(annotations), "annotations": annotations}
    report = {"status": "passed" if not errors else "failed", "itemCount": len(annotations), "snapshotFingerprint": manifest.get("snapshotFingerprint"), "errors": errors}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if errors:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        raise SystemExit(1)
    args.annotations.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
