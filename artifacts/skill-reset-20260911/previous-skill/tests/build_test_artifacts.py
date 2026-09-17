#!/usr/bin/env python3
"""Build synthetic browser fixtures and recall evaluation without using a real library."""
import argparse
import subprocess
from fixture_support import *
from generate_review_page import generate
from evaluate import evaluate


def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True,type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    for n,name in ((12,'review'),(3184,'large-review')):
        catalog=test_catalog(n);rows=[test_row(x,'series:'+str((x['gid']-1)//4+1),chapter=(x['gid']-1)%4) for x in catalog['items']]
        if n==12:
            catalog['items'][0]['title']='星の旅 第0話';catalog['items'][1]['title']='Journey of Stars Chapter 1';catalog=make_catalog(catalog['items']);rows[2].update(category='extra',orderReliable=False,orderConfidence=0,position=None)
        model=test_model(catalog,rows,mode="lightweight")
        write_json(a.output/(name+'-catalog.json'),catalog);write_json(a.output/(name+'-model.json'),model)
        (a.output/(name+'.html')).write_text(generate(catalog,model,sha256_file(a.output/(name+'-model.json'))),encoding='utf-8')
    gold=read_json(Path(__file__).parent/'fixtures/multilingual-gold.json')
    catalog=make_catalog([dict(gid=x['gid'],originalPosition=i,title=x['title'],titleJpn=None) for i,x in enumerate(gold['items'],1)])
    annotations=[dict(gid=x['gid'],authorAliases=[],workAliases=[],semanticTitleKey=x['aliasKey'],partKind='unknown',partNumber=None,confidence=.8,needsHumanReview=True) for x in gold['items']]
    write_json(a.output/'eval-catalog.json',catalog);write_json(a.output/'eval-discovery.json',dict(formatVersion=1,snapshotFingerprint=catalog['snapshotFingerprint'],metadataFingerprint=catalog['metadataFingerprint'],annotations=annotations))
    scripts=Path(__file__).resolve().parents[1]/'scripts'
    subprocess.run([sys.executable,str(scripts/'generate_candidates.py'),'--catalog',str(a.output/'eval-catalog.json'),'--discovery',str(a.output/'eval-discovery.json'),'--candidates',str(a.output/'eval-candidates.json'),'--author-buckets',str(a.output/'eval-authors.json'),'--unresolved',str(a.output/'eval-unresolved.json')],check=True)
    write_json(a.output/'synthetic-recall-report.json',evaluate(gold,read_json(a.output/'eval-candidates.json')))
if __name__=='__main__':main()
