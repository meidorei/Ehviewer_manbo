import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import importlib.util
import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "apply_order.py"
SPEC = importlib.util.spec_from_file_location("apply_order", SCRIPT)
APPLY_ORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(APPLY_ORDER)


class AssignCurrentTimeSlotsTest(unittest.TestCase):
    def test_reorders_from_current_time_in_one_second_steps(self):
        assignments = APPLY_ORDER.assign_current_time_slots(
            [10, 20, 30],
            [30, 10, 20],
            1_700_000_002_000,
        )

        self.assertEqual(
            [(30, 1_700_000_002_000), (10, 1_700_000_001_000), (20, 1_700_000_000_000)],
            assignments,
        )

    def test_duplicate_source_times_do_not_block_current_time_sorting(self):
        assignments = APPLY_ORDER.assign_current_time_slots([10, 20], [20, 10], 1_700_000_000_000)

        self.assertEqual([(20, 1_700_000_000_000), (10, 1_699_999_999_000)], assignments)

    def test_missing_or_duplicate_requested_gid_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "every source GID exactly once"):
            APPLY_ORDER.assign_current_time_slots([10, 20], [10, 10], 1_700_000_000_000)

    def test_command_uses_current_time_sequence_without_changing_other_columns(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.db"
            backup = root / "backup.db"
            output = root / "sorted.db"
            report = root / "report.json"
            order = root / "order.json"
            db = sqlite3.connect(source)
            try:
                db.execute("CREATE TABLE DOWNLOADS (GID INTEGER PRIMARY KEY, TITLE TEXT, STATE INTEGER, TIME INTEGER NOT NULL)")
                db.executemany(
                    "INSERT INTO DOWNLOADS (GID, TITLE, STATE, TIME) VALUES (?, ?, ?, ?)",
                    [(10, "first", 1, 1_700_000_002_000), (20, "second", 2, 1_700_000_002_000), (30, "third", 3, 1_700_000_000_000)],
                )
                db.commit()
            finally:
                db.close()
            from database import catalog_from_database
            from fixture_support import test_model, confirmed_export
            from common import write_json, sha256_file
            catalog_path=root / "catalog.json";model_path=root / "model.json"
            catalog=catalog_from_database(source,"milliseconds");model=test_model(catalog)
            write_json(catalog_path,catalog);write_json(model_path,model)
            confirmed=confirmed_export(catalog,model,sha256_file(model_path),[dict(type="moveItem",gid=10,before=20),dict(type="pin",gid=30)])
            fingerprint = hashlib.sha256("10\n20\n30".encode("utf-8")).hexdigest()
            order.write_text(json.dumps(confirmed), encoding="utf-8")

            before = time.time_ns() // 1_000_000
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--db", str(source), "--catalog", str(catalog_path), "--model", str(model_path), "--order", str(order), "--backup", str(backup), "--output", str(output), "--report", str(report)],
                capture_output=True,
                text=True,
            )
            after = time.time_ns() // 1_000_000

            self.assertEqual(0, result.returncode, result.stderr)
            verify = sqlite3.connect(f"file:{output}?mode=ro", uri=True)
            try:
                self.assertEqual([30, 10, 20], [row[0] for row in verify.execute("SELECT GID FROM DOWNLOADS ORDER BY TIME DESC")])
                times = [row[0] for row in verify.execute("SELECT TIME FROM DOWNLOADS ORDER BY TIME DESC")]
                self.assertEqual(1_000, times[0] - times[1])
                self.assertEqual(1_000, times[1] - times[2])
                self.assertGreaterEqual(times[0], before)
                self.assertLessEqual(times[0], after)
                self.assertEqual(
                    [(10, "first", 1), (20, "second", 2), (30, "third", 3)],
                    list(verify.execute("SELECT GID, TITLE, STATE FROM DOWNLOADS ORDER BY GID")),
                )
            finally:
                verify.close()
            report_data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual("current-time-descending-seconds", report_data["timeStrategy"])
            self.assertEqual(1_000, report_data["timeStepMillis"])


if __name__ == "__main__":
    unittest.main()
