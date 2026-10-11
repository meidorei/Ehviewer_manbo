"""Encode this run's title-based decisions; reuse prior reviewed groups."""
import importlib.util
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = Path(__file__).parent
SKILL = ROOT / '.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega'
spec = importlib.util.spec_from_file_location('organizer', SKILL / 'scripts/organize.py')
organizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(organizer)

def read(path):
    return json.loads(path.read_text('utf-8-sig'))

def write(name, value):
    path = RUN / name
    path.parent.mkdir(parents=True, exist_ok=True)
    assert not path.exists(), path
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

catalog = read(RUN / 'catalog/catalog.json')
by = {x['gid']: x for x in catalog['items']}
old = read(ROOT / 'artifacts/manga-organize-20261003/groups.json')['series']
groups = []
for group in old:
    group['members'] = [dict(gid=m) if isinstance(m, int) else m for m in group['members']]
    group['members'] = [m for m in group['members'] if m['gid'] in by]
    groups.append(group)

def add_to(index, member):
    assert member['gid'] in by
    groups[index]['members'].append(member)

add_to(156, {'gid': 4164633, 'order': 3})
add_to(33, {'gid': 4144640, 'order': 1, 'note': '主标题明确 part 1；日文字段有乱码，采用可读主标题与相同作者、系列名核对。'})
add_to(208, {'gid': 4136469, 'category': 'collection', 'order': 6,
            'note': '同作者《退魔の母》禄／Roku（六）的重制合集；不把合集拆开，也不推定覆盖全部前卷。'})
add_to(328, {'gid': 4017044, 'order': 6})
for m in groups[328]['members']:
    if m['gid'] == 3779091:
        m['branch'] = 'main'
        m['note'] = '主线第4篇，与第5、6篇依编号相邻；Special 保持番外。'
add_to(14, {'gid': 2991582, 'category': 'collection', 'order': 4, 'end': 6,
           'note': '标题明确含同作者《息子の同級生に枕営業物語》4–6，且混收另一作品第1篇；整本保留，不将另一作品的1当成本系列的首篇。'})
groups[64]['name'] = 'PowerHara／Pawahara Onna Joushi to Shachiku-kun（DOLL PLAY / Kurosu Gatari）'
groups[81]['name'] = '打工处的人妻、秋山太太（Fujinoe Honpo / Fujinoe13B）'
groups[117]['name'] = '让人气愤的妹妹必须给她好好训斥一顿（Juna Juna Juice）'
for m in groups[117]['members']:
    if m['gid'] == 3346349:
        m['end'] = 35
    if m['gid'] == 4223833:
        m.update(category='collection', end=40)
for m in groups[331]['members']:
    if m['gid'] == 3331224:
        m['end'] = 2
for m in groups[35]['members']:
    if m['gid'] == 2727275:
        m['order'] = 1
        m['note'] = '日文原标题明确为第1篇；主标题省略1。'

# Find already-reviewed base groups by GID instead of relying on author strings.
taihai = next(g for g in groups if any(m['gid'] == 3978954 for m in g['members']))
taihai['members'].append({'gid': 4199575, 'order': 2})
taihai['name'] = '颓废妻（Kamejirouya-san / Kametaro）'

def new_group(name, members, note):
    groups.append({'name': name, 'members': members, 'note': note})
    return groups[-1]

new_group('完堕ち！残念美人（Tamaki / 環）', [4237595, 4230919],
          '同作者、同日文与罗马音原题的完成／进行中版本；全部保留，彼此相邻，不当成两个章节。')
new_group('Saimin Nurse Call Switch（Hassen）', [{'gid': 3998198, 'order': 1}, {'gid': 4171643, 'order': 2}],
          '相同作者与主体题名，Karte 01／02 为作品内部编号；COMIC Luxuria 卷号不是章号。')
new_group('营业处的裕子小姐／Eigyou-saki no Yuuko-san（Shiono Kou / 汐乃コウ）', [4226964, 3717727],
          '同作者、相同原题，罗马音分词／连字符差异；普通与上色版本相邻保留。')
intro_group = new_group('Inran Shounen（Jairou / Kozi）',
          [{'gid': 4236242, 'category': 'other', 'order': 6,
            'note': '当前下载标注为卷6人物介绍，不能当作卷6完整正篇。'}, {'gid': 3242425, 'category': 'collection'}],
          '相同作者团队和 Inran Shounen 系列标识；有同系列总集篇，但其收录卷次不明。')

