import copy
import io
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch
from fixture_support import *
import common
import generate_candidates as candidates
import seed_historical_decisions as history
import migrate_v2
import generate_review_page
from database import *

ROOT=Path(__file__).resolve().parents[1]


def run_script(name,*args):
    return subprocess.run([sys.executable,'-B',str(ROOT/'scripts'/(name+'.py')),*map(str,args)],capture_output=True,text=True)


def discovery(catalog,aliases=None,keys=None):
    return dict(formatVersion=1,snapshotFingerprint=catalog['snapshotFingerprint'],metadataFingerprint=catalog['metadataFingerprint'],
                annotations=[dict(gid=x['gid'],authorAliases=(aliases or {}).get(x['gid'],[]),workAliases=[],semanticTitleKey=(keys or {}).get(x['gid']),partKind='unknown',partNumber=None,confidence=.8,needsHumanReview=True) for x in catalog['items']])


def generate(catalog,annotations,latest=None):
    with tempfile.TemporaryDirectory() as temp:
        p=Path(temp);write_json(p/'c.json',catalog);write_json(p/'d.json',annotations)
        args=['--catalog',p/'c.json','--discovery',p/'d.json','--candidates',p/'out.json','--author-buckets',p/'authors.json','--unresolved',p/'u.json']
        if latest is not None:write_json(p/'latest.json',latest);args+=['--decisions',p/'latest.json']
        r=run_script('generate_candidates',*args)
        if r.returncode:raise RuntimeError(r.stderr)
        return tuple(read_json(p/x) for x in ('out.json','authors.json','u.json'))


