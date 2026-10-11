#!/usr/bin/env python3
"""Record one full semantic pass plus selected follow-ups, or optional deep audits."""
import argparse
import copy
from pathlib import Path
from common import *


def record(catalog, source, result, round_name, input_hash):
    validate_binding(catalog, source)
    validate_audits(catalog, source, complete=False)
    require(next_round(source) == round_name, 'round must be the next required audit')
    require(type(result.get('formatVersion')) is int and result['formatVersion'] == 3, 'v3 result required')
    for key in ('snapshotFingerprint', 'metadataFingerprint'):
        require(result.get(key) == catalog[key], key + ' mismatch')
    if 'auditMode' in result:
        require(audit_mode(result) == audit_mode(source), 'result audit mode mismatch')
    require(result.get('inputArtifactDigest') == input_hash, 'round result references a different input artifact')
    gids = result.get('reviewedGids')
    rows = result.get('decisions')
    require(isinstance(gids, list) and all(integer(g) for g in gids) and len(gids) == len(set(gids)), 'invalid reviewedGids')
    require(result.get('conflicts') == [], 'no conflicts required')
    require(nonempty(result.get('summary')), 'round requires a semantic summary')
    require(isinstance(rows, list) and all(isinstance(r, dict) and integer(r.get('gid')) for r in rows), 'invalid result decisions')
    require(len(rows) == len(gids) and {r['gid'] for r in rows} == set(gids), 'result decision/coverage mismatch')
    changed = []
    if round_name == 'focused':
        all_gids = {x['gid'] for x in catalog['items']}
        require(bool(gids) and set(gids) <= all_gids, 'focused audit needs valid selected GIDs')
        replacements = {r['gid']: r for r in rows}
        changed = sorted(r['gid'] for r in source['decisions'] if r['gid'] in replacements and r != replacements[r['gid']])
        # Only selected rows may change; no unreviewed row is taken from the result.
        rows = [replacements.get(r['gid'], r) for r in source['decisions']]
    validate_decisions(catalog, rows)
    entry = dict(round=round_name, metadataFingerprint=catalog['metadataFingerprint'], inputArtifactDigest=input_hash,
                 inputDecisionsDigest=decision_digest(source['decisions']), outputDecisionsDigest=decision_digest(rows),
                 reviewedGids=gids, conflicts=[], summary=result['summary'])
    if round_name == 'focused':
        entry['changedGids'] = changed
    output = envelope(catalog, rows, source['auditTrail'] + [entry])
    if 'auditMode' in source:
        output['auditMode'] = source['auditMode']
    if 'seedSourceDigest' in source:
        output['seedSourceDigest'] = source['seedSourceDigest']
    validate_audits(catalog, output, complete=False)
    return output


