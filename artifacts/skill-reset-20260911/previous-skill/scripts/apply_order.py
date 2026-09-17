#!/usr/bin/env python3
import argparse
import sqlite3
import time
from pathlib import Path
from common import read_json, write_json, validate_order, sha256_file, require
from database import ClosingConnection, backup_database, catalog_from_database, logical_digest, ordered_rows, validate_database
TIME_STEP_MILLIS=1000

def assign_current_time_slots(source_gids,gid_order,base_time):
    require(len(source_gids)==len(set(source_gids)),'source database contains duplicate GIDs')
    require(len(gid_order)==len(source_gids) and len(gid_order)==len(set(gid_order)) and set(gid_order)==set(source_gids),'confirmed order must contain every source GID exactly once')
    require(type(base_time) is int and base_time>=0,'base time must be a non-negative integer')
    require(base_time-max(0,len(gid_order)-1)*1000>=0,'current-time sequence would underflow')
    return [(gid,base_time-i*1000) for i,gid in enumerate(gid_order)]

def main():
    p=argparse.ArgumentParser(description='Create a backed-up sorted database copy from a verified, confirmed v3 review. Never replaces live DB.')
    for arg in ('db','catalog','model','order','backup','output','report'):p.add_argument('--'+arg,required=True,type=Path)
    a=p.parse_args();catalog=read_json(a.catalog);order=read_json(a.order)
    validate_order(catalog,order,read_json(a.model),sha256_file(a.model),confirmed=True)
    require(catalog.get('source',{}).get('writebackSupported') is True and catalog['source'].get('timeUnit')=='milliseconds','catalog adapter does not authorize compatible millisecond TIME writeback')
    outputs=[a.backup.resolve(),a.output.resolve(),a.report.resolve()]
    require(len(set(outputs))==3 and not any(x.exists() for x in outputs),'output paths must be distinct and new')
    # The backup, including committed WAL changes, becomes the immutable source of this transaction.
    backup_database(a.db,a.backup)
    fresh=catalog_from_database(a.backup,'milliseconds')
    require(fresh['source']['writebackSupported'],'unsupported database for writeback')
    for key in ('snapshotFingerprint','metadataFingerprint'):require(fresh[key]==catalog[key],key+' changed since review')
    original_digest=logical_digest(a.backup);backup_database(a.backup,a.output)
    base=time.time_ns()//1000000
    assignments=assign_current_time_slots([x['gid'] for x in fresh['items']],order['gidOrder'],base)
    with sqlite3.connect(a.output,factory=ClosingConnection) as db:
        db.execute('PRAGMA journal_mode=DELETE');db.execute('BEGIN IMMEDIATE')
        for gid,slot in assignments:
            require(db.execute('UPDATE DOWNLOADS SET TIME=? WHERE GID=?',(slot,gid)).rowcount==1,'unexpected affected row count')
    require(ordered_rows(a.output)==assignments,'sorted copy failed exact TIME order')
    require(logical_digest(a.output)==original_digest,'sorted copy changed non-TIME data or schema')
    validate_database(a.output,a.output,catalog,order)
    report=dict(status='passed',kind='database-verification',phase='dry-run',itemCount=len(assignments),
                snapshotFingerprint=catalog['snapshotFingerprint'],metadataFingerprint=catalog['metadataFingerprint'],
                orderSha256=sha256_file(a.order),modelSha256=sha256_file(a.model),backupSha256=sha256_file(a.backup),
                sortedSha256=sha256_file(a.output),nonTimeDigest=original_digest,integrityCheck='ok',orderMatches=True,
                timeStrategy='current-time-descending-seconds',timeBase=base,timeStepMillis=1000)
    write_json(a.report,report);print(__import__('json').dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
