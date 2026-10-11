#!/usr/bin/env python3
import argparse
from pathlib import Path
from common import read_json, write_json, make_catalog, singleton, envelope, require, fingerprints, sha256_file

def migrate(catalog, old=None, source_hash=None):
    require(catalog.get('formatVersion')==2,'migration requires a version-2 catalog')
    current=make_catalog(catalog['items'])
    require(current['snapshotFingerprint']==catalog.get('snapshotFingerprint'),'legacy catalog fingerprint mismatch')
    rows={x['gid']:singleton(x) for x in current['items']}
    if old is not None:
        require(old.get('formatVersion')==2 and old.get('snapshotFingerprint')==catalog['snapshotFingerprint'],'legacy decisions binding mismatch')
        seen=set()
        for r in old.get('decisions',[]):
            gid=r.get('gid');require(type(gid) is int and gid in rows and gid not in seen,'invalid legacy decision GID');seen.add(gid)
            row=rows[gid]
            for k in ('canonicalSeriesId','canonicalSeriesTitle','reason'):
                if isinstance(r.get(k),str) and r[k].strip():row[k]=r[k]
            # v2 conflated translation variants, category, branches and unknown/zero numbers.
            row['evidence']=[{'kind':'history','claim':'旧版结论仅作为待审线索。','sourceDigest':source_hash,'legacyDecision':{k:r.get(k) for k in ('gid','canonicalSeriesId','canonicalSeriesTitle','branch','itemOrder','confidence','reason')}}]
        require(seen==set(rows),'legacy decisions must cover catalog')
    result=envelope(current,list(rows.values()));result['migrationStatus']='pending-four-round-review'
    return current,result

def main():
    p=argparse.ArgumentParser(description='Explicitly migrate v2 metadata; never confirms or writes a database.')
    p.add_argument('--catalog',required=True,type=Path);p.add_argument('--decisions',type=Path)
    p.add_argument('--output-catalog',required=True,type=Path);p.add_argument('--output-decisions',required=True,type=Path)
    a=p.parse_args();c,d=migrate(read_json(a.catalog),read_json(a.decisions) if a.decisions else None,sha256_file(a.decisions) if a.decisions else None)
    require(not a.output_catalog.exists() and not a.output_decisions.exists(),'migration outputs already exist')
    write_json(a.output_catalog,c);write_json(a.output_decisions,d)
if __name__=='__main__':main()
