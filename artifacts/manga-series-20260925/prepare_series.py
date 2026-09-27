import pathlib,json,importlib.util,collections
r=pathlib.Path('artifacts/manga-series-20260925'); prev=pathlib.Path('artifacts/manga-fresh-20260917-164332')
spec=importlib.util.spec_from_file_location('organize', '.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
cat=mod.read(r/'catalog/catalog.json'); lookup={x['gid']:x for x in cat['items']}; groups=mod.read(prev/'groups.json'); gid=lambda m:m if isinstance(m,int) else m['gid']
for s in groups['series']:s['members']=[m for m in s['members'] if gid(m) in lookup]
groups['series']=[s for s in groups['series'] if len(s['members'])>1]
def add(name,members,note):groups['series'].append(dict(name=name,members=members,note=note))
add('朋友的妈妈性欲实在太强了（Kirintei / Kirin Kakeru）',[4195179,4194320],'作者、社团及主标题完全一致，两个 GID 均保留为同作版本。')
add('不受欢迎的我怎么可能拒绝得了这种诱惑啊（Yurutto Pocket / Untue）',[{'gid':3988614,'order':2},{'gid':4205681,'order':3}],'同作者罗马音、中文主体题名及连续编号一致；现有库只有第2、3篇，不补造第1篇。')
add('40岁处男变成魔法师这件事（Kokusan JK / Velzhe）',[{'gid':4202833,'note':'同题未编号前作，排在明确第2篇之前；章号留空。'},{'gid':4208132,'order':2}],'社团、作者与中日罗马音题名一致，明确第2篇对应未编号前作；C92/C97 为展会编号。')
add('巨乳妈妈在我面前（Melon no Hoshiboshi / Hoshiduki Melon）',[{'gid':4200709,'order':1},{'gid':4200711,'order':2},{'gid':3326157,'order':2}],'第一条中文明确标1，另两条主标题与日文对应第2篇；第2篇不同版本相邻，全部保留。')
add('危险地区禁止进入（Seiheki Master）',[{'gid':4195051,'order':1,'note':'题名 Zenpen，前篇。'},{'gid':4195111,'order':2,'note':'题名 Kouhen，后篇。'}],'同作者、完整主体题名相同，Zenpen/Kouhen 为明确前后篇，此处顺序1/2表示前后篇而非出版卷号。')
add('Sex Appli Yankee Onna Harami Ochi（KAZAMA DoJo / Mucc）',[4191191,3804835],'作者、社团和罗马音题名一致，翻译及修正版本不同；并列保留。')
add('Okazu Gyaru Harem（Toneri Dan）',[4189274,3926496],'完整罗马音主体题名一致，中文译名和汉化组不同；新条目附日文名互证。')
s=next(s for s in groups['series'] if any(gid(m)==3949082 for m in s['members']));s['name']='污秽之星（Vpan’s EXTASY / Satou Kuuki）';s['members'].append({'gid':4204754,'branch':'黑篇'});s['note']='同作者 Kegareboshi 题名系列，黑、紫、赤分别保留分支；颜色不是章节数字，各分支按本次原位置排列。'
s=next(s for s in groups['series'] if any(gid(m)==3862291 for m in s['members']));s['name']='被催眠俘获的雌兔妈妈（ikuu）';s['members'].append({'gid':4200741,'category':'extra','branch':'番外','order':1});s['note']='同作者同系列；原有1–4、1–6为合集，新增条目明确番外1，不删除重叠收录版本。'
for m in s['members']:
 if gid(m)==3862291:m['end']=4
 if gid(m)==4086672:m['end']=6
mod.write(r/'groups.json',groups)
plan=mod.build(cat,groups)
# The generic sorter places unnumbered works after numbered works. Preserve the explicit prequel relation without inventing chapter 1.
rows=plan['items']; base=next(x for x in rows if x['gid']==4202833);rows.remove(base); rows.insert(next(i for i,x in enumerate(rows) if x['gid']==4208132),base);plan['gidOrder']=[x['gid'] for x in rows];mod.validate_plan(cat,plan);mod.write(r/'order.json',plan)
unc=mod.read(prev/'uncertain.json');unc=[dict(x,gids=[g for g in x['gids'] if g in lookup]) for x in unc];unc=[x for x in unc if len(x['gids'])>1]
unc.extend([{'gids':[4123647,3913426],'note':'日文署名与 Acesulfame Kei 可对应，但仅题材相近，不能确认同作，独立保留。'},{'gids':[4197323],'note':'新增条目没有作者字段，未找到可核对的同题记录，独立保留。'},{'gids':[3621404,4198026],'note':'同作者且涉及同人物，但 SUGYAAA 与 SOFS 题名不同，没有明确续篇证据，独立保留。'}]);mod.write(r/'uncertain.json',unc)
delta=mod.read(r/'delta.json'); membership={gid(m):s['name'] for s in groups['series'] for m in s['members']}; count=sum(len(s['members']) for s in groups['series'])
report={'itemCount':len(lookup),'seriesCount':len(groups['series']),'groupedItemCount':count,'independentItemCount':len(lookup)-count,'newItemCount':len(delta['added']),'absentSincePriorSnapshot':len(delta['removed']),'changedTitleCount':len(delta['changed']),'addedSeriesCount':7,'extendedSeriesCount':2,'newItemsGrouped':sum(x['gid'] in membership for x in delta['added']),'priorUnchangedItemsReused':len(lookup)-len(delta['added']),'uncertainCount':len(unc),'allGidsAndTitlesPreserved':True,'sqliteIntegrity':'ok','phoneWriteback':False,'webGenerated':False,'confirmed':False}
mod.write(r/'report.json',report)
notes=['# 漫画系列整理（2026-09-25）','',f"最新 Debug 书库共 {len(lookup)} 条；{len(groups['series'])} 个多条目系列，共 {count} 条；其余 {len(lookup)-count} 条独立保留。",'','续用9月17日已整理且与当时确认结果一致的判断；本次逐条核对36条新增，并回看对应作者及跨语言题名候选。旧快照24条已不在当前手机书库，本次没有删除任何记录或下载。','', '只输出数据；网页生成暂缓，未写回手机、未修改应用或技能、未构建安装APK。','', '## 后续网页接入','', '- catalog/catalog.json：本次完整标题与原位置。','- groups.json：系列与章节标记。','- order.json：已核对完整顺序，confirmed=false。','- uncertain.json：保留的疑点。','', '**顺序注意**：未编号前作 GID 4202833 应在明确第2篇 GID 4208132 前；没有捏造第1章。当前技能通用 build 把空编号排后，因此 order.json 已按这一明确关系调整。后续网页应直接读取此 order.json；若从 groups.json 重建，需同样保留该关系。','', '## 本次新增条目判断','']
for x in delta['added']: notes.append(f"- GID {x['gid']}："+('归入「'+membership[x['gid']]+'」。' if x['gid'] in membership else '独立保留；未取得同作或续篇的明确题名证据。'))
notes+=['','## 系列清单','']
for s in sorted(groups['series'],key=lambda s:min(lookup[gid(m)]['originalPosition'] for m in s['members'])):notes.append(f"- {s['name']}：{len(s['members'])} 条。")
notes+=['','## 验证与限制','', 'SQLite 一致性副本完整性通过；全量 GID 唯一且齐全、原标题不变；完整顺序通过技能校验。旧判断沿用，不将其表述为本次重新逐本审核。网页及手机写回均未执行。','',f"保留 {len(unc)} 项不确定关系，详情见 uncertain.json。"]
(r/'整理说明.md').write_text('\n'.join(notes)+'\n',encoding='utf-8'); print(json.dumps(report,ensure_ascii=False,indent=2))
