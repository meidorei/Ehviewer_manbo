"""Synthetic lightweight workflow tests; never use these fixtures on a real library."""
import copy
import tempfile
import unittest
from fixture_support import *
from test_v3_toolkit import run_script


class LightweightTest(unittest.TestCase):
    def result(self, catalog, source, gids=None, hash_='a'*64):
        gids = [x['gid'] for x in catalog['items']] if gids is None else gids
        return dict(formatVersion=3, snapshotFingerprint=catalog['snapshotFingerprint'], metadataFingerprint=catalog['metadataFingerprint'],
                    inputArtifactDigest=hash_, decisions=[copy.deepcopy(r) for r in source['decisions'] if r['gid'] in gids],
                    reviewedGids=gids, conflicts=[], summary='Synthetic semantic/focused result, not real-library review.')

    def test_default_cli_single_pass_build_and_selected_prepare(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);c=test_catalog(80);write_json(root/'catalog.json',c)
            args=['--catalog',root/'catalog.json']
            r=run_script('audit_round','init',*args,'--output',root/'initial.json');self.assertEqual(0,r.returncode,r.stderr)
            source=read_json(root/'initial.json');self.assertEqual('lightweight',source['auditMode'])
            r=run_script('audit_round','prepare',*args,'--input',root/'initial.json','--output',root/'prepared.json');self.assertEqual(0,r.returncode,r.stderr)
            prepared=read_json(root/'prepared.json');self.assertEqual('semantic',prepared['round']);self.assertEqual([75,5],[len(b['items']) for b in prepared['batches']])
            out=self.result(c,source,hash_=sha256_file(root/'initial.json'));write_json(root/'result.json',out)
            r=run_script('audit_round','record',*args,'--input',root/'initial.json','--result',root/'result.json','--round','semantic','--output',root/'reviewed.json');self.assertEqual(0,r.returncode,r.stderr)
            r=run_script('build_order',*args,'--decisions',root/'reviewed.json','--output',root/'model.json');self.assertEqual(0,r.returncode,r.stderr)
            m=read_json(root/'model.json');validate_order(c,m);self.assertEqual(1,len(m['auditTrail']))
            r=run_script('audit_round','prepare',*args,'--input',root/'reviewed.json','--gids','3','7','--output',root/'focused.json');self.assertEqual(0,r.returncode,r.stderr)
            f=read_json(root/'focused.json');self.assertEqual([3,7],f['reviewGids']);self.assertEqual(2,sum(len(b['items']) for b in f['batches']))
            r=run_script('audit_round','prepare',*args,'--input',root/'reviewed.json','--output',root/'unbounded.json');self.assertNotEqual(0,r.returncode)

    def test_focused_patch_preserves_all_unreviewed_rows(self):
        c=test_catalog(4);m=test_model(c,mode='lightweight');result=self.result(c,m,[2])
        result['decisions'][0]['reason']='Synthetic correction after checking chapter marker.'
        out=record(c,m,result,'focused','a'*64)
        self.assertEqual([2],out['auditTrail'][-1]['reviewedGids']);self.assertEqual([2],out['auditTrail'][-1]['changedGids'])
        self.assertEqual([r for r in m['decisions'] if r['gid']!=2],[r for r in out['decisions'] if r['gid']!=2])
        from build_order import build_stable_order
        validate_order(c,build_stable_order(c,out))

    def test_partial_first_pass_and_fake_focused_coverage_rejected(self):
        c=test_catalog(3);source=envelope(c,[singleton(x) for x in c['items']]);source['auditMode']='lightweight'
        with self.assertRaises(ValueError):record(c,source,self.result(c,source,[1]),'semantic','a'*64)
        m=test_model(c,mode='lightweight');result=self.result(c,m,[1]);result['decisions'].append(copy.deepcopy(m['decisions'][1]))
        with self.assertRaises(ValueError):record(c,m,result,'focused','a'*64)
        with self.assertRaises(ValueError):record(c,m,self.result(c,m,[]),'focused','a'*64)

    def test_focused_cannot_rename_half_a_series(self):
        c=test_catalog(2);m=test_model(c,mode='lightweight');result=self.result(c,m,[1]);result['decisions'][0]['canonicalSeriesTitle']='Wrong partial rename'
        with self.assertRaises(ValueError):record(c,m,result,'focused','a'*64)

    def test_modes_chain_and_input_tampering_rejected(self):
        c=test_catalog(2);m=test_model(c,mode='lightweight')
        for mode in ('deep','bogus',None):
            bad=copy.deepcopy(m);bad['auditMode']=mode
            with self.subTest(mode=mode),self.assertRaises(ValueError):validate_order(c,bad)
        legacy=test_model(c);legacy['auditMode']='lightweight'
        with self.assertRaises(ValueError):validate_order(c,legacy)
        result=self.result(c,m,[1]);result['inputArtifactDigest']='b'*64
        with self.assertRaises(ValueError):record(c,m,result,'focused','a'*64)
        out=record(c,m,self.result(c,m,[1]),'focused','a'*64);out['decisions'][1]['reason']='unlogged'
        with self.assertRaises(ValueError):validate_audits(c,out)

    def test_lightweight_human_replay_still_requires_real_confirmed_payload(self):
        c=test_catalog(2);m=test_model(c,mode='lightweight');e=confirmed_export(c,m,'a'*64)
        validate_order(c,e,m,'a'*64,True)
        for field,value in (('auditMode','deep'),('reviewStatus','draft'),('baseModelDigest','b'*64)):
            bad=copy.deepcopy(e);bad[field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):validate_order(c,bad,m,'a'*64,True)
        pending={**m,'baseModelDigest':'a'*64,'operations':[],'reviewStatus':'confirmed'}
        with self.assertRaises(ValueError):validate_order(c,pending,m,'a'*64,True)

    def test_seed_preserves_decisions_without_fabricating_completion(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);c=test_catalog(2);m=test_model(c);write_json(p/'c.json',c);write_json(p/'old.json',m)
            r=run_script('audit_round','init','--catalog',p/'c.json','--input',p/'old.json','--output',p/'seed.json');self.assertEqual(0,r.returncode,r.stderr)
            seed=read_json(p/'seed.json');self.assertEqual([],seed['auditTrail']);self.assertEqual('lightweight',seed['auditMode']);self.assertEqual(m['decisions'],seed['decisions']);self.assertEqual(sha256_file(p/'old.json'),seed['seedSourceDigest'])
            with self.assertRaises(ValueError):validate_audits(c,seed)

    def test_title_only_recall_without_fabricated_discovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);c=test_catalog(3);write_json(p/'c.json',c)
            r=run_script('generate_candidates','--catalog',p/'c.json','--candidates',p/'out.json','--author-buckets',p/'authors.json','--unresolved',p/'u.json');self.assertEqual(0,r.returncode,r.stderr)
            out=read_json(p/'out.json');self.assertEqual('titles-only',out['recallSource']);self.assertGreater(out['candidateCount'],0)
            self.assertTrue(all(item['derived']['discovery']=={} for b in out['candidates'] for item in b['items']))

    def test_manifest_init_without_discovery_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);write_json(p/'catalog.json',test_catalog(2))
            args=['init','--catalog',p/'catalog.json','--manifest',p/'run-manifest.json']
            r=run_script('run_manifest',*args);self.assertEqual(0,r.returncode,r.stderr)
            manifest=read_json(p/'run-manifest.json');self.assertEqual(False,manifest['discoveryPerformed']);self.assertEqual([],manifest['batches'])
            self.assertEqual([{'path':'catalog.json','retention':'preserve'}],manifest['artifacts'])
            original=(p/'run-manifest.json').read_bytes();r=run_script('run_manifest',*args);self.assertNotEqual(0,r.returncode)
            self.assertEqual(original,(p/'run-manifest.json').read_bytes())

    def test_focused_batch_merge_and_real_subset_coverage(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);c=test_catalog(3);m=test_model(c,mode='lightweight');write_json(p/'c.json',c);write_json(p/'m.json',m)
            results=p/'results';results.mkdir()
            for gid in [1,3]:
                result=self.result(c,m,[gid],sha256_file(p/'m.json'));result['decisions'][0]['reason']='Synthetic reviewed item '+str(gid);write_json(results/(str(gid)+'.json'),result)
            r=run_script('audit_round','merge','--catalog',p/'c.json','--input',p/'m.json','--results-dir',results,'--output',p/'out.json');self.assertEqual(0,r.returncode,r.stderr)
            out=read_json(p/'out.json');self.assertEqual([1,3],out['auditTrail'][-1]['reviewedGids']);self.assertEqual(m['decisions'][1],out['decisions'][1])

    def test_empty_catalog_single_pass(self):
        c=test_catalog(0);m=test_model(c,mode='lightweight');validate_order(c,m)
        self.assertEqual([],m['gidOrder'])


if __name__=='__main__':unittest.main()
