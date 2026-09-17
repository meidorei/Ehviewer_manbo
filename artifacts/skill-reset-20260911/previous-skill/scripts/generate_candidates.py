#!/usr/bin/env python3
import argparse
import json
from common import read_json, loads
import re
import unicodedata
from collections import defaultdict
from difflib import SequenceMatcher
from pathlib import Path


BRACKET = re.compile(r"\[([^\]]+)\]")
PAREN = re.compile(r"\(([^)]*)\)")
NON_WORD = re.compile(r"[^0-9a-z\u3040-\u30ff\u3400-\u9fff]+")
SEQUENCE = re.compile(r"(?ix)(?:\b(?:ch(?:apter)?|ep(?:isode)?|vol(?:ume)?|part)\.?\s*[-#:]*\s*)(\d+(?:\.\d+)?)|第\s*(\d+(?:\.\d+)?)\s*(?:話|话|章|巻|卷|部|篇|集)")
RANGE = re.compile(r"(?<!\d)(\d{1,3})\s*[-~～至]\s*(\d{1,3})(?!\d)")
PART = re.compile(r"(?i)\b(?:zenpen|chuuhen|kouhen|first|middle|final|extra|remake|remaster)\b|前[編篇]|中[編篇]|後[編篇]|后[篇编]|上[巻卷篇]?|中[巻卷篇]?|下[巻卷篇]?|番外|外[伝传]|重[制製]|[总総]集[篇編]|合集")
LANGUAGE = {"chinese", "english", "japanese", "digital", "decensored", "uncensored", "translated", "sample", "中文", "汉化", "漢化", "中国翻訳", "中國翻譯", "無修正", "无修正"}
DISCOVERY_COLLISION_LIMIT = 40
REVIEW_BATCH_SIZE = 75


def fold(text):
    return unicodedata.normalize("NFKC", text or "").casefold()


def normalize_piece(text):
    return "".join(c for c in re.sub(r"\([^)]*\)", " ", fold(text)) if c.isalnum())


def authors(title):
    cleaned = re.sub(r"^(?:\([^)]*\)\s*)+(?=\[)", "", (title or "").strip())
    match = BRACKET.match(cleaned)
    if not match or fold(match.group(1)).strip() in LANGUAGE:
        return []
    raw = match.group(1)
    values = [raw, *re.split(r"[/,&、，]", raw)]
    paren = PAREN.search(raw)
    if paren:
        values.extend(re.split(r"[/,&、，]", paren.group(1)))
        values.append(raw[:paren.start()])
    result = []
    for value in values:
        normalized = normalize_piece(value)
        if len(normalized) >= 2 and normalized not in result:
            result.append(normalized)
    return result


def skeleton(title):
    text = fold(title)
    text = BRACKET.sub(" ", text)
    text = PAREN.sub(" ", text)
    text = SEQUENCE.sub(" ", text)
    text = RANGE.sub(" ", text)
    text = PART.sub(" ", text)
    return NON_WORD.sub("", text)


def tokens(title):
    text = fold(title)
    text = BRACKET.sub(" ", text)
    text = PAREN.sub(" ", text)
    text = SEQUENCE.sub(" ", text)
    text = RANGE.sub(" ", text)
    text = PART.sub(" ", text)
    return {token for token in NON_WORD.sub(" ", text).split() if len(token) >= 3}


def language_signature(item):
    text = " ".join(str(item.get(field) or "") for field in ("title", "titleJpn"))
    values = []
    if re.search(r"[\u3040-\u30ff]", text):
        values.append("japanese")
    if re.search(r"[\u3400-\u9fff]", text):
        values.append("han")
    if re.search(r"[a-zA-Z]", text):
        values.append("latin")
    return "+".join(values) or "other"


def normalized_discovery_values(values):
    return {normalize_piece(value) for value in values if normalize_piece(value)}


