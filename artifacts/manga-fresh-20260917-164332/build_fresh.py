from pathlib import Path
import importlib.util,json,re,collections,hashlib,sqlite3
P=Path(__file__).resolve().parent
root=P.parents[1]
spec=importlib.util.spec_from_file_location('organize',root/'.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py')
o=importlib.util.module_from_spec(spec);spec.loader.exec_module(o)
a=json.loads((P/'reading.json').read_text('utf-8')); catalog=o.read(P/'catalog/catalog.json')
groups=[]; assigned={}
for line in (P/'decisions.txt').read_text('utf-8').splitlines():
    parts=line.split(); members=[]
    for t in parts:
        m=re.fullmatch(r'\+?(\d+)([cer])?(?::([\d.]+))?(?:@(.+))?',t)
        assert m,t
        n=int(m[1]);assert 1<=n<=len(a)
        member={'gid':a[n-1]['gid'],'_n':n}
        if m[2]:member['category']={'c':'collection','e':'extra','r':'remaster'}[m[2]]
        if m[3]:member['order']=float(m[3])
        if m[4]:member['branch']=m[4]
        members.append(member)
    old={assigned[m['_n']] for m in members if m['_n'] in assigned}
    if len(old)>1:raise ValueError(('conflicting joins',line))
    if old:g=old.pop()
    else:g=len(groups);groups.append([])
    for m in members:
        if m['_n'] not in assigned:groups[g].append(m);assigned[m['_n']]=g

def label(n):
    t=a[n-1]['title'] or a[n-1]['titleJpn'] or str(a[n-1]['gid'])
    t=re.sub(r'^\([^)]*\)\s*','',t)
    m=re.match(r'\[([^]]+)\]\s*',t)
    author=m[1] if m else ''
    if m:t=t[m.end():]
    t=re.sub(r'\[[^]]*\]|【[^】]*】','',t).strip()
    halves=re.split(r'\s*[|｜]\s*',t)
    body=halves[1] if len(halves)>1 and re.search(r'[\u4e00-\u9fff]',halves[1]) else halves[0]
    body=re.sub(r'\s*\([^)]*\)\s*',' ',body).strip()
    body=re.sub(r'\s*(?:Ch(?:apter)?\.?|Vol\.?)\s*[\d.]+.*$','',body,flags=re.I)
    body=re.sub(r'\s*(?:\d+(?:\.\d+)?|[IVX]+)\s*$','',body).strip()
    body=re.sub(r'\s+',' ',body)
    return (body or '同题版本')+('（'+author+'）' if author else '')

