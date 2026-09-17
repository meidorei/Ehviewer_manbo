"""Synthetic fixtures only. Audit records here are test data, not real model evaluations."""
import sys
import copy
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from common import *
from audit_round import record


def test_catalog(n=4):
    return make_catalog([dict(gid=i,originalPosition=i,title=f'[Artist] Work Chapter {i}',titleJpn=None) for i in range(1,n+1)])


def test_row(item,sid='series:one',chapter=None,category='main',branch='main'):
    row=singleton(item)
    row.update(canonicalSeriesId=sid,canonicalSeriesTitle=sid,category=category,branch=branch,
               confidence=.95,reason='Synthetic fixture: explicitly assigned relationship.')
    if chapter is not None:
        row.update(position=dict(volume=None,chapter=chapter,part=None,rangeEnd=None),orderReliable=True,orderConfidence=1)
    return row


def test_model(catalog,rows=None,mode="deep"):
    rows=copy.deepcopy(rows) if rows is not None else [test_row(x,chapter=x['originalPosition']) for x in catalog['items']]
    source=envelope(catalog,rows)
    if mode == 'lightweight':source['auditMode']=mode
    for name in (('semantic',) if mode == 'lightweight' else ROUNDS):
        hash_=digest(source)
        result={**envelope(catalog,copy.deepcopy(source['decisions'])), 'reviewedGids':[x['gid'] for x in catalog['items']],
                'conflicts':[],'summary':'Synthetic test fixture, no real-library semantic claim.','inputArtifactDigest':hash_}
        source=record(catalog,source,result,name,hash_)
    return {**source,**stable_order(catalog,rows),'reviewStatus':'model-reviewed'}


def confirmed_export(catalog,model,model_hash,operations=None):
    ops=list(operations or [])+[dict(type='acknowledge',gids=[x['gid'] for x in catalog['items']])]
    return {**envelope(catalog,[],copy.deepcopy(model['auditTrail'])),**replay(catalog,model,ops),
            'auditMode':audit_mode(model),'baseModelDigest':model_hash,'operations':ops,'reviewStatus':'confirmed'}
