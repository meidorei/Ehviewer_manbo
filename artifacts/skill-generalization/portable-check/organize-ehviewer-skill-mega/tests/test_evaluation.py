import unittest
from pathlib import Path
from fixture_support import *
from evaluate import evaluate

class EvaluationTest(unittest.TestCase):
    def test_false_merges_missing_members_and_order_errors_are_separate(self):
        gold=dict(items=[dict(gid=1,expectedSeriesId='a'),dict(gid=2,expectedSeriesId='a'),dict(gid=3,expectedSeriesId='b')],orderedPairs=[[1,2]])
        out=evaluate(gold,dict(candidates=[dict(items=[dict(gid=1),dict(gid=2)])]),dict(decisions=[dict(gid=1,canonicalSeriesId='x'),dict(gid=2,canonicalSeriesId='y'),dict(gid=3,canonicalSeriesId='y')],gidOrder=[2,1,3]))
        self.assertEqual(1,out['candidatePairRecall']);self.assertEqual(1,out['falseMergePairs']);self.assertEqual(1,out['missedSeriesPairs']);self.assertEqual(1,out['orderingErrorCount'])
    def test_synthetic_gold_has_explicit_provenance(self):
        gold=read_json(Path(__file__).parent/'fixtures/multilingual-gold.json')
        self.assertEqual(18,len(gold['items']));self.assertIn('未经独立人工标注',gold['provenance'])
if __name__=='__main__':unittest.main()