notes={
 573:'Darkmaya 与社团 Anmokan／闇夢館署名互证；同题续篇及第4篇不同版本。',
 1171:'Kaze no Koe 为社团内作者；英文题名与原日文含义对应，作为同作版本相邻保留。',
 1147:'英文译名与罗马音题名含义对应，作者与社团一致，第2篇独立编号。',
 2567:'英文题名与罗马音／日文第二话对应，作者一致。',
 715:'Season 分开保留；第6季该条罗马音写4，中文与日文写5，存在冲突，章号留空。',
 2285:'同作者、同一主体题名的魔导师分支；各角色分支分开，不强行编卷号。',
 581:'第4部所附副标题内的数字另属子篇编号，不混用为全系列章号。',
 3129:'同作者的千穗理系列，中英别名一致；第5篇两个GID都保留，合集不拆分。',
 3037:'中文系列题名一致、部分条目未署名；保留全部版本，作者身份未额外核实。',
 3009:'唯一主体题名对应，部分记录没有作者；合集范围重叠也全部保留，不删除下载。',
 1362:'LAPUTA／Laputa 与中文题名对应；夺母和纯爱分支分开，缺卷不补造。',
 3111:'同作者中文系列；监狱、白兰、丧尸外传及重制版分开，其他作者同名作品不并入。',
 158:'甘噛本舗／Amagami Honpo 与まんの／Manno 对应；第4、4.5与1–4合集均保留。',
 870:'Hakutamayu／白玉湯为同一署名；总序与主题篇编号体系不同，主题篇不混排成正篇序号。',
 2618:'同一主体题名的角色篇章；第二篇依题名标2，不把角色变化判为异作者作品。',
 65:'',
}
uncertain=[
 ([55,56],'同作者题名相近，但后者增加独立标题词，缺少可靠续篇依据，分开保留。'),
 ([108,109,110],'同作者同原作的合集，实际收录范围不明，分别保留。'),
 ([212,416],'整体合集与角色篇可能重叠，未取得目录对应，不并组。'),
 ([240,241,1213],'作者别名可能对应，但主标题不同；仅合并完全对应的两个版本。'),
 ([570,571],'短篇标注收录于整本，不把整本其他故事强制并为同一系列。'),
 ([565,566],'同年度刊物品牌、不同原作故事，不仅凭固定前缀合并。'),
 ([736,737],'同作者的两种题名可能关联，缺少明确同作证据，分开保留。'),
 ([964,965],'题名可能描述后续，但未明确标续篇且缺少目录证据，分开保留。'),
 ([987,988],'第二条题名缺少邻居等限定，可能是缩写也可能是另一故事，撤回合并。'),
 ([1188,1189],'同作者、中文内容近似，罗马音题名不足，分开保留。'),
 ([1280,1281],'短篇与单行本可能关联，没有目录证据，分开保留。'),
 ([1499,1501,1505,1506],'同作者相似主题不能自动视为同一故事，分开保留。'),
 ([1857,39],'新题名可能与该家族系列有关，但没有明确外传标记，独立保留。'),
 ([8,714,715],'同作者退魔主题可能关联，单行本与季次关系未确定。'),
 ([1882,3172],'中文无署名合集可能是同作；缺少作者证据，暂不并入第17篇。'),
 ([23,3042],'同题的无署名合集与单篇可能对应，作者未明，分开保留。'),
 ([20,1374,1367,2797,3111,3156,3157],'多人使用相同中文系列名；仅在作者和分支可对应时归组。'),
 ([1092,1093],'同作者同原作题名相近，是否直接续篇未能从题名确定。'),
 ([2476,2477,2479],'作者相同且命名模式相似，女主与故事可能不同，分开保留。'),
 ([835,837],'同作者相似人物设定，但题名不同，不据此合并。'),
 ([3173,3174],'缺少作者及收录说明的个人合集，即使同名也独立保留。'),
 ([248,1866],'只有作者合集标识，没有实际作品题名，不能确认两个合集内容关系。'),
]
output=[]
for ms in groups:
    n=ms[0]['_n']
    group={'name':label(n),'note':notes.get(n,'依本次标题核对作者、主体题名与续篇／版本标记归组；未编号不等于第1章。'),'members':[]}
    for m in ms:group['members'].append({k:v for k,v in m.items() if k!='_n'})
    output.append(group)
# Explicit names avoid selecting a sequel number or a translator as the series label.
names={573:'魔女与羔羊（Darkmaya）',715:'退魔部（煌野一人）',494:'COMIC BAVEL',514:'COMIC ExE',516:'COMIC Kairakuten',577:'Dascomi',2997:'退魔士ゆら（Crimson）',2996:'退魔士カグヤ（Crimson）',870:'俺の上京性生活（白玉湯）',1362:'淫母日记（LAPUTA）',3111:'罪恶都市（泰隆是信仰）',1367:'罪恶都市·兵玉（LGMarlboro）',2797:'罪恶都市（wushiwushi）',3129:'千穗理／Chiho Rei（点点滴滴）',2285:'魔导师系列（白猫屋）',158:'入り浸りギャル（甘噛本舗）',2038:'妈妈的玩具（RK-2）',2930:'Hirogaru Sky 系列（EMPRESS CLUB）',3150:'Harem House（Nietzsche／ニーチェ）',2750:'今日子系列（U羅漢）',2820:'Gal no Yari Life Nikki（Yasomono／Yosomono）'}
for g,ms in zip(output,groups):
    if ms[0]['_n'] in names:g['name']=names[ms[0]['_n']]
