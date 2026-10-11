import copy
import json
import tempfile
import unittest
from fixture_support import *
from test_v3_toolkit import run_script

class AuditCliTest(unittest.TestCase):
    def test_prepare_merge_all_four_rounds_and_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);c=test_catalog(3);write_json(root/'catalog.json',c)
            args=['--catalog',root/'catalog.json']
            self.assertEqual(0,run_script('audit_round','init','--mode','deep',*args,'--output',root/'audit0.json').returncode)
            for i in range(4):
                input_path=root/f'audit{i}.json';source=read_json(input_path);result_dir=root/f'results{i}';result_dir.mkdir()
                prepared=root/f'prepared{i}.json';r=run_script('audit_round','prepare',*args,'--input',input_path,'--output',prepared);self.assertEqual(0,r.returncode,r.stderr)
                self.assertEqual(3,len(read_json(prepared)['unresolvedGids']))
                for j,gids in enumerate(([1,2],[2,3])):
                    rows=[copy.deepcopy(row) for row in source['decisions'] if row['gid'] in gids]
                    output={**envelope(c,rows),'reviewedGids':gids,'inputArtifactDigest':sha256_file(input_path),'conflicts':[],'summary':'Synthetic unchanged decisions for CLI coverage.'}
                    write_json(result_dir/f'{j}.json',output)
                r=run_script('audit_round','merge',*args,'--input',input_path,'--results-dir',result_dir,'--output',root/f'audit{i+1}.json');self.assertEqual(0,r.returncode,r.stderr)
            r=run_script('build_order',*args,'--decisions',root/'audit4.json','--output',root/'model.json');self.assertEqual(0,r.returncode,r.stderr)
            validate_order(c,read_json(root/'model.json'))
    def test_overlap_conflicts_do_not_pick_a_winner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);c=test_catalog(1);source=envelope(c,[singleton(c['items'][0])]);write_json(root/'catalog.json',c);write_json(root/'input.json',source);results=root/'results';results.mkdir()
            output={**source,'reviewedGids':[1],'inputArtifactDigest':sha256_file(root/'input.json'),'conflicts':[],'summary':'fixture'}
            write_json(results/'1.json',output);output['decisions'][0]['reason']='conflicting result';write_json(results/'2.json',output)
            r=run_script('audit_round','merge','--catalog',root/'catalog.json','--input',root/'input.json','--results-dir',results,'--output',root/'out.json')
            self.assertNotEqual(0,r.returncode);self.assertIn('conflicting',r.stderr);self.assertFalse((root/'out.json').exists())
    def test_empty_discovery_validation(self):
        from build_discovery_batches import build_batches
        from validate_discovery import validate
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);c=test_catalog(0);build_batches(c,root,root/'run-manifest.json');annotations,errors=validate(root/'run-manifest.json',root)
            self.assertEqual([],annotations);self.assertEqual([],errors)

if __name__=='__main__':unittest.main()
