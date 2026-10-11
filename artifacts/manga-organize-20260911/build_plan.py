import json,re,importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('organize',ROOT.parent.parent/'.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py')
o=importlib.util.module_from_spec(spec);spec.loader.exec_module(o)
cat=o.read(ROOT/'catalog/catalog.json'); items=cat['items']
groups={};positions={}; assigned={}
for line in (ROOT/'groups-notes.txt').read_text('utf-8-sig').splitlines():
 if not line.strip():continue
 name,tokens=line.split('|',1)
 g=groups.setdefault(name,dict(name=name,note='依据本库作者/社团、主体题名、译名与篇章标记归组；保留全部版本。',members=[]))
 for token in tokens.split(','):
  head,*parts=token.split('/')
  p,*order=head.split('@');p=int(p)
  assert p not in assigned,(p,assigned.get(p),name)
  assigned[p]=name;m={'gid':items[p-1]['gid']}
  if order:m['order']=float(order[0]);m['order']=int(m['order']) if m['order'].is_integer() else m['order']
  if parts:m['category']=parts[0]
  if len(parts)>1:m['branch']=parts[1]
  g['members'].append(m)
# Keep unnumbered titles unnumbered. Curated member sequences retain their intended
# placement; numerical chapters within a branch are sorted without treating dates,
# IDs, season labels or issue years as chapter numbers.
for name,g in groups.items():
 branch_keys=[]
 for m in g['members']:
  key=(m.get('category','main'),m.get('branch','main'))
  if key not in branch_keys:branch_keys.append(key)
 branch_keys.sort(key=lambda k:o.KINDS.index(k[0]))
 ordered=[]
 for key in branch_keys:
  ms=[m for m in g['members'] if (m.get('category','main'),m.get('branch','main'))==key]
  numbered=iter(sorted([m for m in ms if 'order'in m],key=lambda m:m['order']))
  ordered.extend(next(numbered) if 'order'in m else m for m in ms)
 g['members']=ordered
(ROOT/'groups.json').write_text(json.dumps({'series':list(groups.values())},ensure_ascii=False,indent=2)+'\n','utf-8')
print(json.dumps({'groups':len(groups),'groupedBooks':len(assigned),'independentBooks':len(items)-len(assigned)},ensure_ascii=False))
print('JPN checks:')
for p in [160,320,321,362,449,450,595,613,722,723,874,875,1030,1142,1144,1216,1217,1236,1237,1349,1350,1399,1401,1420,1435,1459,1476,1477,1482,1483,1484,1520,1696,1697,1729,1739,1937,1990,2062,2082,2083,2115,2138,2196,2278,2404,2425,2523,2647,2735,2803,2856,2897,2935,2957,2977,2981,3054]:
 x=items[p-1];print(str(p)+'|'+str(x.get('titleJpn')))
# Correct the few ambiguous relationships after checking original Japanese fields.
for pos in (1697,1144,2083):
 for g in groups.values():g['members']=[m for m in g['members'] if m['gid']!=items[pos-1]['gid']]
# Put the alternate unnumbered base next to the other base, before its sequel.
g=groups['魔妈妈狩猎（MAFIC）'];lookup={m['gid']:m for m in g['members']};g['members']=[lookup[items[p-1]['gid']] for p in (20,628,21)]
# Publication dates stay in the branch label and never become chapter numbers.
for name in ('COMIC BAVEL','COMIC快楽天'):
 groups[name]['members'].sort(key=lambda m:m.get('branch',''))
# Add known explicit coverage without fabricating chapter numbers for unnumbered books.
ends={85:3,86:5,87:7,130:2,185:4,186:6,248:2.1,249:2.2,328:2,334:10,335:13,336:4,362:4,340:None,368:3,369:4,405:4,557:3,558:6,679:3,710:9,711:10,726:None,762:35,801:3,824:6,825:9,1005:6,1006:5,1029:5,1503:10,1559:2,1572:9,1573:15,1582:2,1583:5,1597:4,1598:4,1625:6,1779:3,1846:8,1853:2,1854:2,2082:3,2158:8,2159:2,2278:6,2401:3,2402:7,2403:8,2404:4,2565:2,2566:4,2572:2,2762:14,2763:16,2823:4,2881:2,2882:6,2913:2}
bygid={x['gid']:x for x in items}
for g in groups.values():
 for m in g['members']:
  p=bygid[m['gid']]['originalPosition']
  if ends.get(p) is not None and 'order'in m:m['end']=ends[p]
 # Use the actual source spelling for author names, avoiding guessed transcriptions.
 if re.search(r'（[^（）]+）$',g['name']):
  author=None
  for field in ('titleJpn','title'):
   for m in g['members']:
    title=bygid[m['gid']].get(field) or ''
    match=re.search(r'\[([^]]+)\]',title)
    if not match:continue
    a=match.group(1)
    if re.search(r'汉化|漢化|翻译|翻訳|Chinese|3D|MTL|AI|机翻|機翻|Pixiv|Fanbox|Full Color',a,re.I):continue
    inner=re.search(r'\(([^()]+)\)',a);author=inner.group(1) if inner else a
    break
   if author:break
  if author:g['name']=re.sub(r'（[^（）]+）$','（'+author+'）',g['name'])
  if g['name'].startswith('小垒与'):g['name']=g['name'].replace('小垒与','Rui与')
