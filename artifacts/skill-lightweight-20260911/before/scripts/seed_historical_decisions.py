#!/usr/bin/env python3
"""Generic, review-only history reuse; no library-specific identifiers."""
import argparse
import copy
from pathlib import Path
from common import read_json, write_json, validate_catalog, validate_binding, singleton, envelope, digest

def build(current, history_catalog, history_decisions):
    validate_catalog(current);validate_binding(history_catalog,history_decisions)
    old_items={x['gid']:x for x in history_catalog['items']};old_rows={x['gid']:x for x in history_decisions['decisions']}
    rows=[];reused=changed=new=0
    for item in current['items']:
        gid=item['gid'];old=old_items.get(gid)
        if old and all(old.get(k)==item.get(k) for k in ('title','titleJpn')):
            row=copy.deepcopy(old_rows[gid]);reused+=1
            row['needsHumanReview']=True
            row['evidence'].append({'kind':'history','claim':'同 GID 标题元数据未变，复用结论并重新审核。','sourceDigest':digest(history_decisions)})
        else:
            row=singleton(item)
            if old:changed+=1
            else:new+=1
        rows.append(row)
    result=envelope(current,rows)
    result['historyReuse']=dict(reusedUnchangedGids=reused,changedMetadataGids=changed,newGids=new)
    return result

def main():
    p=argparse.ArgumentParser(description='Seed an arbitrary v3 catalog from matching history, always pending review.')
    for arg in ('catalog','history-catalog','history-decisions','output'):p.add_argument('--'+arg,required=True,type=Path)
    a=p.parse_args();write_json(a.output,build(read_json(a.catalog),read_json(a.history_catalog),read_json(a.history_decisions)))
if __name__=='__main__':main()
