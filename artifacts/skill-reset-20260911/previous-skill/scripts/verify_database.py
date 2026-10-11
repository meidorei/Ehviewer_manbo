#!/usr/bin/env python3
import argparse
from pathlib import Path
from common import *
from database import validate_database,logical_digest

def main():
    p=argparse.ArgumentParser(description='Verify re-exported DB against the confirmed JSON and verified sorted copy.')
    for arg in ('db','expected-db','catalog','model','order','report'):p.add_argument('--'+arg,required=True,type=Path)
    p.add_argument('--phase',required=True,choices=('offline','pre-launch','post-launch'))
    a=p.parse_args();catalog=read_json(a.catalog);order=read_json(a.order)
    validate_order(catalog,order,read_json(a.model),sha256_file(a.model),confirmed=True)
    validate_database(a.db,a.expected_db,catalog,order)
    write_json(a.report,dict(status='passed',kind='database-verification',phase=a.phase,
        snapshotFingerprint=catalog['snapshotFingerprint'],metadataFingerprint=catalog['metadataFingerprint'],
        orderSha256=sha256_file(a.order),modelSha256=sha256_file(a.model),sortedSha256=sha256_file(a.expected_db),
        actualSha256=sha256_file(a.db),nonTimeDigest=logical_digest(a.db),integrityCheck='ok',orderMatches=True))
if __name__=='__main__':main()
