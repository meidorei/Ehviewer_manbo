import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "build_order.py"
SPEC = importlib.util.spec_from_file_location("build_order", SCRIPT)
BUILD_ORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD_ORDER)


def item(gid, position):
    return {"gid": gid, "originalPosition": position}


def decision(gid, series_id, item_order):
    return {
        "gid": gid,
        "canonicalSeriesId": series_id,
        "canonicalSeriesTitle": series_id,
        "branch": "main",
        "itemOrder": item_order,
        "confidence": 1.0,
        "reason": "test",
    }


class BuildStableOrderTest(unittest.TestCase):
    def build(self, items, decisions):
        catalog = {"formatVersion": 2, "snapshotFingerprint": "fingerprint", "items": items}
        source = {"formatVersion": 2, "snapshotFingerprint": "fingerprint", "decisions": decisions}
        return BUILD_ORDER.build_stable_order(catalog, source)

    def test_interleaved_series_use_first_member_anchor_and_newest_first(self):
        items = [item(gid, position) for position, gid in enumerate((10, 20, 30, 40, 50, 60, 70, 80), 1)]
        decisions = [
            decision(10, "single:10", 1),
            decision(20, "series:a", 1),
            decision(30, "single:30", 1),
            decision(40, "series:a", 3),
            decision(50, "series:b", 2),
            decision(60, "series:a", 2),
            decision(70, "series:b", 1),
            decision(80, "single:80", 1),
        ]

        result = self.build(items, decisions)

        self.assertEqual(
            ["single:10", "series:a", "single:30", "series:b", "single:80"],
            result["seriesOrder"],
        )
        self.assertEqual([10, 40, 60, 20, 30, 50, 70, 80], result["gidOrder"])
        self.assertEqual([10, 30, 80], [gid for gid in result["gidOrder"] if gid in {10, 30, 80}])
        self.assertEqual(set(range(10, 81, 10)), set(result["gidOrder"]))
        self.assertEqual(len(result["gidOrder"]), len(set(result["gidOrder"])))

    def test_tied_and_unknown_member_orders_keep_original_relative_order(self):
        items = [item(gid, position) for position, gid in enumerate((1, 2, 3, 4, 5, 6), 1)]
        decisions = [
            decision(1, "series:a", 2),
            decision(2, "series:a", 2),
            decision(3, "series:a", 0),
            decision(4, "series:a", -1),
            decision(5, "series:a", 4),
            decision(6, "series:a", 3),
        ]

        result = self.build(items, decisions)

        self.assertEqual([5, 6, 1, 2, 3, 4], result["gidOrder"])
        self.assertEqual(result["gidOrder"], [row["gid"] for row in result["decisions"]])

    def test_missing_or_duplicate_gid_is_rejected(self):
        items = [item(1, 1), item(2, 2)]
        decisions = [decision(1, "series:a", 1), decision(1, "series:a", 2)]

        with self.assertRaisesRegex(RuntimeError, "every catalog GID exactly once"):
            self.build(items, decisions)


if __name__ == "__main__":
    unittest.main()