plan=o.build(catalog,{'series':output})
# Manual relative positioning: preserve blank chapter annotations while placing the base before numbered sequels.
byseries=collections.defaultdict(list)
for r in plan['items']:byseries[r['seriesId']].append(r)
for gi,ms in enumerate(groups,1):
    rows=byseries['series:'+str(gi)];tokenpos={m['gid']:i for i,m in enumerate(ms)}
    branches={}
    for r in rows:branches.setdefault((r['category'],r['branch']),min(tokenpos[x['gid']] for x in rows if (x['category'],x['branch'])==(r['category'],r['branch'])))
    def key(r):
        peers=[x for x in rows if (x['category'],x['branch'])==(r['category'],r['branch'])]
        if r['order'] is not None:rank=r['order']
        else:
            earlier=[x['order'] for x in peers if x['order'] is not None and tokenpos[x['gid']]<tokenpos[r['gid']]]
            later=[x['order'] for x in peers if x['order'] is not None and tokenpos[x['gid']]>tokenpos[r['gid']]]
            rank=(max(earlier)+min(later))/2 if earlier and later else min(later)-.5 if later else max(earlier)+.5 if earlier else 0
        return (o.KINDS.index(r['category']),branches[(r['category'],r['branch'])],rank,r['originalPosition'])
    rows.sort(key=key)
plan['items']=[r for rows in sorted(byseries.values(),key=lambda rs:min(r['originalPosition'] for r in rs)) for r in rows]
# Specific no-number original / continuation relationships use an explicit relative ordering, not invented chapter numbers.
for seq in [[3150,1744],[2090,2092,2091,2095],[2609,2610,2611]]:
    gids=[a[n-1]['gid'] for n in seq]; loc=[i for i,r in enumerate(plan['items']) if r['gid'] in gids]
    selected={r['gid']:r for r in plan['items'] if r['gid'] in gids}
    for pos,gid in zip(loc,gids):plan['items'][pos]=selected[gid]
issues=[]
for ns,note in uncertain:
    gids=[a[n-1]['gid'] for n in ns];issues.append({'gids':gids,'note':note})
    for r in plan['items']:
        if r['gid'] in gids:r['note']+=' 关联疑点：'+note
plan['gidOrder']=[r['gid'] for r in plan['items']]
o.validate_plan(catalog,plan)
preview=P/'preview';preview.mkdir(exist_ok=True)
o.write(P/'groups.json',{'series':output})
o.write(P/'uncertain.json',issues)
o.write(preview/'order.json',plan)
html=o.review_html(plan)
# Fix the template's double-escaped JSON characters only in this output, preserving all original titles exactly.
encoded=json.dumps(plan,ensure_ascii=False).replace('<','\\u003c').replace('>','\\u003e').replace('&','\\u0026')
# A single JSON unicode escape is required, not a literal backslash sequence.
encoded=encoded.replace('\\\\u003c','\\u003c').replace('\\\\u003e','\\u003e').replace('\\\\u0026','\\u0026')
start=html.index('<script id="data" type="application/json">')+len('<script id="data" type="application/json">')
end=html.index('</script>',start)
html=html[:start]+encoded+html[end:]
assert json.loads(html[start:start+len(encoded)])==plan
(preview/'review.html').write_text(html,encoding='utf-8')
count=sum(len(g['members']) for g in output)
report={'source':'fresh USB snapshot','package':'com.ehviewer.manbo.debug','total':len(a),'multiItemGroups':len(groups),'groupedItems':count,'independentItems':len(a)-count,'uncertainSets':len(issues),'allGidsPreserved':True,'allTitlesPreserved':True,'confirmed':False,'phoneDatabaseWritten':False,'previousCacheUsed':False,'catalogHash':plan['catalogHash']}
o.write(P/'report.json',report)
lines=['# 本次从手机重新整理的结果','',f"本次下载记录 {len(a)} 条；归为 {len(groups)} 组，涉及 {count} 条；其余 {len(a)-count} 条独立保留。",'', '未使用以前的导出、分类或缓存；原始数据库和 journal 快照保存在本目录。未写回手机。未构建或安装 APK。','', '## 系列清单','']
for g in output:lines.append('- '+g['name']+'：'+str(len(g['members']))+' 条。')
lines+=['','## 保留的疑点','']
for issue in issues:lines.append('- GID '+', '.join(map(str,issue['gids']))+'：'+issue['note'])
lines+=['','## 使用与后续','', '打开 preview/review.html，可搜索、调整顺序与归组、保存进度。确认整个结果后可导出 confirmed.json。写回前需取得手机最新一致性副本并核对书库有无变化，再按技能生成备份及仅修改 DOWNLOADS.TIME 的排序副本。','', '未联网查证作品页。系列关系来自本次逐批阅读标题和针对性别名核对；程序校验只证明全量保留、原题与结构有效，并不能证明每个作品关系。']
(P/'整理说明.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