flags = []
def flag(group, status, reason):
    label = {'suspected': '疑缺首篇', 'collection': '合集待核', 'numbering': '编号待核'}[status]
    plain_name = group['name']
    group['name'] = '【' + label + '】' + plain_name
    group['note'] = reason + ' ' + group.get('note', '')
    normalized = [dict(gid=m) if isinstance(m, int) else m for m in group['members']]
    for m in normalized:
        if m.get('note'):
            m['note'] = reason + ' ' + m['note']
    group['members'] = normalized
    flags.append({'status': status, 'label': label, 'series': plain_name, 'reason': reason,
                  'members': [{'gid': m['gid'], 'title': by[m['gid']]['title'], 'titleJpn': by[m['gid']]['titleJpn'],
                               'order': m.get('order'), 'category': m.get('category', 'main'), 'branch': m.get('branch', 'main')}
                              for m in normalized]})

gap_groups = [5,14,28,32,39,56,64,69,81,93,104,116,120,127,131,150,153,154,161,172,176,181,193,200,207,
              209,214,219,222,233,242,253,260,264,284,286,291,295,309,327,328,336,339,364,365,371,372,377]
for i in gap_groups:
    reason = '疑缺首篇：当前库有明确后续编号，未见可可靠对应的第1篇／前篇；按题名判断，可能存在尚未识别的译名。'
    if i == 5:
        reason = '疑缺首篇：主线仅见 II；「新VII篇」是分支名，不能当作主线第1篇或第7篇。'
    elif i == 93:
        reason = '疑缺首篇：主线见第19篇；「精液検査編2」另有分支首篇疑缺，母亲篇未编号，不把不同分支互当首篇。'
    elif i == 104:
        reason = '疑缺首篇：见第2、5篇；X 不强行换算成第10篇，也不能据此认定有第1篇。'
    elif i == 371:
        reason = '疑缺首篇（连载分支）：当前连载下载为第六章后篇至第九章前篇；新手护士篇为另一分支，不能补作连载第1章。'
    elif i == 264:
        reason = '疑缺首篇：最早明确编号为1.5，未见1；1.5可能是补篇，不能将它改号成第1篇。'
    elif i == 14:
        reason = '疑缺首篇：见2、3、7及4–6混收合集；合集中的另一作品第1篇不属于这个系列。'
    flag(groups[i], 'suspected', reason)

for i, reason in {
    29: '合集待核：正篇见14，并有总集篇1、2、3；总集篇编号不是正篇章号，未核目录，不能断言首篇缺失。',
    102: '合集待核：正篇见7，并有未注明收录范围的 Collection；可能已有早期章节。',
    109: '合集待核：见第3话与同题未编号整本；未核目录，不能断言缺首篇。',
    128: '编号待核：Request 支线见2–4；暑假篇用年份／字母，OutStory 又是独立分支，编号体系不同。',
    136: '编号待核：改二／改三与李B线可能属于改版／分支，不能仅用2、3推定主线第1篇缺失。',
}.items():
    flag(groups[i], 'numbering' if i in [128,136] else 'collection', reason)
flag(intro_group, 'collection', '合集待核：卷6只标人物介绍，另有同系列总集篇；首卷与完整正篇的覆盖范围无法从题名确定。')

singles = read(RUN / 'gap-single-candidates.json')
extra = read(RUN / 'gap-extra-candidates.json')
language = read(RUN / 'gap-language-candidates.json')
gap_singles = [0,4,5,6,8,10,14,16,17,19,24,27,32,33,35,36,37,38,39,41,44,45,47,48,49,51,55,57,58,61,65,66,71,
               76,82,84,88,90,92,99,101,102,103,107,108,112,116,117,118,120,121,122,123,124,125,127,133,135,
               136,137,139,143,148,149,152,153,156,157,158,159,160,164,166,167,170,173,175,177,178,180,182,
               183,186,187,189,190,191,192,194,197,198,199,200,201,202,203,204,205,209,210,211,213,215,216,
               217,218,219,220,222,223,227,229,232,237,238,241,242,245,246,248,250,251,253,255,258,259,260,
               264,265,266,267,268,275,276,277,278,279]