def main():
    p = argparse.ArgumentParser(description='One semantic pass by default; focused revisions or optional four-round deep review.')
    p.add_argument('operation', choices=('init', 'prepare', 'record', 'merge'))
    p.add_argument('--catalog', required=True, type=Path)
    p.add_argument('--input', type=Path)
    p.add_argument('--result', type=Path)
    p.add_argument('--results-dir', type=Path)
    p.add_argument('--round', choices=(*ROUNDS, 'semantic', 'focused'))
    p.add_argument('--mode', choices=AUDIT_MODES, help='init only; defaults to lightweight. Missing mode in old files remains deep.')
    p.add_argument('--gids', nargs='+', type=int, help='prepare a focused follow-up for these GIDs only')
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    catalog = read_json(a.catalog)
    validate_catalog(catalog)
    if a.operation == 'init':
        require(a.gids is None, '--gids is only for focused prepare')
        if a.input:
            seed = read_json(a.input)
            validate_binding(catalog, seed)
            validate_audits(catalog, seed, complete=False)
            rows = copy.deepcopy(seed['decisions'])
            for row in rows:
                row['needsHumanReview'] = True
        else:
            rows = [singleton(x) for x in catalog['items']]
        output = envelope(catalog, rows)
        output['auditMode'] = a.mode or 'lightweight'
        if a.input:
            output['seedSourceDigest'] = sha256_file(a.input)
        write_json(a.output, output)
        return
    require(a.mode is None, '--mode is init-only; use init --input to explicitly seed another mode')
    require(a.gids is None or a.operation == 'prepare', '--gids is only for focused prepare')
    require(a.input is not None, '--input required')
    source = read_json(a.input)
    validate_binding(catalog, source)
    validate_audits(catalog, source, False)
    round_name = next_round(source)
    if a.operation == 'merge':
        require(a.results_dir is not None, 'merge needs --results-dir containing only this pass batch results')
        rows = {}
        reviewed = set()
        summaries = []
        input_hash = sha256_file(a.input)
        files = sorted(a.results_dir.glob('*.json'))
        require(files or not catalog['items'], 'no batch results found')
        for file in files:
            batch = read_json(file)
            require(type(batch.get('formatVersion')) is int and batch['formatVersion'] == 3 and batch.get('inputArtifactDigest') == input_hash, 'batch input binding mismatch')
            require(batch.get('snapshotFingerprint') == catalog['snapshotFingerprint'] and batch.get('metadataFingerprint') == catalog['metadataFingerprint'], 'batch catalog binding mismatch')
            if 'auditMode' in batch:
                require(audit_mode(batch) == audit_mode(source), 'batch audit mode mismatch')
            require(batch.get('conflicts') == [] and nonempty(batch.get('summary')), 'batch has conflicts or no summary')
            decisions = batch.get('decisions')
            gids = batch.get('reviewedGids')
            require(isinstance(decisions, list) and isinstance(gids, list) and all(integer(g) for g in gids) and len(gids) == len(set(gids)), 'invalid batch coverage')
            require(all(isinstance(row, dict) and integer(row.get('gid')) for row in decisions), 'invalid batch decision')
            require(set(gids) == {row['gid'] for row in decisions} and len(decisions) == len(gids), 'batch decision/coverage mismatch')
            for row in decisions:
                require(row['gid'] not in rows or rows[row['gid']] == row, 'conflicting overlapping batch decisions; resolve explicitly')
                rows[row['gid']] = row
            reviewed.update(gids)
            summaries.append(batch['summary'])
        result = {**envelope(catalog, list(rows.values())), 'reviewedGids': sorted(reviewed), 'inputArtifactDigest': input_hash,
                  'conflicts': [], 'summary': '\n'.join(summaries) or 'Empty catalog; no semantic items.'}
        write_json(a.output, record(catalog, source, result, round_name, input_hash))
        return
    if a.operation == 'record':
        require(a.result is not None and a.round is not None, 'record needs --result and --round')
        write_json(a.output, record(catalog, source, read_json(a.result), a.round, sha256_file(a.input)))
        return
    rows = {r['gid']: r for r in source['decisions']}
    if round_name == 'focused':
        require(a.gids and all(integer(g) for g in a.gids) and len(a.gids) == len(set(a.gids)) and set(a.gids) <= set(rows), 'focused prepare requires unique catalog --gids')
        selected = set(a.gids)
    else:
        require(a.gids is None, 'initial/deep review covers the full catalog; --gids is for focused review')
        selected = set(rows)
    series = {}
    for row in rows.values():
        series.setdefault(row['canonicalSeriesId'], []).append(row['gid'])
    safe = [dict(item, decision=rows[item['gid']]) for item in catalog['items'] if item['gid'] in selected]
    related_ids = {rows[g]['canonicalSeriesId'] for g in selected}
    context = [dict(item, decision=rows[item['gid']]) for item in catalog['items']
               if item['gid'] not in selected and rows[item['gid']]['canonicalSeriesId'] in related_ids] if round_name == 'focused' else []
    output = dict(formatVersion=3, auditMode=audit_mode(source), round=round_name, inputArtifactDigest=sha256_file(a.input),
                  metadataFingerprint=catalog['metadataFingerprint'], snapshotFingerprint=catalog['snapshotFingerprint'],
                  reviewGids=sorted(selected),
                  batches=[dict(batchId=i//75+1, items=safe[i:i+75]) for i in range(0, len(safe), 75)],
                  contextBatches=[context[i:i+75] for i in range(0, len(context), 75)],
                  unresolvedGids=[r['gid'] for r in rows.values() if len(series[r['canonicalSeriesId']]) == 1 or r['needsHumanReview']],
                  seriesIndex=[dict(seriesId=s, gids=gids) for s, gids in series.items()])
    write_json(a.output, output)


if __name__ == '__main__':
    main()
