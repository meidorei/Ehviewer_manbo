#!/usr/bin/env python3
import argparse
from pathlib import Path
from common import read_json, write_json, make_catalog, validate_catalog, sha256_file, require
from database import backup_database, catalog_from_database

def main():
    p=argparse.ArgumentParser(description='Import safe v3 title metadata from a consistent SQLite snapshot or standard JSON.')
    src=p.add_mutually_exclusive_group(required=True);src.add_argument('--db',type=Path);src.add_argument('--json',type=Path)
    p.add_argument('--snapshot',type=Path,help='Required new consistent SQLite backup for --db.')
    p.add_argument('--time-unit',choices=('milliseconds',),help='Explicitly confirm EhViewer millisecond TIME semantics; otherwise read-only catalog.')
    p.add_argument('--catalog',required=True,type=Path);p.add_argument('--summary',required=True,type=Path)
    a=p.parse_args()
    outputs=[x.resolve() for x in (a.snapshot,a.catalog,a.summary) if x]
    require(len(set(outputs))==len(outputs) and not any(x.exists() for x in outputs),'output paths must be distinct and new')
    if a.db:
        require(a.snapshot is not None,'--db requires --snapshot for consistent SQLite backup')
        backup_database(a.db,a.snapshot);catalog=catalog_from_database(a.snapshot,a.time_unit)
    else:
        raw=read_json(a.json)
        require(not isinstance(raw,dict) or raw.get('formatVersion')!=2,'use explicit migrate-v2 for version-2 catalogs')
        if isinstance(raw,dict) and raw.get('formatVersion')==3:validate_catalog(raw)
        catalog=make_catalog(raw if isinstance(raw,list) else raw['items'])
    write_json(a.catalog,catalog)
    summary=dict(formatVersion=3,status='passed',itemCount=len(catalog['items']),snapshotFingerprint=catalog['snapshotFingerprint'],metadataFingerprint=catalog['metadataFingerprint'],source=catalog['source'])
    if a.snapshot:summary['snapshotSha256']=sha256_file(a.snapshot)
    write_json(a.summary,summary)
    print(__import__('json').dumps(summary,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