# Specific notes explain decisions and genuine unresolved questions.
for key,note in {
'魔导士不向催眠屈服（白猫屋）':'同作者的「魔導士は催眠術には屈しない」题名群；不同属性角色分支分别保留，不编造跨分支章号。',
'JK退魔部（煌野一人）':'Season4、Season6及单行本分开。S6条目罗马音写4，但日文⑤和中文篇5一致，本次按5标注并保留原题冲突。',
'对儿子同学的枕营业（アルマロッソ）':'第4至6篇条目还收录另一作品《少子化を解決する法律ができた結果…1》；保留为整本混合合集，不拆分或删减。',
'罪恶都市（泰隆是信仰）':'仅归合同一署名的标题，监狱、白兰、丧尸外传及重置版分开；其他署名未视为同一作者。',
'EMPIRE HARD CORE（TYPE.90）':'同名出版系列，不同年份与原作IP分支分开；年份不作为章号。',
'身体奖励福利部门（C級）':'连载第1至4篇按编号排列，FANZA限定特装版保留为单本合集。',
'八里木岛（霧島鮎）':'作者一致，标题清单中的八里木岛/Oideyo与单行本副题相对应；章节包与单行本分别保留。',
}.items():groups[key]['note']=note
final_groups={'series':list(groups.values())}
(ROOT/'groups.json').write_text(json.dumps(final_groups,ensure_ascii=False,indent=2)+'\n','utf8')
plan=o.build(cat,final_groups)
rank={m['gid']:j for g in final_groups['series'] for j,m in enumerate(g['members'])}
# Use the explicitly curated member sequence to position unnumbered base works;
# this changes only presentation order, leaving order=null for unnumbered works.
segments=[]
for row in plan['items']:
 if not segments or segments[-1][0]['seriesId']!=row['seriesId']:segments.append([])
 segments[-1].append(row)
plan['items']=[row for segment in segments for row in sorted(segment,key=lambda r:rank.get(r['gid'],0))]
uncertain={76:'同名的罪恶都市还有泰隆是信仰、wushiwushi、LGMarlboro等署名；没有确认作者别名及改编关系，独立保留。',89:'无作者署名的罪恶都市条目，不能确认与其他作者分组的关系。',90:'疑似与罪恶都市赵老师重置篇有关，但本条无明确作者署名，独立保留。',284:'罪恶都市丧失小镇与丧尸外传可能有关；题名与署名证据不足，独立保留。',1144:'与同作者温泉作品可能有收录关系，但题名不完全一致且无明确收录证据，独立保留。',1697:'与同作者「お母さんにはこれぐらいしか出来ないから」措辞相近，但日文题名不同，独立保留。',2083:'本标题缺少「邻居」一词，无法确认是否为同作者邻居妈妈系列，独立保留。',449:'原题疑似拼接了两部作品名称，未与学园王子或其他作品合并。',962:'与同作者爸爸活男娘作品措辞相近，暂无明确续篇证据，独立保留。',963:'可能与同作者恩爱男娘题名有关，但不凭题材相同归组。',2877:'与同作者金发黑辣妹母亲AV篇可能关联，缺少明确续篇标记，独立保留。',3050:'与同作者金发黑辣妹母亲应召篇可能关联，缺少明确续篇标记，独立保留。',1682:'题名仅为「我的教师妈妈」，没有作者和篇章信息，未并入千穗理系列。',372:'作者图库按原样独立保留；日期不作为作品章号。',374:'作者图库按原样独立保留；日期不作为作品章号。',2801:'作者图库按原样独立保留；日期不作为作品章号。'}
for row in plan['items']:
 p=row['originalPosition']
 if p in uncertain:row['note']=uncertain[p]
plan['gidOrder']=[r['gid']for r in plan['items']];o.validate_plan(cat,plan)
preview=ROOT/'preview';preview.mkdir(exist_ok=True)
for target,value in [(preview/'order.json',json.dumps(plan,ensure_ascii=False,indent=2)+'\n'),(preview/'review.html',o.review_html(plan))]:
 with target.open('x',encoding='utf8')as f:f.write(value)
counts={'itemCount':len(items),'groupCount':len(groups),'groupedItemCount':sum(len(g['members'])for g in groups.values()),'independentItemCount':sum(r['seriesId'].startswith('item:')for r in plan['items']),'movedItemCount':sum(i!=r['originalPosition']for i,r in enumerate(plan['items'],1)),'allGidsPreserved':True,'allOriginalTitlesPreserved':True,'confirmed':False,'phoneWriteback':False,'uncertainPositions':list(uncertain)}
(ROOT/'validation.json').write_text(json.dumps(counts,ensure_ascii=False,indent=2)+'\n','utf8')
lines=['# 漫画库整理结果','',f"范围：com.ehviewer.manbo.debug。共 {len(items)} 本，{len(groups)} 个归组，{counts['groupedItemCount']} 本归入系列，其余 {counts['independentItemCount']} 本独立保留。",'', '已逐批读取全部原标题。仅对具体疑点回看日文题名。所有 GID、原标题及下载均保留，系列锚定原最早位置。原题未编号的条目保留空编号；其阅读位置按照人工清单安排。','', '当前未写回手机；没有生成经用户确认的排序数据库。电脑已保存含日志的原库归档及完整性通过的一致性快照。','', '## 待确认关系','']
for p,note in uncertain.items():lines.append(f'- GID {items[p-1]["gid"]}：{note}')
lines+=['','## 系列清单','']
for g in sorted(groups.values(),key=lambda g:min(bygid[m['gid']]['originalPosition']for m in g['members'])):lines.append('- '+g['name']+f"：{len(g['members'])} 本。GID "+', '.join(str(m['gid'])for m in g['members']))
(ROOT/'整理说明.md').write_text('\n'.join(lines)+'\n','utf8')
print('FINAL',json.dumps(counts,ensure_ascii=False))
