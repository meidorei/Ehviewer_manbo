#!/usr/bin/env python3
"""Record complete primary-model round results with a verifiable artifact chain."""
import argparse
from pathlib import Path
from common import *

def record(catalog, source, result, round_name, input_hash):
    validate_binding(catalog,source);validate_audits(catalog,source,complete=False)
    index=len(source['auditTrail'])
    require(index<4 and ROUNDS[index]==round_name,'round must be the next required audit')
    validate_binding(catalog,result)
    require(result.get("inputArtifactDigest")==input_hash,"round result references a different input artifact")
    require(result.get('reviewedGids') is not None and result.get('conflicts')==[],'complete reviewedGids and no conflicts required')
    require(nonempty(result.get('summary')),'round requires a semantic summary')
    entry=dict(round=round_name,metadataFingerprint=catalog['metadataFingerprint'],inputArtifactDigest=input_hash,
               inputDecisionsDigest=decision_digest(source['decisions']),outputDecisionsDigest=decision_digest(result['decisions']),
               reviewedGids=result['reviewedGids'],conflicts=[],summary=result['summary'])
    output=envelope(catalog,result['decisions'],source['auditTrail']+[entry]);validate_audits(catalog,output,complete=False)
    return output

def main():
    p=argparse.ArgumentParser(description='Initialize review decisions, prepare bounded audit inputs, or record the next completed round.')
    p.add_argument('operation',choices=('init','prepare','record','merge'))
    p.add_argument('--catalog',required=True,type=Path);p.add_argument('--input',type=Path);p.add_argument('--result',type=Path)
    p.add_argument('--results-dir',type=Path);p.add_argument('--round',choices=ROUNDS);p.add_argument('--output',required=True,type=Path)
    a=p.parse_args();catalog=read_json(a.catalog);validate_catalog(catalog)
    if a.operation=='init':write_json(a.output,envelope(catalog,[singleton(x) for x in catalog['items']]));return
    require(a.input is not None,'--input required');source=read_json(a.input);validate_binding(catalog,source);validate_audits(catalog,source,False)
    if a.operation=='merge':
        require(a.results_dir is not None, 'merge needs --results-dir containing only this round batch results')
        rows = {}; reviewed = set(); summaries = []
        input_hash = sha256_file(a.input)
        files = sorted(a.results_dir.glob('*.json'))
        require(files or not catalog['items'], 'no batch results found')
        for file in files:
            batch = read_json(file)
            require(batch.get('formatVersion') == 3 and batch.get('inputArtifactDigest') == input_hash, 'batch input binding mismatch')
            require(batch.get('snapshotFingerprint') == catalog['snapshotFingerprint'] and batch.get('metadataFingerprint') == catalog['metadataFingerprint'], 'batch catalog binding mismatch')
            require(batch.get('conflicts') == [] and nonempty(batch.get('summary')), 'batch has conflicts or no summary')
            decisions = batch.get('decisions'); gids = batch.get('reviewedGids')
            require(isinstance(decisions, list) and isinstance(gids, list) and all(integer(g) for g in gids) and len(gids) == len(set(gids)), 'invalid batch coverage')
            require(all(isinstance(row, dict) and integer(row.get('gid')) for row in decisions), 'invalid batch decision')
            require(set(gids) == {row['gid'] for row in decisions} and len(decisions) == len(gids), 'batch decision/coverage mismatch')
            for row in decisions:
                require(row['gid'] not in rows or rows[row['gid']] == row, 'conflicting overlapping batch decisions; resolve explicitly')
                rows[row['gid']] = row
            reviewed.update(gids); summaries.append(batch['summary'])
        result = {**envelope(catalog, list(rows.values())), 'reviewedGids': sorted(reviewed), 'inputArtifactDigest': input_hash,
                  'conflicts': [], 'summary': '\n'.join(summaries) or 'Empty catalog; no semantic items.'}
        require(len(source['auditTrail']) < 4, 'all rounds already completed')
        write_json(a.output, record(catalog, source, result, ROUNDS[len(source['auditTrail'])], input_hash)); return
    if a.operation=='record':
        require(a.result is not None and a.round is not None,'record needs --result and --round')
        write_json(a.output,record(catalog,source,read_json(a.result),a.round,sha256_file(a.input)));return
    require(len(source['auditTrail'])<4,'all rounds already completed')
    rows={r['gid']:r for r in source['decisions']};counts={}
    for r in rows.values():counts[r['canonicalSeriesId']]=counts.get(r['canonicalSeriesId'],0)+1
    safe=[dict(item,decision=rows[item['gid']]) for item in catalog['items']]
    output=dict(formatVersion=3,round=ROUNDS[len(source['auditTrail'])],inputArtifactDigest=sha256_file(a.input),
                metadataFingerprint=catalog['metadataFingerprint'],snapshotFingerprint=catalog['snapshotFingerprint'],
                batches=[dict(batchId=i//75+1,items=safe[i:i+75]) for i in range(0,len(safe),75)],
                unresolvedGids=[r['gid'] for r in rows.values() if counts[r['canonicalSeriesId']]==1 or r['needsHumanReview']],
                seriesIndex=[dict(seriesId=s,gids=[r['gid'] for r in rows.values() if r['canonicalSeriesId']==s]) for s in counts])
    write_json(a.output,output)
if __name__=='__main__':main()
