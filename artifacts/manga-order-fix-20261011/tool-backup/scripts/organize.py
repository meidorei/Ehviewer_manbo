#!/usr/bin/env python3
"""Direct EhViewer grouping and safe copy-only ordering. Python standard library."""
import argparse
from contextlib import contextmanager, closing
import base64
import hashlib
import html
import json
import math
from pathlib import Path
import sqlite3
import time

KINDS = ('main', 'extra', 'collection', 'remaster', 'other')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def pairs(values):
    out = {}
    for k, v in values:
        require(k not in out, 'duplicate JSON key: ' + k)
        out[k] = v
    return out


def read(path):
    def bad(value):
        raise ValueError('non-finite JSON: ' + value)
    return json.loads(Path(path).read_text('utf-8-sig'), object_pairs_hook=pairs, parse_constant=bad)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as f:
        f.write(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def integer(x):
    return type(x) is int and 0 < x <= 9007199254740991


def number(x):
    return type(x) in (int, float) and math.isfinite(x) and x >= 0


def normalize(value):
    values = value if isinstance(value, list) else value['items']
    require(isinstance(values, list), 'items must be a list')
    result = []
    for i, row in enumerate(values, 1):
        require(isinstance(row, dict) and integer(row.get('gid')), 'invalid GID')
        item = dict(gid=row['gid'], title=row.get('title'), titleJpn=row.get('titleJpn'), originalPosition=row.get('originalPosition', i))
        require(integer(item['originalPosition']), 'invalid originalPosition')
        require(all(x is None or isinstance(x, str) for x in (item['title'], item['titleJpn'])), 'invalid title')
        result.append(item)
    require(len({x['gid'] for x in result}) == len(result), 'duplicate GID')
    require(len({x['originalPosition'] for x in result}) == len(result), 'duplicate originalPosition')
    return sorted(result, key=lambda x: x['originalPosition'])


def catalog_hash(items):
    values = [{k: x[k] for k in ('gid', 'title', 'titleJpn')} for x in sorted(items, key=lambda x: x['gid'])]
    return hashlib.sha256(canonical(values).encode()).hexdigest()


def title_page(catalog, offset=0, limit=40, max_chars=6000, query='', previous=None):
    """Bound stdout before sending titles to the chat; never clip a title."""
    require(type(offset) is int and offset >= 0, 'offset must be nonnegative')
    require(type(limit) is int and 1 <= limit <= 100, 'limit must be 1..100')
    require(type(max_chars) is int and 1000 <= max_chars <= 12000, 'max-chars must be 1000..12000')
    items = normalize(catalog)
    total = len(items)
    if previous is not None:
        old = {x['gid']: x for x in normalize(previous)}
        items = [x for x in items if x['gid'] not in old or
                 any(x[k] != old[x['gid']][k] for k in ('title', 'titleJpn'))]
    query = query.casefold().strip()
    if query:
        items = [x for x in items if query in
                 ' '.join(str(x[k] or '') for k in ('gid', 'title', 'titleJpn')).casefold()]
    require(offset <= len(items), 'offset exceeds matched count')
    page = dict(totalCount=total, matchedCount=len(items), offset=offset,
                nextOffset=None, items=[])
    for item in items[offset:offset + limit]:
        page['items'].append(item)
        end = offset + len(page['items'])
        page['nextOffset'] = end if end < len(items) else None
        # Windows stdout may write CRLF; reserve both characters.
        if len(canonical(page)) + 2 > max_chars:
            page['items'].pop()
            end -= 1
            page['nextOffset'] = end if end < len(items) else None
            require(page['items'], 'title exceeds page budget at GID ' + str(item['gid']) +
                    '; inspect this record in a local file or increase --max-chars')
            break
    return page


def quote(name):
    return '"' + name.replace('"', '""') + '"'


@contextmanager
def connect(path):
    con = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        yield con
    finally:
        con.close()


def snapshot(source, target):
    target = Path(target)
    require(not target.exists(), 'refusing to overwrite snapshot: ' + str(target))
    target.parent.mkdir(parents=True, exist_ok=True)
    with connect(source) as src, closing(sqlite3.connect(target)) as dst:
        src.backup(dst)
        require(dst.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'invalid SQLite snapshot')


def db_items(db, writable=False):
    with connect(db) as con:
        columns = {x[1].upper(): x[1] for x in con.execute('PRAGMA table_info(DOWNLOADS)')}
        require('GID' in columns and ('TITLE' in columns or 'TITLE_JPN' in columns), 'unsupported DOWNLOADS schema')
        if writable:
            require('TIME' in columns, 'DOWNLOADS.TIME missing')
            require(con.execute('SELECT count(*) FROM DOWNLOADS WHERE typeof(TIME) != \'integer\'').fetchone()[0] == 0, 'TIME must contain integer milliseconds')
        names = [quote(columns[k]) if k in columns else 'NULL' for k in ('GID', 'TITLE', 'TITLE_JPN')]
        order = 'TIME DESC, GID DESC' if 'TIME' in columns else 'GID DESC'
        values = con.execute('SELECT ' + ','.join(names) + ' FROM DOWNLOADS ORDER BY ' + order).fetchall()
    return normalize([dict(gid=g, title=t, titleJpn=j) for g, t, j in values])


def db_digest(db, ignore_time=False):
    def cell(value):
        return {'bytes': base64.b64encode(value).decode()} if isinstance(value, bytes) else value
    with connect(db) as con:
        schema = con.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name').fetchall()
        all_data = []
        for table, in con.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
            columns = [x[1] for x in con.execute('PRAGMA table_xinfo(' + quote(table) + ')') if x[6] != 1]
            kept = [c for c in columns if not (ignore_time and table.upper() == 'DOWNLOADS' and c.upper() == 'TIME')]
            rows = [canonical([cell(v) for v in row]) for row in con.execute('SELECT ' + ','.join(map(quote, kept)) + ' FROM ' + quote(table))]
            all_data.append([table, kept, sorted(rows)])
        value = [schema, con.execute('PRAGMA user_version').fetchone(), con.execute('PRAGMA application_id').fetchone(), all_data]
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def validate_plan(catalog, plan, confirmed=False):
    items = normalize(catalog)
    original = {x['gid']: x for x in items}
    require(type(plan.get('formatVersion')) is int and plan['formatVersion'] == 1, 'unsupported plan version')
    require(plan.get('catalogHash') == catalog_hash(items), 'library titles or GIDs changed')
    rows = plan.get('items')
    require(isinstance(rows, list), 'missing plan items')
    require(all(isinstance(r, dict) and integer(r.get('gid')) for r in rows), 'invalid plan GID')
    require(len(rows) == len(original) and {r['gid'] for r in rows} == set(original), 'plan must retain every GID exactly once')
    require(isinstance(plan.get('gidOrder'), list) and all(integer(g) for g in plan['gidOrder']) and plan['gidOrder'] == [r['gid'] for r in rows], 'order does not match items')
    names = {}
    for row in rows:
        require(all(row.get(k) == original[row['gid']][k] for k in ('title', 'titleJpn')), 'original title changed')
        for field in ('seriesId', 'series', 'branch'):
            require(isinstance(row.get(field), str) and row[field].strip(), 'invalid ' + field)
        require(names.get(row['seriesId'], row['series']) == row['series'], 'inconsistent series name')
        names[row['seriesId']] = row['series']
        require(row.get('category') in KINDS, 'invalid category')
        require(row.get('order') is None or number(row['order']), 'invalid chapter order')
        require(row.get('end') is None or (number(row['end']) and row.get('order') is not None and row['end'] >= row['order']), 'invalid collection range')
    if confirmed:
        require(plan.get('confirmed') is True, 'user-confirmed result required')
    return rows


def build(catalog, groups):
    items = normalize(catalog)
    rows = {x['gid']: dict(x, seriesId='item:' + str(x['gid']), series=x['title'] or x['titleJpn'] or str(x['gid']), category='main', branch='main', order=None, end=None, note='独立保留') for x in items}
    require(isinstance(groups, dict) and isinstance(groups.get('series'), list), 'groups needs a series list')
    assigned = set()
    for i, group in enumerate(groups['series'], 1):
        require(isinstance(group.get('name'), str) and group['name'].strip(), 'series name required')
        require(isinstance(group.get('members'), list) and group['members'], 'series members required')
        for member in group['members']:
            member = {'gid': member} if integer(member) else member
            require(isinstance(member, dict) and integer(member.get('gid')), 'invalid member')
            gid = member['gid']
            require(gid in rows and gid not in assigned, 'unknown or duplicate grouped GID')
            assigned.add(gid)
            rows[gid].update(seriesId='series:' + str(i), series=group['name'].strip(), category=member.get('category', 'main'), branch=member.get('branch', 'main'), order=member.get('order'), end=member.get('end'), note=member.get('note', group.get('note', '')))
    grouped = {}
    for row in rows.values():
        grouped.setdefault(row['seriesId'], []).append(row)
    ordered = []
    for values in sorted(grouped.values(), key=lambda rs: min(r['originalPosition'] for r in rs)):
        anchors = {}
        for row in values:
            key = (row['category'], row['branch'])
            anchors[key] = min(anchors.get(key, row['originalPosition']), row['originalPosition'])
        def key(row):
            require(row['order'] is None or number(row['order']), 'invalid chapter order')
            return (KINDS.index(row['category']), anchors[(row['category'], row['branch'])], row['order'] is None, row['order'] or 0, row['originalPosition'])
        ordered.extend(sorted(values, key=key))
    plan = dict(formatVersion=1, catalogHash=catalog_hash(items), confirmed=False, items=ordered, gidOrder=[r['gid'] for r in ordered])
    validate_plan(catalog, plan)
    return plan


def apply(db, catalog, plan, backup, output):
    validate_plan(catalog, plan, confirmed=True)
    require(Path(backup).resolve() != Path(output).resolve(), 'backup and output must differ')
    require(not Path(backup).exists() and not Path(output).exists(), 'refusing to overwrite database files')
    snapshot(db, backup)
    fresh = db_items(backup, writable=True)
    require(catalog_hash(fresh) == plan['catalogHash'], 'fresh library changed; regroup before writing')
    before = db_digest(backup, ignore_time=True)
    snapshot(backup, output)
    stamp = int(time.time() * 1000)
    with closing(sqlite3.connect(output)) as con, con:
        for i, gid in enumerate(plan['gidOrder']):
            require(con.execute('UPDATE DOWNLOADS SET TIME=? WHERE GID=?', (stamp-i*1000, gid)).rowcount == 1, 'GID update mismatch')
    with connect(output) as con:
        require(con.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'SQLite integrity failed')
    require(db_digest(output, ignore_time=True) == before, 'non-TIME data or schema changed')
    require([x['gid'] for x in db_items(output)] == plan['gidOrder'], 'database order mismatch')
    return dict(status='passed', itemCount=len(fresh), onlyDownloadsTimeChanged=True, backup=str(backup), output=str(output))


def review_html(plan):
    esc = lambda x: html.escape(str(x or ''), quote=True)
    static = ''.join('<article><b>' + esc(r['title'] or r['titleJpn']) + '</b><p>' + esc(r['titleJpn']) + '</p><p>GID ' + str(r['gid']) + ' · ' + esc(r['series']) + '</p></article>' for r in plan['items'])
    data = json.dumps(plan, ensure_ascii=False, allow_nan=False).replace('<', '\\u003c').replace('>', '\\u003e').replace('&', '\\u0026')
    template = (Path(__file__).resolve().parents[1] / 'assets' / 'review.html').read_text(encoding='utf-8')
    before, after = template.split('@@STATIC@@', 1)
    middle, tail = after.split('@@DATA@@', 1)
    return before + static + middle + data + tail



def main():
    p = argparse.ArgumentParser(description='EhViewer: catalog → groups → preview → confirmed database copy.')
    sub = p.add_subparsers(dest='command', required=True)
    t = sub.add_parser('titles', help='bounded title page for agent reading')
    t.add_argument('--catalog', required=True, type=Path)
    t.add_argument('--previous', type=Path)
    t.add_argument('--query', default='')
    t.add_argument('--offset', type=int, default=0)
    t.add_argument('--limit', type=int, default=40)
    t.add_argument('--max-chars', type=int, default=6000)
    c = sub.add_parser('catalog');src=c.add_mutually_exclusive_group(required=True);src.add_argument('--db', type=Path);src.add_argument('--json', type=Path);c.add_argument('--out', required=True, type=Path)
    b = sub.add_parser('build');b.add_argument('--catalog', required=True, type=Path);b.add_argument('--groups', required=True, type=Path);b.add_argument('--out', required=True, type=Path)
    a = sub.add_parser('apply');a.add_argument('--db', required=True, type=Path);a.add_argument('--catalog', required=True, type=Path);a.add_argument('--order', required=True, type=Path);a.add_argument('--out', required=True, type=Path)
    v = sub.add_parser('verify');v.add_argument('--db', required=True, type=Path);v.add_argument('--expected', required=True, type=Path)
    args = p.parse_args()
    if args.command == 'titles':
        page = title_page(read(args.catalog), args.offset, args.limit, args.max_chars,
                          args.query, read(args.previous) if args.previous else None)
        print(canonical(page));return
    if args.command == 'verify':
        with connect(args.db) as con:
            require(con.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'SQLite integrity failed')
        require(db_digest(args.db) == db_digest(args.expected), 'database differs from verified sorting copy')
        print(json.dumps(dict(status='passed', databaseMatches=True)));return
    args.out.mkdir(parents=True, exist_ok=True)
    if args.command == 'catalog':
        if args.db:
            snapshot(args.db, args.out/'source.db');items=db_items(args.out/'source.db')
        else:
            items=normalize(read(args.json))
        write(args.out/'catalog.json',dict(items=items));print(json.dumps(dict(itemCount=len(items))));return
    catalog = read(args.catalog)
    if args.command == 'build':
        plan=build(catalog,read(args.groups));require(not (args.out/'review.html').exists(), 'preview already exists')
        write(args.out/'order.json',plan)
        with (args.out/'review.html').open('x',encoding='utf-8') as f:f.write(review_html(plan))
        print(json.dumps(dict(itemCount=len(plan['items']),seriesCount=len({r['seriesId'] for r in plan['items']}))));return
    require(not (args.out/'report.json').exists(), 'report already exists')
    report=apply(args.db,catalog,read(args.order),args.out/'backup.db',args.out/'sorted.db')
    write(args.out/'report.json',report);print(json.dumps(report))


if __name__ == '__main__':
    main()
