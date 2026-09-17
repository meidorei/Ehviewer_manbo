#!/usr/bin/env python3
"""Inspect pairwise recall/grouping/order against an explicitly labeled evaluation set."""
import argparse
from itertools import combinations
from pathlib import Path
from common import read_json,write_json,require,integer


def evaluate(gold,candidates=None,order=None):
    items=gold['items'];gids={x['gid'] for x in items}
    require(len(gids)==len(items) and all(integer(g) for g in gids),'invalid evaluation GIDs')
    truth={x['gid']:x['expectedSeriesId'] for x in items}
    positive={tuple(sorted((a,b))) for a,b in combinations(gids,2) if truth[a]==truth[b]}
    report={'fixtureProvenance':gold.get('provenance'),'itemCount':len(items),'trueSameSeriesPairs':len(positive)}
    if candidates is not None:
        components=[set(row.get('componentGids',[x['gid'] for x in row['items']])) for row in candidates['candidates']]
        retrieved=sum(any(a in c and b in c for c in components) for a,b in positive)
        report.update(candidateRetrievedPairs=retrieved,candidateMissedPairs=len(positive)-retrieved,
                      candidatePairRecall=retrieved/len(positive) if positive else None)
    if order is not None:
        predicted={x['gid']:x['canonicalSeriesId'] for x in order['decisions']}
        require(set(predicted)==gids and len(predicted)==len(order['decisions']),'predicted GIDs must exactly cover evaluation set')
        pairs={tuple(sorted((a,b))) for a,b in combinations(gids,2) if predicted[a]==predicted[b]}
        correct=len(positive & pairs)
        gid_order=order['gidOrder'];require(len(gid_order)==len(gids) and set(gid_order)==gids,'invalid predicted order')
        positions={gid:i for i,gid in enumerate(gid_order)}
        ordered=gold.get('orderedPairs',[])
        require(all(len(p)==2 and all(g in gids for g in p) for p in ordered),'invalid gold orderedPairs')
        failures=[pair for pair in ordered if positions[pair[0]]>=positions[pair[1]]]
        report.update(falseMergePairs=len(pairs-positive),missedSeriesPairs=len(positive-pairs),
                      seriesPairPrecision=correct/len(pairs) if pairs else None,
                      seriesPairRecall=correct/len(positive) if positive else None,
                      orderingPairCount=len(ordered),orderingErrorCount=len(failures),orderingErrors=failures)
    require(candidates is not None or order is not None,'supply candidates or order to evaluate')
    return report


def main():
    p=argparse.ArgumentParser(description='Evaluate candidate recall, false merges, missing series members and pairwise ordering separately.')
    p.add_argument('--gold',required=True,type=Path);p.add_argument('--candidates',type=Path);p.add_argument('--order',type=Path);p.add_argument('--report',required=True,type=Path)
    a=p.parse_args();result=evaluate(read_json(a.gold),read_json(a.candidates) if a.candidates else None,read_json(a.order) if a.order else None);write_json(a.report,result)
    print(__import__('json').dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