class ContractsTest(unittest.TestCase):
    def setUp(self):self.c=test_catalog();self.m=test_model(self.c)
    def test_json_duplicate_and_nonfinite_rejected(self):
        for raw in ('{"a":1,"a":2}','{"a":NaN}','{"a":1e309}','{"a":Infinity}','{"a":-Infinity}','{} trailing','```{} ```'):
            with self.subTest(raw=raw),self.assertRaises(ValueError):loads(raw)
    def test_gid_types_and_duplicate_positions(self):
        for key,value in (('gid',True),('gid',1.2),('gid',2**53),('originalPosition',2)):
            c=copy.deepcopy(self.c);c['items'][0][key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):validate_catalog(c)
    def test_non_contiguous_model_rejected(self):
        rows=[test_row(x,'a' if x['gid'] in (1,3) else 'b',x['gid']) for x in self.c['items']]
        m=test_model(self.c,rows);m['decisions']=sorted(m['decisions'],key=lambda x:x['gid']);m['gidOrder']=[1,2,3,4];m['seriesOrder']=['a','b']
        with self.assertRaises(ValueError):validate_order(self.c,m)
    def test_missing_branch_and_invalid_position_rejected(self):
        for kind in ('branch','nan','low'):
            rows=copy.deepcopy(self.m['decisions'])
            if kind=='branch':rows[0].pop('branch')
            elif kind=='nan':rows[0]['position']['chapter']=float('nan')
            else:rows[0]['orderConfidence']=.01
            with self.subTest(kind=kind),self.assertRaises(ValueError):validate_decisions(self.c,rows)
    def test_web_evidence_requires_real_page_and_time(self):
        rows=copy.deepcopy(self.m['decisions']);e=dict(kind='web',claim='chapter listing',url='https://example.org/work',accessedAt='2026-09-11T12:00:00Z',sourceType='page');rows[0]['evidence']=[e]
        validate_decisions(self.c,rows)
        for key,value in (('sourceType','snippet'),('accessedAt','yesterday'),('url','file:///private'),('url','https://user:password@example.org')):
            bad=copy.deepcopy(rows);bad[0]['evidence'][0][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validate_decisions(self.c,bad)
    def test_audit_coverage_chain_and_final_digest(self):
        for kind in ('missing','coverage','digest','conflict'):
            m=copy.deepcopy(self.m)
            if kind=='missing':m['auditTrail'].pop()
            if kind=='coverage':m['auditTrail'][0]['reviewedGids'].pop()
            if kind=='digest':m['decisions'][0]['reason']='changed after audit'
            if kind=='conflict':m['auditTrail'][3]['conflicts']=['unresolved disagreement']
            with self.subTest(kind=kind),self.assertRaises(ValueError):validate_order(self.c,m)
    def test_title_change_invalidates_same_gid_catalog(self):
        items=copy.deepcopy(self.c['items']);items[0]['title']='changed';new=make_catalog(items)
        self.assertEqual(self.c['snapshotFingerprint'],new['snapshotFingerprint'])
        with self.assertRaises(ValueError):validate_order(new,self.m)
    def test_model_digest_and_replay_prevent_silent_edits(self):
        e=confirmed_export(self.c,self.m,'a'*64);validate_order(self.c,e,self.m,'a'*64,True)
        e['decisions'][0]['reason']='silent edit'
        with self.assertRaises(ValueError):validate_order(self.c,e,self.m,'a'*64,True)
    def test_pending_export_cannot_be_applied(self):
        e=confirmed_export(self.c,self.m,'a'*64);e['reviewStatus']='draft'
        with self.assertRaises(ValueError):validate_order(self.c,e,self.m,'a'*64,True)
    def test_confirmed_flag_does_not_skip_pending_items(self):
        e={**self.m,'operations':[],'baseModelDigest':'a'*64,'reviewStatus':'confirmed'}
        with self.assertRaises(ValueError):validate_order(self.c,e,self.m,'a'*64,True)


class OrderingAndReplayTest(unittest.TestCase):
    def test_zero_unknown_category_branch_translation_and_ranges(self):
        c=test_catalog(8)
        rows=[test_row(x,chapter=ch,category=cat,branch=br) for x,ch,cat,br in zip(c['items'],[2,0,None,1,1,3,1,4],['main']*5+['extra','collection','remaster'],['main','main','main','main','main','main','main','main'])]
        rows[2]['orderConfidence']=.01
        rows[6]['position']['rangeEnd']=5
        order=stable_order(c,rows)
        self.assertEqual([2,4,5,1,3,6,7,8],order['gidOrder'])
        self.assertEqual([1,4,5,2,3,6,7,8],stable_order(c,rows,'descending')['gidOrder'])
    def test_story_branches_do_not_interleave(self):
        c=test_catalog(3);rows=[test_row(c['items'][0],chapter=1),test_row(c['items'][1],chapter=2,branch='side'),test_row(c['items'][2],chapter=3)]
        self.assertEqual([1,3,2],stable_order(c,rows)['gidOrder'])
    def test_all_operations_replay_and_manual_pin_exception(self):
        c=test_catalog();m=test_model(c)
        ops=[dict(type='assign',gids=[2,3],seriesId='new',title='New'),dict(type='rename',seriesId='new',title='Renamed'),
             dict(type='edit',gid=2,patch=dict(category='extra')),dict(type='direction',value='descending'),
             dict(type='moveSeries',seriesId='new',before='series:one'),dict(type='moveItem',gid=1,before=3),dict(type='pin',gid=4)]
        e=confirmed_export(c,m,'a'*64,ops);validate_order(c,e,m,'a'*64,True)
        self.assertEqual(4,e['gidOrder'][0]);self.assertEqual('Renamed',next(x for x in e['decisions'] if x['gid']==2)['canonicalSeriesTitle'])
    def test_unsafe_review_ops_are_rejected(self):
        c=test_catalog();m=test_model(c)
        for op in (dict(type='edit',gid=1,patch={'gid':2}),dict(type='assign',gids=[1,1],seriesId='a',title='a'),dict(type='moveItem',gid=1,before=999),dict(type='delete',gid=1)):
            with self.subTest(op=op),self.assertRaises(ValueError):replay(c,m,[op])
    def test_collection_range_is_validated(self):
        c=test_catalog(1);rows=[test_row(c['items'][0],chapter=5,category='collection')];rows[0]['position']['rangeEnd']=2
        with self.assertRaises(ValueError):stable_order(c,rows)


class RecallAndHistoryTest(unittest.TestCase):
    def test_event_prefix_and_discovery_alias_author_buckets(self):
        self.assertIn('artist',candidates.authors('(C104) [Circle (Artist)] Work'))
        c=make_catalog([dict(gid=1,title='[作者甲] 青い庭',originalPosition=1),dict(gid=2,title='[Author A] Crimson Castle',originalPosition=2)])
        out,authors,u=generate(c,discovery(c,{1:['author a'],2:['author a']}))
        self.assertTrue(any(set(b['gids'])=={1,2} for b in authors['buckets']))
        self.assertEqual(0,out['candidateCount']) # author identity alone must never merge works
    def test_chain_is_bounded_and_cross_batch_edges_remain(self):
        c=make_catalog([dict(gid=i,originalPosition=i,title=f'Unique {i}',titleJpn=None) for i in range(1,161)])
        a=discovery(c)
        for row in a['annotations']:
            i=row['gid'];row['workAliases']=[f'link{i}',f'link{i+1}']
        out,_,_=generate(c,a)
        self.assertTrue(all(len(x['items'])<=75 for x in out['candidates']))
        self.assertGreater(len(out['candidates']),1)
        self.assertTrue(any(edge['gids']==[75,76] for edge in out['edges']))
    def test_rejected_candidates_reappear_in_unresolved(self):
        c=test_catalog(2);out,_,_=generate(c,discovery(c,keys={1:'work',2:'work'}))
        self.assertEqual(1,out['candidateCount'])
        latest=envelope(c,[singleton(x) for x in c['items']]);_,_,u=generate(c,discovery(c,keys={1:'work',2:'work'}),latest)
        self.assertEqual({1,2},{x['gid'] for x in u['items']})
    def test_generic_history_new_changed_deleted(self):
        c=test_catalog(3);m=test_model(c);items=copy.deepcopy(c['items']);items[1]['title']='changed';items.pop();items.append(dict(gid=10,originalPosition=4,title='new',titleJpn=None));current=make_catalog(items)
        result=history.build(current,c,m)
        self.assertEqual(dict(reusedUnchangedGids=1,changedMetadataGids=1,newGids=1),result['historyReuse'])
        self.assertTrue(all(x['needsHumanReview'] for x in result['decisions']))
        self.assertEqual('item:2',result['decisions'][1]['canonicalSeriesId'])
    def test_legacy_migration_retains_evidence_but_never_confirms(self):
        c=test_catalog(1);old={'formatVersion':2,'snapshotFingerprint':c['snapshotFingerprint'],'items':c['items']}
        old_order={'formatVersion':2,'snapshotFingerprint':c['snapshotFingerprint'],'decisions':[dict(gid=1,canonicalSeriesId='old',canonicalSeriesTitle='Old',branch='translation',itemOrder=0,confidence=.9,reason='legacy')]}
        current,result=migrate_v2.migrate(old,old_order)
        self.assertIsNone(result['decisions'][0]['position']);self.assertTrue(result['decisions'][0]['needsHumanReview']);self.assertEqual('old',result['decisions'][0]['canonicalSeriesId'])
        with self.assertRaises(ValueError):validate_order(current,result)


class DatabaseTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.p=Path(self.temp.name);self.db=self.p/'source.db'
        with sqlite3.connect(self.db,factory=ClosingConnection) as db:
            db.execute('CREATE TABLE DOWNLOADS(GID INTEGER PRIMARY KEY,TITLE TEXT,TIME INTEGER)');db.executemany('INSERT INTO DOWNLOADS VALUES(?,?,?)',[(1,'one',1000),(2,'two',2000)])
            db.execute('CREATE TABLE EXTRA_DATA(ID INTEGER, CONTENT BLOB)');db.execute('INSERT INTO EXTRA_DATA VALUES(1,?)',(b'keep me',))
    def tearDown(self):self.temp.cleanup()
    def setup_order(self):
        c=catalog_from_database(self.db,'milliseconds');m=test_model(c);write_json(self.p/'catalog.json',c);write_json(self.p/'model.json',m)
        e=confirmed_export(c,m,sha256_file(self.p/'model.json'));write_json(self.p/'order.json',e);return c,m,e
    def apply(self):
        return run_script('apply_order','--db',self.db,'--catalog',self.p/'catalog.json','--model',self.p/'model.json','--order',self.p/'order.json','--backup',self.p/'backup.db','--output',self.p/'sorted.db','--report',self.p/'report.json')
    def test_optional_fields_and_unconfirmed_time(self):
        c=catalog_from_database(self.db)
        self.assertFalse(c['source']['writebackSupported']);self.assertIsNone(c['items'][0]['titleJpn'])
        self.assertTrue(catalog_from_database(self.db,'milliseconds')['source']['writebackSupported'])
    def test_wal_snapshot_includes_committed_data(self):
        db=sqlite3.connect(self.db,factory=ClosingConnection);db.execute('PRAGMA journal_mode=WAL');db.execute('INSERT INTO DOWNLOADS VALUES(3,"WAL title",3000)');db.commit()
        try:
            backup_database(self.db,self.p/'snap.db');self.assertEqual(3,len(catalog_from_database(self.p/'snap.db')['items']))
        finally:db.close()
    def test_write_and_verify_all_tables(self):
        c,m,e=self.setup_order();before=logical_digest(self.db,False);r=self.apply();self.assertEqual(0,r.returncode,r.stderr)
        self.assertEqual(before,logical_digest(self.db,False));self.assertEqual(logical_digest(self.db),logical_digest(self.p/'sorted.db'))
        r=run_script('verify_database','--db',self.p/'sorted.db','--expected-db',self.p/'sorted.db','--catalog',self.p/'catalog.json','--model',self.p/'model.json','--order',self.p/'order.json','--report',self.p/'verified.json','--phase','offline');self.assertEqual(0,r.returncode,r.stderr)
        self.assertEqual('passed',read_json(self.p/'verified.json')['status'])
    def test_changed_title_blocks_write(self):
        self.setup_order()
        with sqlite3.connect(self.db,factory=ClosingConnection) as db:db.execute('UPDATE DOWNLOADS SET TITLE="new title" WHERE GID=1')
        r=self.apply();self.assertNotEqual(0,r.returncode);self.assertIn('metadataFingerprint',r.stderr);self.assertFalse((self.p/'sorted.db').exists())
    def test_trigger_changing_other_table_is_detected(self):
        with sqlite3.connect(self.db,factory=ClosingConnection) as db:db.execute('CREATE TRIGGER touch AFTER UPDATE OF TIME ON DOWNLOADS BEGIN UPDATE EXTRA_DATA SET ID=9; END')
        self.setup_order();r=self.apply();self.assertNotEqual(0,r.returncode);self.assertIn('non-TIME',r.stderr);self.assertFalse((self.p/'report.json').exists())
    def test_empty_database_round_trip(self):
        with sqlite3.connect(self.db,factory=ClosingConnection) as db:db.execute('DELETE FROM DOWNLOADS')
        self.setup_order();r=self.apply();self.assertEqual(0,r.returncode,r.stderr);self.assertEqual([],ordered_rows(self.p/'sorted.db'))
    def test_verify_detects_other_table_and_time_mutations(self):
        c,m,e=self.setup_order();self.assertEqual(0,self.apply().returncode);backup_database(self.p/'sorted.db',self.p/'actual.db')
        with sqlite3.connect(self.p/'actual.db',factory=ClosingConnection) as db:db.execute('UPDATE EXTRA_DATA SET ID=7')
        with self.assertRaises(ValueError):validate_database(self.p/'actual.db',self.p/'sorted.db',c,e)
    def test_generated_non_time_column_change_is_detected(self):
        with sqlite3.connect(self.db,factory=ClosingConnection) as db:
            db.execute('ALTER TABLE DOWNLOADS ADD COLUMN DERIVED INTEGER GENERATED ALWAYS AS (TIME + 1) VIRTUAL')
        self.setup_order();r=self.apply();self.assertNotEqual(0,r.returncode);self.assertIn('non-TIME',r.stderr)
    def test_unconfirmed_time_semantics_blocks_apply(self):
        c,m,e=self.setup_order();c['source']['writebackSupported']=False
        (self.p/'catalog.json').write_text(json.dumps(c),encoding='utf-8')
        r=self.apply();self.assertNotEqual(0,r.returncode);self.assertFalse((self.p/'backup.db').exists())
    def test_legacy_order_cannot_write(self):
        self.setup_order();(self.p/'order.json').write_text('{"snapshotFingerprint":"legacy","gidOrder":[1,2]}',encoding='utf-8')
        self.assertNotEqual(0,self.apply().returncode);self.assertFalse((self.p/'backup.db').exists())


class OfflinePageTest(unittest.TestCase):
    def test_static_escaped_full_metadata_and_no_external_dependencies(self):
        c=make_catalog([dict(gid=1,originalPosition=1,title='</script><script>alert(1)</script>',titleJpn='日本語')]);m=test_model(c)
        page=generate_review_page.generate(c,m,'a'*64)
        self.assertIn('&lt;/script&gt;',page);self.assertIn('日本語',page);self.assertIn('GID 1',page)
        self.assertNotIn('<script src=',page);self.assertNotIn('fetch(',page)

if __name__=='__main__':unittest.main()