def label_for(item):
    t = item['title'] or item['titleJpn']
    t = re.sub(r'^\([^)]*\)\s*', '', t)
    author = re.match(r'^\[([^\]]+)\]', t)
    author = author[1] if author and author[1].lower() not in ['chinese', 'digital', 'rough translate'] else ''
    t = re.sub(r'\[[^\]]*\]', '', t).strip()
    return t + ('（' + author + '）' if author else '')

used = {m if isinstance(m, int) else m['gid'] for g in groups for m in g['members']}
for i in gap_singles:
    x = singles[i]
    assert x['gid'] not in used, x['gid']
    reason = '疑缺首篇：题名明确有后续编号／续篇标记，当前库未见可可靠对应的首篇。此为题名线索，非作品页或内容核实。'
    if i in [0,61]:
        reason += ' 同作者 BADend 2／4 的「二人的秘密」与「皆的秘密」题名不同，关系待核，分别保留。'
    if i in [10,90,157,200,210,237,238]:
        reason = '疑缺首篇：当前仅见后篇／下篇／最终话标记，未见可对应的前篇；不猜测具体章号。'
    if i == 120:
        reason = '疑缺首篇（第2季分支）：见 Season 2 #4，未见此季#1；Season 2本身不当作第2章。'
    if i == 133:
        reason = '疑缺首篇：仅见3.7后日谈，当前库未见可对应原篇；不把3.7改为整数篇。'
    if i == 156:
        reason = '疑缺首篇（日常2分支）：本书是日常2的前篇，当前库未见日常1；前篇标记不等于整个系列首篇。'
    if i == 279:
        reason += ' 同作者另有不同题名的教学总集篇，无法证明收录本作首篇，暂不并组。'
    group = new_group(label_for(x), [x['gid']], '独立保留；只标记疑缺，不虚构不存在的首篇或归组关系。')
    flag(group, 'suspected', reason)
    used.add(x['gid'])

for i in [3,6,8,12,15,17,20,21]:
    x = extra[i]
    group = new_group(label_for(x), [x['gid']], '标题有续篇／后日谈或下篇线索；当前未见可可靠对应的前作。')
    flag(group, 'suspected', '疑缺首篇：只有后续篇的题名线索；未核正文，不能排除题名变化或同一册收录前作。')
    assert x['gid'] not in used
    used.add(x['gid'])

for i in [12,13]:
    x = language[i]
    group = new_group(label_for(x), [x['gid']], 'Sono Ni／Ni 为题名末端续篇标记；未见相应首篇。')
    flag(group, 'suspected', '疑缺首篇：题名末端标 Ni／Sono Ni（二），当前库未见可可靠对应的前篇；不将日语助词 ni 当章号。')
    assert x['gid'] not in used
    used.add(x['gid'])

for i in [9,29,68,69,94,100,105,114,115,128,131,132,138,144,195,236,262,273]:
    x = singles[i]
    group = new_group(label_for(x), [{'gid': x['gid'], 'category': 'collection'}], '按合集／选集编号保留，不据此推定单一故事连续。')
    status = 'collection' if i == 132 else 'numbering'
    reason = ('合集待核：本册混收主作品与 Hatoba-san to Saruyama-kun 2，前篇可能收在同册，未核目录。' if i == 132 else
              '编号待核：当前仅见较后编号的合集／选集；未见同题第1册，卷号不自动代表故事章号。')
    flag(group, status, reason)
    assert x['gid'] not in used
    used.add(x['gid'])

# Preserve earlier decisions about genuinely uncertain cross-title relations.
uncertain = read(ROOT / 'artifacts/manga-organize-20261003/uncertain.json')
uncertain = [{**u, 'gids': [g for g in u['gids'] if g in by]} for u in uncertain]
uncertain = [u for u in uncertain if len(u['gids']) > 1]
uncertain.extend([
    {'gids': [4237162,3022174], 'note': 'BADend 4 与 BADend 2 作者/IP相同，二人／皆的秘事题名不同，未确认续篇；各标疑缺首篇，未强制并组。'},
    {'gids': [4231174,3779091,4017044], 'note': '同作者与 Lizana 人物不能证明同系列；School Prey Part01保持独立，Sinful Lust依已有系列归组。'},
    {'gids': [3178758,3825890], 'note': '不同题名的学生教学作品与总集篇，未核目录；不能确认总集篇含首篇。'},
])

