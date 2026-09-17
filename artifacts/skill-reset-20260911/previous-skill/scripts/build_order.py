#!/usr/bin/env python3
import argparse
import json
from collections import defaultdict
from pathlib import Path


def is_known_order(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def build_stable_order(catalog, source):
    if catalog.get("formatVersion") == 3:
        from common import validate_binding, validate_audits, stable_order
        validate_binding(catalog, source)
        validate_audits(catalog, source)
        return {**source, **stable_order(catalog, source["decisions"], source.get("direction", "ascending")), "reviewStatus": "model-reviewed"}
    # Legacy callable kept for compatibility tests; CLI requires explicit migration.
    if source.get("formatVersion") != 2 or source.get("snapshotFingerprint") != catalog.get("snapshotFingerprint"):
        raise RuntimeError("decision format version or fingerprint mismatch")
    items = {int(item["gid"]): item for item in catalog["items"]}
    decisions = source.get("decisions", [])
    decision_by_gid = {int(row["gid"]): row for row in decisions}
    if len(decision_by_gid) != len(decisions) or set(decision_by_gid) != set(items):
        raise RuntimeError("decisions must contain every catalog GID exactly once")

    groups = defaultdict(list)
    for gid, row in decision_by_gid.items():
        series_id = row.get("canonicalSeriesId")
        if not series_id:
            raise RuntimeError(f"GID {gid} is missing canonicalSeriesId")
        groups[series_id].append(gid)

    # Keep series blocks at the first place where any member originally appeared.
    # This preserves the relative order of unrelated series and standalone items.
    series_order = sorted(
        groups,
        key=lambda series_id: min(items[gid]["originalPosition"] for gid in groups[series_id]),
    )
    gid_order = []
    ordered_decisions = []
    for series_id in series_order:
        def member_key(gid):
            value = decision_by_gid[gid].get("itemOrder")
            known = is_known_order(value)
            # EhViewer displays TIME DESC, so show the newest/highest semantic
            # itemOrder first. Ties and unknown values remain stable by source position.
            return (0 if known else 1, -value if known else 0, items[gid]["originalPosition"])

        members = sorted(groups[series_id], key=member_key)
        gid_order.extend(members)
        ordered_decisions.extend(decision_by_gid[gid] for gid in members)

    return {
        "formatVersion": 2,
        "snapshotFingerprint": catalog["snapshotFingerprint"],
        "status": "model-reviewed-pending-human-review",
        "decisions": ordered_decisions,
        "seriesOrder": series_order,
        "gidOrder": gid_order,
    }


def main():
    parser = argparse.ArgumentParser(description="Build stable seriesOrder and gidOrder arrays from full semantic decisions.")
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--decisions", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"refusing to overwrite {args.output}")
    from common import read_json, require
    catalog = read_json(args.catalog)
    source = read_json(args.decisions)
    require(catalog.get("formatVersion") == 3, "explicitly migrate v2 before building a review")
    output = build_stable_order(catalog, source)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    series_counts = defaultdict(int)
    for row in output["decisions"]:
        series_counts[row["canonicalSeriesId"]] += 1
    print(json.dumps({"itemCount": len(output["gidOrder"]), "seriesCount": len(output["seriesOrder"]), "multiItemSeriesCount": sum(count > 1 for count in series_counts.values())}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
