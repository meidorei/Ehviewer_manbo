#!/usr/bin/env python3
"""Portable, standard-library-only entry point. Semantic work stays with the primary model."""
import argparse
import subprocess
import sys
from pathlib import Path
COMMANDS={'catalog':'snapshot_catalog','migrate-v2':'migrate_v2','discover':'build_discovery_batches',
'discovery-validate':'validate_discovery','candidates':'generate_candidates','history':'seed_historical_decisions',
'audit':'audit_round','build':'build_order','review':'generate_review_page','validate':'validate_order',
'apply':'apply_order','verify-db':'verify_database','adb':'adb_transfer','manifest':'run_manifest','cleanup':'cleanup_run',
'evaluate':'evaluate'}
def main():
    p=argparse.ArgumentParser(description='EhViewer multilingual series toolkit v3. Use COMMAND --help for arguments.')
    p.add_argument('command',choices=COMMANDS)
    if len(sys.argv)<2 or sys.argv[1] in ('-h','--help'):p.print_help();return
    a=p.parse_args(sys.argv[1:2])
    raise SystemExit(subprocess.call([sys.executable,str(Path(__file__).parent/(COMMANDS[a.command]+'.py')),*sys.argv[2:]]))
if __name__=='__main__':main()
