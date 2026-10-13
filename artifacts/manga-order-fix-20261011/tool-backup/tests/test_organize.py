"""Synthetic data only; this suite does not classify or confirm a real library."""
import copy
from contextlib import closing
from html.parser import HTMLParser
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
import organize as o


class ToolkitTest(unittest.TestCase):
    def catalog(self):
        return {'items': [dict(gid=1,title='[A] Aoi Niwa 2',titleJpn=None),dict(gid=2,title='Unrelated',titleJpn=None),dict(gid=3,title='[A] 蓝色庭院 0',titleJpn=None),dict(gid=4,title='[A] Blue Garden',titleJpn='青い庭'),dict(gid=5,title='[A] 青い庭 第0話',titleJpn=None)]}

    def groups(self):
        return {'series':[dict(name='蓝色庭院（A）',members=[{'gid':1,'order':2},{'gid':3,'order':0},4,{'gid':5,'order':0}],note='Synthetic explicit grouping.') ]}

    def database(self, path, trigger=False):
        with closing(sqlite3.connect(path)) as con, con:
            con.execute('CREATE TABLE DOWNLOADS (GID INTEGER PRIMARY KEY, TITLE TEXT, TITLE_JPN TEXT, TIME INTEGER, STATE INTEGER)')
            con.execute('CREATE TABLE extra (value BLOB)');con.execute('INSERT INTO extra VALUES (?)',(b'unchanged',))
            con.executemany('INSERT INTO DOWNLOADS VALUES(?,?,?,?,?)',[(r['gid'],r['title'],r['titleJpn'],100-r['gid'],1) for r in self.catalog()['items']])
            if trigger:con.execute("CREATE TRIGGER changed AFTER UPDATE ON DOWNLOADS BEGIN UPDATE extra SET value='changed'; END")

    def test_multilingual_grouping_order_zero_unknown_and_versions(self):
        plan=o.build(self.catalog(),self.groups())
        self.assertEqual([3,5,1,4,2],plan['gidOrder'])
        self.assertEqual(5,len(plan['items']));self.assertFalse(plan['confirmed'])
        self.assertEqual('item:2',plan['items'][-1]['seriesId'])

    def test_unknown_duplicate_and_bad_number_rejected(self):
        for members in ([1,1],[99],[True],[{'gid':1,'order':float('inf')}],[{'gid':1,'order':-1}],[{'gid':1,'order':3,'end':2}]):
            with self.subTest(members=members),self.assertRaises((ValueError,TypeError)):o.build(self.catalog(),{'series':[{'name':'test','members':members}]})

    def test_full_gid_metadata_and_confirmation_checked(self):
        plan=o.build(self.catalog(),self.groups())
        with self.assertRaises(ValueError):o.validate_plan(self.catalog(),plan,True)
        plan['confirmed']=True;o.validate_plan(self.catalog(),plan,True)
        for kind in ['missing','title','hash','bool']:
            bad=copy.deepcopy(plan)
            if kind=='missing':bad['items'].pop()
            elif kind=='title':bad['items'][0]['title']='changed'
            elif kind=='hash':bad['catalogHash']='fake'
            else:bad['gidOrder'][2]=True
            with self.subTest(kind=kind),self.assertRaises(ValueError):o.validate_plan(self.catalog(),bad)

    def test_html_static_titles_and_json_escape(self):
        class Parser(HTMLParser):
            def __init__(self):super().__init__();self.data=[];self.active=False;self.count=0
            def handle_starttag(self,tag,attrs):
                if tag=='article':self.count+=1
                if tag=='script' and dict(attrs).get('id')=='data':self.active=True
            def handle_endtag(self,tag):
                if tag=='script':self.active=False
            def handle_data(self,data):
                if self.active:self.data.append(data)
        catalog=self.catalog();catalog['items'][0]['title']='<script>alert(1)</script> & test'
        plan=o.build(catalog,self.groups());page=o.review_html(plan);parser=Parser();parser.feed(page)
        self.assertEqual(plan,json.loads(''.join(parser.data)));self.assertEqual(5,parser.count)
        self.assertIn('&lt;script&gt;',page);self.assertNotIn('<script>alert(1)</script>',page)

    def test_write_and_safe_json_roundtrip(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t)/'data.json';o.write(p,self.catalog());self.assertEqual(self.catalog(),o.read(p))
            with self.assertRaises(FileExistsError):o.write(p,{})
            p.write_text('{"x":1,"x":2}')
            with self.assertRaises(ValueError):o.read(p)

    def test_database_copy_changes_only_time(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t);self.database(p/'source.db');original=(p/'source.db').read_bytes()
            plan=o.build(self.catalog(),self.groups());plan['confirmed']=True
            report=o.apply(p/'source.db',self.catalog(),plan,p/'backup.db',p/'sorted.db')
            self.assertEqual('passed',report['status']);self.assertEqual(original,(p/'source.db').read_bytes())
            self.assertEqual(o.db_digest(p/'backup.db',True),o.db_digest(p/'sorted.db',True))
            self.assertEqual(plan['gidOrder'],[r['gid'] for r in o.db_items(p/'sorted.db')])

    def test_fresh_title_changes_and_trigger_mutations_block_apply(self):
        for kind in ['title','trigger']:
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as t:
                p=Path(t);self.database(p/'source.db',trigger=kind=='trigger')
                if kind=='title':
                    with closing(sqlite3.connect(p/'source.db')) as con, con:con.execute("UPDATE DOWNLOADS SET TITLE='Changed' WHERE GID=1")
                plan=o.build(self.catalog(),self.groups());plan['confirmed']=True
                with self.assertRaises(ValueError):o.apply(p/'source.db',self.catalog(),plan,p/'backup.db',p/'sorted.db')

    def test_snapshot_includes_committed_wal(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t);self.database(p/'source.db');con=sqlite3.connect(p/'source.db');con.execute('PRAGMA journal_mode=WAL');con.execute("UPDATE DOWNLOADS SET TITLE='Committed WAL' WHERE GID=1");con.commit()
            o.snapshot(p/'source.db',p/'snapshot.db');self.assertEqual('Committed WAL',o.db_items(p/'snapshot.db')[0]['title']);con.close()

    def test_cli_catalog_build_apply_verify(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t);self.database(p/'source.db');o.write(p/'groups.json',self.groups())
            def run(*args):
                result=subprocess.run([sys.executable,'-B',o.__file__,*map(str,args)],capture_output=True,text=True)
                self.assertEqual(0,result.returncode,result.stderr)
            run('catalog','--db',p/'source.db','--out',p/'run')
            run('build','--catalog',p/'run/catalog.json','--groups',p/'groups.json','--out',p/'preview')
            plan=o.read(p/'preview/order.json');plan['confirmed']=True;o.write(p/'confirmed.json',plan)
            run('apply','--db',p/'source.db','--catalog',p/'run/catalog.json','--order',p/'confirmed.json','--out',p/'writeback')
            run('verify','--db',p/'writeback/sorted.db','--expected',p/'writeback/sorted.db')

    def test_title_pages_budget_and_full_coverage(self):
        catalog = {'items': [dict(gid=i, title=('长标题<&>\\n' * 35) + str(i))
                             for i in range(1, 121)]}
        offset, seen = 0, []
        while offset is not None:
            page = o.title_page(catalog, offset, limit=40, max_chars=1000)
            self.assertLessEqual(len(o.canonical(page)) + 2, 1000)
            self.assertGreater(len(page['items']), 0)
            seen.extend(page['items'])
            if page['nextOffset'] is not None:
                self.assertGreater(page['nextOffset'], offset)
            offset = page['nextOffset']
        self.assertEqual(o.normalize(catalog), seen)
        empty = o.title_page(catalog, query='absent')
        self.assertEqual([], empty['items']);self.assertIsNone(empty['nextOffset'])

    def test_title_pages_delta_and_literal_query(self):
        old = self.catalog()
        new = copy.deepcopy(old)
        new['items'][0]['titleJpn'] = 'Changed Japanese'
        new['items'].pop(1)
        new['items'].append(dict(gid=6, title='New Aoi Niwa', originalPosition=6))
        page = o.title_page(new, previous=old)
        self.assertEqual([1, 6], [x['gid'] for x in page['items']])
        page = o.title_page(new, previous=old, query='AOI NIWA')
        self.assertEqual([1, 6], [x['gid'] for x in page['items']])
        self.assertEqual([], o.title_page(new, query='.*')['items'])

    def test_title_pages_reject_invalid_or_oversized_input(self):
        for kwargs in [dict(offset=-1), dict(offset=6), dict(limit=0),
                       dict(limit=101), dict(max_chars=999), dict(max_chars=12001)]:
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                o.title_page(self.catalog(), **kwargs)
        with self.assertRaisesRegex(ValueError, 'GID 10'):
            o.title_page([dict(gid=10, title='x' * 2000)], max_chars=1000)

    def test_cli_titles_bounded_json(self):
        with tempfile.TemporaryDirectory() as t:
            p = Path(t)/'catalog.json';o.write(p, self.catalog())
            result = subprocess.run([sys.executable, '-B', o.__file__, 'titles',
                                     '--catalog', str(p), '--limit', '2'],
                                    capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertLessEqual(len(result.stdout), 6000)
            page = json.loads(result.stdout)
            self.assertEqual(2, page['nextOffset'])
            self.assertEqual([1, 2], [x['gid'] for x in page['items']])


if __name__=='__main__':unittest.main()
