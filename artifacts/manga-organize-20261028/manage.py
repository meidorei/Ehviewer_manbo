"""Small local progress and candidate index helper. Titles are read with skill pagination."""
import importlib.util
import json
from pathlib import Path
import sys

RUN = Path(__file__).resolve().parent
ROOT = RUN.parents[1]
OLD = ROOT / 'artifacts/manga-order-fix-20261011'
spec = importlib.util.spec_from_file_location('organize', ROOT / '.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py')
o = importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)

def save(name, data):
    (RUN/name).write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

if sys.argv[1] == 'seed':
    current = o.read(RUN/'catalog/catalog.json')
    previous = o.read(OLD/'catalog/catalog.json')
    by = {r['gid']:r for r in current['items']}
    old = {r['gid']:r for r in previous['items']}
    delta = [r for r in current['items'] if r['gid'] not in old or any(r[k]!=old[r['gid']][k] for k in ('title','titleJpn'))]
    changed = {r['gid'] for r in delta if r['gid'] in old}
    groups = o.read(OLD/'groups.json')
    for group in groups['series']:
        group['members'] = [m for m in group['members'] if (m if isinstance(m,int) else m['gid']) in by and (m if isinstance(m,int) else m['gid']) not in changed]
    groups['series'] = [g for g in groups['series'] if g['members']]
    save('groups.json',groups)
    save('delta.json',{'items':delta})
    summary = dict(total=len(by),previous=len(old),added=len(set(by)-set(old)),removed=len(set(old)-set(by)),retitled=len(changed),delta=len(delta))
    save('delta-summary.json',summary)
    save('progress.json',dict(catalog='catalog/catalog.json',previousCatalog='../manga-order-fix-20261011/catalog/catalog.json',query='',nextOffset=40,readDeltaRanges=[[0,40]],readDeltaGids=[r['gid'] for r in delta[:40]],relatedRead=[],status='reading_delta',phoneWritten=False,inheritedJudgments='Reuse completed 2026-10-11 judgments only for unchanged title/titleJpn and existing GIDs',questions=[]))
    print(json.dumps(summary))
elif sys.argv[1] == 'related':
    # Exact literal terms selected by the agent; no automated series assignments.
    terms = json.loads(sys.argv[2])
    dest = sys.argv[3]
    catalog = o.read(RUN/'catalog/catalog.json')
    delta = {r['gid'] for r in o.read(RUN/'delta.json')['items']}
    selected=[]
    counts={t:0 for t in terms}
    for r in catalog['items']:
        text=' '.join(str(r.get(k) or '') for k in ('title','titleJpn')).casefold()
        hits=[t for t in terms if t.casefold() in text]
        for t in hits: counts[t]+=1
        if hits and r['gid'] not in delta: selected.append(r)
    save(dest,{'items':selected})
    print(json.dumps({'candidateCount':len(selected),'counts':counts},ensure_ascii=False))
elif sys.argv[1] == 'batch':
    data = json.loads(sys.argv[2])
    groups = o.read(RUN/'groups.json')
    for change in data.get('groups',[]):
        target = change.get('base')
        match = [g for g in groups['series'] if any((m if isinstance(m,int) else m['gid']) == target for m in g['members'])] if target else []
        assert len(match) <= 1
        if match:
            group = match[0]
            for key in ('name','note'):
                if key in change: group[key] = change[key]
        else:
            group = {'name':change['name'],'note':change['note'],'members':[]}
            groups['series'].append(group)
        for m in change['members']:
            gid = m if isinstance(m,int) else m['gid']
            existing = [i for i,v in enumerate(group['members']) if (v if isinstance(v,int) else v['gid']) == gid]
            assert len(existing) <= 1
            if existing: group['members'][existing[0]] = m
            else: group['members'].append(m)
    save('groups.json',groups)
    progress = o.read(RUN/'progress.json')
    if 'range' in data:
        start,end = data['range']
        if [start,end] not in progress['readDeltaRanges']: progress['readDeltaRanges'].append([start,end])
        delta = o.read(RUN/'delta.json')['items']
        progress['readDeltaGids'] = list(dict.fromkeys(progress['readDeltaGids'] + [r['gid'] for r in delta[start:end]]))
        progress['nextOffset'] = end if end < len(delta) else None
    progress['relatedRead'] += data.get('relatedRead',[])
    progress['questions'] += data.get('questions',[])
    save('progress.json',progress)
    decisions = o.read(RUN/'decisions.json') if (RUN/'decisions.json').exists() else []
    decisions.append(data)
    save('decisions.json',decisions)
    print(json.dumps({'savedGroups':len(groups['series']),'deltaRead':len(progress['readDeltaGids']),'nextOffset':progress['nextOffset']},ensure_ascii=False))