def load_discovery(path, catalog):
    discovery = loads(path.read_text(encoding="utf-8"))
    if discovery.get("formatVersion") != 1 or discovery.get("snapshotFingerprint") != catalog.get("snapshotFingerprint"):
        raise RuntimeError("discovery format version or fingerprint mismatch")
    if catalog.get("metadataFingerprint") and discovery.get("metadataFingerprint") != catalog["metadataFingerprint"]:
        raise RuntimeError("discovery title metadata fingerprint mismatch")
    annotations = discovery.get("annotations")
    catalog_gids = {int(item["gid"]) for item in catalog["items"]}
    if not isinstance(annotations, list):
        raise RuntimeError("discovery annotations are missing")
    by_gid = {}
    for annotation in annotations:
        if not isinstance(annotation, dict) or isinstance(annotation.get("gid"), bool) or not isinstance(annotation.get("gid"), int):
            raise RuntimeError("discovery annotation GID is invalid")
        gid = annotation["gid"]
        if gid in by_gid:
            raise RuntimeError("discovery annotations contain duplicate GIDs")
        by_gid[gid] = annotation
    if set(by_gid) != catalog_gids:
        raise RuntimeError("discovery annotations must contain every catalog GID exactly once")
    from validate_discovery import validate_annotation
    errors = []
    for gid, annotation in by_gid.items():
        validate_annotation(annotation, gid, errors)
    if errors:
        raise RuntimeError("invalid discovery annotations: " + "; ".join(errors))
    return by_gid


class UnionFind:
    def __init__(self, values):
        self.parent = {value: value for value in values}

    def find(self, value):
        while self.parent[value] != value:
            self.parent[value] = self.parent[self.parent[value]]
            value = self.parent[value]
        return value

    def union(self, left, right):
        left, right = self.find(left), self.find(right)
        if left != right:
            self.parent[right] = left


