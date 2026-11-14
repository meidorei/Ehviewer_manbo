"""Replay actual EhDB/greenDAO calls captured by DownloadDirnameImportTest on SQLite.

Run that JUnit test first, then run this script with the existing Python runtime.
This tests production SQL/bindings and persisted results, not Android SQLiteDatabase.
The Java tests exercise the actual import decision and both concurrent lock orders.
"""
import json
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TRACES = ROOT / "app/build/reports/dirname-import"


class DownloadDirnameSqliteTest(unittest.TestCase):
    def replay(self, name, expected_writes):
        trace = json.loads((TRACES / (name + ".json")).read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory(prefix="dirname-import-test-") as directory:
            path = Path(directory) / "synthetic.db"
            with closing(sqlite3.connect(path)) as db:
                for sql in trace["schema"]:
                    db.execute(sql)
                db.executemany("INSERT INTO DOWNLOAD_DIRNAME VALUES (?,?)", trace["initial"])
                writes = 0
                for operation in trace["operations"]:
                    cursor = db.execute(operation["sql"], operation["args"])
                    if operation["kind"] == "query":
                        self.assertEqual(operation["result"], [list(row) for row in cursor.fetchall()],
                                         operation["sql"])
                    else:
                        writes += 1
                self.assertEqual(expected_writes, writes)
                expected = sorted(trace["expected"])
                self.assertEqual(expected, [list(row) for row in db.execute(
                    "SELECT GID,DIRNAME FROM DOWNLOAD_DIRNAME ORDER BY GID")])
                db.commit()
            # Closing/reopening checks persisted ownership, including the concurrent local writer.
            with closing(sqlite3.connect(path)) as reopened:
                self.assertEqual(expected, [list(row) for row in reopened.execute(
                    "SELECT GID,DIRNAME FROM DOWNLOAD_DIRNAME ORDER BY GID")])
            return dict(expected)

    def test_local_mapping_survives_and_only_empty_or_missing_rows_are_filled(self):
        self.assertEqual({1: "local-A", 2: "backup-B", 3: "backup-C", 4: " ",
                          5: "missing-on-this-device", 6: "backup-F"}, self.replay("merge", 3))

    def test_local_writer_after_import_keeps_the_new_local_mapping(self):
        self.assertEqual({42: "local-A"}, self.replay("import-first", 2))

    def test_import_after_local_writer_does_not_overwrite_its_mapping(self):
        self.assertEqual({42: "local-A"}, self.replay("local-first", 1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
