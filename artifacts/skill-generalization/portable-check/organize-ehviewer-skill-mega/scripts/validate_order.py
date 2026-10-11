#!/usr/bin/env python3
import argparse
from pathlib import Path
from common import read_json, write_json, validate_order, sha256_file

def main():
    p=argparse.ArgumentParser(description='Validate v3 model order or replay a human review export.')
    p.add_argument('--catalog',required=True,type=Path);p.add_argument('--order',required=True,type=Path)
    p.add_argument('--model',type=Path);p.add_argument('--report',type=Path);p.add_argument('--confirmed',action='store_true')
    a=p.parse_args();catalog=read_json(a.catalog);order=read_json(a.order)
    validate_order(catalog,order,read_json(a.model) if a.model else None,sha256_file(a.model) if a.model else None,a.confirmed)
    result=dict(status='passed',kind='order-validation',itemCount=len(order['gidOrder']),seriesCount=len(order['seriesOrder']),
                snapshotFingerprint=catalog['snapshotFingerprint'],metadataFingerprint=catalog['metadataFingerprint'],orderSha256=sha256_file(a.order))
    if a.report:write_json(a.report,result)
    print(__import__('json').dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