def main():
    parser = argparse.ArgumentParser(description="Generate overlapping high-recall manga-series candidates and author buckets.")
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--decisions", type=Path, help="Latest v3 decisions; rebuild unresolved after every round.")
    parser.add_argument("--discovery", type=Path, help="Optional validated discovery annotations; omitted for title-only recall.")
    parser.add_argument("--candidates", required=True, type=Path)
    parser.add_argument("--author-buckets", required=True, type=Path)
    parser.add_argument("--unresolved", required=True, type=Path, help="Output index for entries not placed in any candidate component.")
    args = parser.parse_args()
    for output in (args.candidates, args.author_buckets, args.unresolved):
        if output.exists():
            raise FileExistsError(f"refusing to overwrite {output}")
    catalog = loads(args.catalog.read_text(encoding="utf-8"))
    items = sorted(catalog["items"], key=lambda x: x["originalPosition"])
    if catalog.get("formatVersion") == 3:
        from common import validate_catalog
        validate_catalog(catalog)
    by_gid = {int(item["gid"]): item for item in items}
    discovery_by_gid = load_discovery(args.discovery, catalog) if args.discovery else {gid: {} for gid in by_gid}
    derived = {}
    author_buckets = defaultdict(list)
    skeleton_buckets = defaultdict(list)
    discovery_alias_buckets = defaultdict(list)
    discovery_key_buckets = defaultdict(list)
    for item in items:
        gid = int(item["gid"])
        item_authors = []
        item_skeletons = []
        for title in (item.get("title"), item.get("titleJpn")):
            for author in authors(title):
                if author not in item_authors:
                    item_authors.append(author)
            title_skeleton = skeleton(title)
            if len(title_skeleton) >= 4 and title_skeleton not in item_skeletons:
                item_skeletons.append(title_skeleton)
        sequence_signal = any(SEQUENCE.search(title or "") or RANGE.search(title or "") or PART.search(title or "") for title in (item.get("title"), item.get("titleJpn")))
        item_tokens = set()
        for title in (item.get("title"), item.get("titleJpn")):
            item_tokens.update(tokens(title))
        annotation = discovery_by_gid[gid]
        for alias in sorted(normalized_discovery_values(annotation.get("authorAliases", []))):
            if alias not in item_authors:
                item_authors.append(alias)
        derived[gid] = {
            "authors": item_authors,
            "skeletons": item_skeletons,
            "sequenceSignal": sequence_signal,
            "tokens": sorted(item_tokens),
            "languageSignature": language_signature(item),
            "discovery": annotation,
        }
        for author in item_authors:
            author_buckets[author].append(gid)
        for title_skeleton in item_skeletons:
            skeleton_buckets[title_skeleton].append(gid)
        for alias in normalized_discovery_values(annotation.get("workAliases", [])):
            discovery_alias_buckets[alias].append(gid)
        semantic_key = normalize_piece(annotation.get("semanticTitleKey") or "")
        if semantic_key:
            discovery_key_buckets[semantic_key].append(gid)

    union = UnionFind(by_gid)
    reasons = defaultdict(set)
    collision_audits = []

    def link_or_audit(kind, key, gids):
        unique = list(dict.fromkeys(gids))
        if len(unique) < 2:
            return
        if len(unique) > DISCOVERY_COLLISION_LIMIT:
            collision_audits.append({
                "auditId": f"collision:{kind}:{len(collision_audits) + 1:05d}",
                "kind": kind,
                "key": key,
                "confidence": "low",
                "reason": "shared discovery value exceeds the safe candidate collision limit",
                "gids": unique,
                "batches": [[{**by_gid[gid], "discovery": discovery_by_gid[gid]} for gid in unique[i:i+REVIEW_BATCH_SIZE]] for i in range(0,len(unique),REVIEW_BATCH_SIZE)],
            })
            return
        for gid in unique[1:]:
            union.union(unique[0], gid)
            reasons[tuple(sorted((unique[0], gid)))].add(kind)

    for key, gids in skeleton_buckets.items():
        link_or_audit("same-title-skeleton", key, gids)
    for key, gids in discovery_alias_buckets.items():
        link_or_audit("discovery-work-alias", key, gids)
    for key, gids in discovery_key_buckets.items():
        link_or_audit("discovery-semantic-title-key", key, gids)
    for bucket in author_buckets.values():
        gids = list(dict.fromkeys(bucket))
        if not 2 <= len(gids) <= 120:
            continue
        for index, left in enumerate(gids):
            for right in gids[index + 1:]:
                ratio = max((SequenceMatcher(None, a, b).ratio() for a in derived[left]["skeletons"] for b in derived[right]["skeletons"]), default=0)
                sequence_signal = derived[left]["sequenceSignal"] or derived[right]["sequenceSignal"]
                if ratio >= 0.86 or (ratio >= 0.68 and sequence_signal):
                    union.union(left, right)
                    reasons[tuple(sorted((left, right)))].add("same-author-fuzzy-title")
                    if sequence_signal:
                        reasons[tuple(sorted((left, right)))].add("numeric-or-part-sequence")

    # Nearby rows with a distinctive shared token are a deliberately weak recall channel.
    for index, left_item in enumerate(items):
        left = int(left_item["gid"])
        left_tokens = set(derived[left]["tokens"])
        for right_item in items[index + 1:index + 13]:
            right = int(right_item["gid"])
            shared = left_tokens.intersection(derived[right]["tokens"])
            if not any(len(token) >= 6 for token in shared):
                continue
            ratio = max((SequenceMatcher(None, a, b).ratio() for a in derived[left]["skeletons"] for b in derived[right]["skeletons"]), default=0)
            if ratio >= 0.58:
                union.union(left, right)
                reasons[tuple(sorted((left, right)))].add("nearby-shared-title-token")

    components = defaultdict(list)
    for gid in by_gid:
        components[union.find(gid)].append(gid)
    candidate_rows = []
    for gids in components.values():
        if len(gids) < 2:
            continue
        gids.sort(key=lambda gid: by_gid[gid]["originalPosition"])
        gid_set = set(gids)
        recall = sorted({reason for pair, pair_reasons in reasons.items() if set(pair) <= gid_set for reason in pair_reasons})
        component_id = f"component:{gids[0]}"
        for start in range(0, len(gids), REVIEW_BATCH_SIZE):
            batch = gids[start:start+REVIEW_BATCH_SIZE]
            candidate_rows.append({"candidateId": "pending", "componentId": component_id,
                "componentGids": gids, "recallReasons": recall,
                "items": [{**by_gid[gid], "derived": derived[gid]} for gid in batch]})
    candidate_rows.sort(key=lambda row: row["items"][0]["originalPosition"])
    for index, row in enumerate(candidate_rows, 1):
        row["candidateId"] = f"candidate:{index:05d}"
    audits = []
    seen_sets = set()
    for author, gids in author_buckets.items():
        unique = tuple(sorted(set(gids), key=lambda gid: by_gid[gid]["originalPosition"]))
        if len(unique) >= 2 and unique not in seen_sets:
            seen_sets.add(unique)
            audits.append({"authorKey": author, "itemCount": len(unique), "gids": list(unique),
                           "batches": [[by_gid[gid] for gid in unique[i:i+REVIEW_BATCH_SIZE]] for i in range(0,len(unique),REVIEW_BATCH_SIZE)]})
    audits.sort(key=lambda row: (-row["itemCount"], row["authorKey"]))
    unresolved_gids = [gid for gid in by_gid if len(components[union.find(gid)]) == 1]

    if args.decisions:
        from common import read_json, validate_binding
        latest = read_json(args.decisions)
        validate_binding(catalog, latest)
        counts = defaultdict(int)
        for row in latest["decisions"]: counts[row["canonicalSeriesId"]] += 1
        unresolved_gids = [row["gid"] for row in latest["decisions"] if counts[row["canonicalSeriesId"]] == 1 or row["needsHumanReview"]]

    def unresolved_index(getter):
        indexed = defaultdict(list)
        for gid in unresolved_gids:
            for key in getter(gid):
                if key:
                    indexed[key].append(gid)
        return [{"key": key, "gids": gids} for key, gids in sorted(indexed.items())]

    unresolved_output = {
        "formatVersion": 1,
        "snapshotFingerprint": catalog["snapshotFingerprint"],
        "metadataFingerprint": catalog.get("metadataFingerprint"),
        "itemCount": len(unresolved_gids),
        "items": [{**by_gid[gid], "discovery": discovery_by_gid[gid], "languageSignature": derived[gid]["languageSignature"]} for gid in unresolved_gids],
        "indexes": {
            "authorAliases": unresolved_index(lambda gid: sorted(normalized_discovery_values(discovery_by_gid[gid].get("authorAliases", [])))),
            "semanticTitleKeys": unresolved_index(lambda gid: [normalize_piece(discovery_by_gid[gid].get("semanticTitleKey") or "")]),
            "languageSignatures": unresolved_index(lambda gid: [derived[gid]["languageSignature"]]),
            "parts": unresolved_index(lambda gid: [f"{discovery_by_gid[gid].get('partKind', 'unknown')}:{discovery_by_gid[gid].get('partNumber') or ''}"]),
        },
    }
    candidate_output = {
        "formatVersion": 2,
        "snapshotFingerprint": catalog["snapshotFingerprint"],
        "metadataFingerprint": catalog.get("metadataFingerprint"),
        "edges": [{"gids": list(pair), "reasons": sorted(values)} for pair,values in sorted(reasons.items())],
        "recallSource": "discovery-and-titles" if args.discovery else "titles-only",
        "candidateCount": len(candidate_rows),
        "collisionAuditCount": len(collision_audits),
        "candidates": candidate_rows,
        "collisionAudits": collision_audits,
    }
    author_output = {"formatVersion": 2, "snapshotFingerprint": catalog["snapshotFingerprint"], "metadataFingerprint": catalog.get("metadataFingerprint"), "authorBucketCount": len(audits), "buckets": audits}
    args.candidates.write_text(json.dumps(candidate_output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.author_buckets.write_text(json.dumps(author_output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.unresolved.write_text(json.dumps(unresolved_output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"candidateCount": len(candidate_rows), "authorBucketCount": len(audits), "collisionAuditCount": len(collision_audits), "unresolvedItemCount": len(unresolved_gids)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