# Unnumbered originals remain unnumbered. Place identified base titles before their
# numbered sequels without silently assigning a chapter number to them.
bare_candidates = read(RUN / 'unnumbered-original-candidates.json')
prelude = {gid for c in bare_candidates if c['i'] not in [104,143,275,288,331] for gid in c['bare']}
prelude.discard(3596281)  # Okawari follows the unnumbered original.
prelude.add(1588501)
prelude.update([3978954,3780833])

def member_for(group, gid):
    return next(m for m in group['members'] if (m if isinstance(m, int) else m['gid']) == gid)

# The range 1-2 is explicitly printed on the collection, so it precedes 3 onwards.
early_collections = {199:[3604449],220:[3855698],340:[3943697],356:[3743972,3289938],
                     357:[3394869],368:[3571621],370:[3806543],331:[3331224],117:[3346349,4223833]}

groups = [g for g in groups if g['members']]
write('groups.json', {'series': groups})
write('missing-first.json', {'scope': '当前Debug书库的题名线索；不表示外部作品不存在', 'flags': flags})
write('uncertain.json', uncertain)
plan = organizer.build(catalog, {'series': groups})
blocks = {}
for row in plan['items']:
    blocks.setdefault(row['seriesId'], []).append(row)
for values in blocks.values():
    originals = [r for r in values if r['gid'] in prelude and r['category'] == 'main' and r['order'] is None]
    originals.sort(key=lambda r:r['originalPosition'])
    for r in originals:
        r['note'] += ' 未编号原作先列；未擅自补写章号1。'
    values[:] = originals + [r for r in values if r not in originals]
    gids = {r['gid'] for r in values}
    for index, early in early_collections.items():
        if any(g in gids for g in early):
            front = [r for g in early for r in values if r['gid'] == g]
            # 36-40 must follow the standalone chapter3 as well as the 1-35 volume.
            if index == 117:
                front = [r for r in front if r['gid'] != 4223833]
            values[:] = front + [r for r in values if r not in front]
            break
plan['items'] = [row for values in blocks.values() for row in values]
plan['gidOrder'] = [row['gid'] for row in plan['items']]
organizer.validate_plan(catalog, plan)
write('preview/order.json', plan)
(RUN / 'preview/review.html').write_text(organizer.review_html(plan), encoding='utf-8')

counts = Counter(f['status'] for f in flags)
multi = [v for v in blocks.values() if len(v) > 1]
summary = {'itemCount': len(by), 'multiSeries': len(multi), 'groupedItems': sum(map(len,multi)),
           'singleItems': len(by) - sum(map(len,multi)), 'flags': dict(counts), 'uncertainRelations': len(uncertain),
           'confirmed': False, 'phoneWritten': False, 'onlyPreviewLabelsChanged': True}
write('summary.json', summary)

lines = ['# 疑缺首篇与待核清单（2026-10-09）', '',
         '来源：本次手机 `com.ehviewer.manbo.debug` 数据库一致性副本，共3,314条。沿用10月3日已读过的归组判断，逐项检查85条新增题名，并回看本次缺篇候选及相关条目。', '',
         '“疑缺首篇”表示库内未识别到可可靠对应的首篇，不表示外部作品不存在；未逐本核正文或联网检索。未编号原作、明确含第1篇的合集不标缺首篇。合集目录不明和编号体系不明另列。', '',
         '预览中搜索“疑缺首篇”“合集待核”或“编号待核”即可筛选。标记只存在电脑整理结果，原标题、titleJpn与手机数据未改。', '',
         '[完整离线预览](preview/review.html)', '']
for status, heading in [('suspected','疑缺首篇'),('collection','合集待核'),('numbering','编号待核')]:
    subset = [f for f in flags if f['status'] == status]
    lines += ['## ' + heading + '（' + str(len(subset)) + '项）', '']
    for i, f in enumerate(subset,1):
        lines += [str(i) + '. **' + f['series'] + '**', '   ' + f['reason']]
        for m in f['members']:
            lines += ['   - GID `' + str(m['gid']) + '`：' + (m['title'] or m['titleJpn'])]
        lines.append('')
(RUN / '缺篇清单.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False))
