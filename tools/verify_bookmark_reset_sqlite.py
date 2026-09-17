"""Replay SQL emitted by BookmarkBaselineResetTest on host SQLite (no Android runtime).

Run the JUnit subscription tests (including FollowBaselineResetTest) first, then:
    python tools/verify_bookmark_reset_sqlite.py
Only temporary synthetic databases are written; no app/user database is opened.
"""
import json
import sqlite3
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRACE = ROOT / "app/build/reports/bookmark-reset/sql-trace.json"


def snapshot(db):
    tables = [row[0] for row in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    )]
    return {table: db.execute('SELECT * FROM "' + table + '" ORDER BY rowid').fetchall()
            for table in tables}


def seed(db, trace):
    for sql in trace["schema"]:
        db.execute(sql)
    db.execute("CREATE TABLE QUICK_SEARCH (_id INTEGER PRIMARY KEY, NAME TEXT, TIME INTEGER)")
    for write in trace["writes"]:
        if not write["sql"].startswith("INSERT"):
            continue
        args = write["args"]
        key, signature = args[2], args[3]
        db.execute("INSERT INTO QUICK_SEARCH VALUES (?, ?, 42)", (key, "kept " + key))
        db.execute("INSERT INTO LOCAL_UPDATE_STATE VALUES ('QUICK_SEARCH',?,?,42,'LOWER_BOUND',123,'kept error')",
                   (key, signature))
        for gid in range(1, 43):
            db.execute("INSERT INTO LOCAL_UNREAD_GALLERY VALUES ('QUICK_SEARCH',?,?,123)", (key, gid))
        for account in ["guest|e", "guest|ex", "user:a|e"]:
            db.execute("INSERT INTO FEED_CHECKPOINT(ACCOUNT_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,"
                       'PREVIOUS_TIME,"CURRENT_TIME",PREVIOUS_GIDS,CURRENT_GIDS,UPDATED_AT) '
                       "VALUES (?,'BOOKMARK_SYNC',?,?,20,30,'1','2',123)", (account, key, signature))
        db.execute("INSERT INTO FEED_CHECKPOINT(ACCOUNT_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,"
                   'PREVIOUS_TIME,"CURRENT_TIME",PREVIOUS_GIDS,CURRENT_GIDS,UPDATED_AT) '
                   "VALUES ('shared','BOOKMARK_OPEN',?,?,10,20,'3','4',123)", (key, signature))
        db.execute("INSERT INTO LOCAL_BASELINE_QUEUE(BATCH_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,"
                   "METHOD,ADDED_AT,STATUS) VALUES ('old','QUICK_SEARCH',?,?,'BOOKMARK',1,'PENDING')", (key, signature))
    db.execute("INSERT INTO LOCAL_FOLLOW_TAG VALUES ('artist:kept',1,123)")
    db.execute("INSERT INTO LOCAL_UNREAD_GALLERY VALUES ('LOCAL_FOLLOW','artist:kept',1,123)")
    db.execute("INSERT INTO LOCAL_BASELINE_QUEUE(BATCH_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,METHOD,"
               "ADDED_AT,STATUS) VALUES ('kept','LOCAL_FOLLOW','artist:kept','q','FOLLOW_TAG',1,'PENDING')")
    db.execute("INSERT INTO FEED_CHECKPOINT(ACCOUNT_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,UPDATED_AT) "
               "VALUES ('shared','HOME_MANUAL','home','',123)")
    for account in ["guest|e", "guest|ex", "user:a|e"]:
        for kind in ["BOOKMARK", "FOLLOW"]:
            db.execute("INSERT INTO LOCAL_GLOBAL_CURSOR VALUES (?,?,'q',1,'1',123)", (account, kind))
    db.execute("INSERT INTO LOCAL_REFRESH_META VALUES ('last_bookmark_success','123')")
    db.execute("INSERT INTO LOCAL_REFRESH_JOB(_id,JOB_TYPE,METHOD,STATUS) VALUES (1,'BOOKMARK','GLOBAL','SUCCESS')")
    db.commit()


def replay(db, writes):
    with db:
        for write in writes:
            db.execute(write["sql"], write["args"])


def verify_follow_reset(bookmark_trace):
    follow_trace = json.loads((ROOT / "app/build/reports/follow-reset/sql-trace.json").read_text(encoding="utf-8"))
    markers = [w["args"] for w in follow_trace["writes"] if w["sql"].startswith("INSERT")]

    def prepare(db):
        seed(db, bookmark_trace)
        # Replace the old follow sentinel with the exact list read by the production fixture.
        for table in ["LOCAL_UNREAD_GALLERY", "LOCAL_BASELINE_QUEUE"]:
            db.execute("DELETE FROM " + table + " WHERE SOURCE_TYPE='LOCAL_FOLLOW'")
        db.execute("DELETE FROM LOCAL_FOLLOW_TAG")
        for marker in markers:
            tag, signature = marker[2:4]
            db.execute("INSERT INTO LOCAL_FOLLOW_TAG VALUES (?,17,18)", (tag,))
            db.execute("INSERT INTO LOCAL_UPDATE_STATE VALUES ('LOCAL_FOLLOW',?,?,21,'LOWER_BOUND',18,'kept error')", (tag, signature))
            for gid in range(1, 22):
                db.execute("INSERT INTO LOCAL_UNREAD_GALLERY VALUES ('LOCAL_FOLLOW',?,?,18)", (tag, gid))
            for account, kind in [("guest|e", "LOCAL_FOLLOW_SYNC"), ("guest|ex", "LOCAL_FOLLOW_SYNC"), ("shared", "LOCAL_FOLLOW_OPEN")]:
                db.execute("INSERT INTO FEED_CHECKPOINT(ACCOUNT_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,"
                           'PREVIOUS_TIME,"CURRENT_TIME",PREVIOUS_GIDS,CURRENT_GIDS,UPDATED_AT) '
                           "VALUES (?,?,?,?,20,30,'1','2',18)", (account, kind, tag, signature))
            db.execute("INSERT INTO LOCAL_BASELINE_QUEUE(BATCH_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,METHOD,ADDED_AT) "
                       "VALUES ('old','LOCAL_FOLLOW',?,?,'FOLLOW_TAG',17)", (tag, signature))
        db.execute("INSERT INTO FEED_CHECKPOINT(ACCOUNT_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,UPDATED_AT) "
                   "VALUES ('guest','SUBSCRIPTION_AGGREGATE','watched','',18)")
        db.commit()

    with tempfile.TemporaryDirectory(prefix="follow-reset-test-") as directory:
        path = Path(directory) / "synthetic.db"
        db = sqlite3.connect(path)
        prepare(db)
        before = snapshot(db)
        replay(db, follow_trace["writes"])
        after = snapshot(db)
        for table in before.keys() - {"FEED_CHECKPOINT", "LOCAL_BASELINE_QUEUE", "LOCAL_GLOBAL_CURSOR"}:
            assert after[table] == before[table], table
        assert db.execute("SELECT * FROM FEED_CHECKPOINT WHERE SOURCE_TYPE<>'LOCAL_FOLLOW_RESET' ORDER BY rowid").fetchall() == before["FEED_CHECKPOINT"]
        assert db.execute("SELECT COUNT(*) FROM LOCAL_BASELINE_QUEUE WHERE SOURCE_TYPE='LOCAL_FOLLOW'").fetchone()[0] == 0
        assert db.execute("SELECT * FROM LOCAL_BASELINE_QUEUE ORDER BY rowid").fetchall() == [row for row in before["LOCAL_BASELINE_QUEUE"] if row[2] == "QUICK_SEARCH"]
        assert db.execute("SELECT * FROM LOCAL_GLOBAL_CURSOR ORDER BY rowid").fetchall() == [row for row in before["LOCAL_GLOBAL_CURSOR"] if row[1] == "BOOKMARK"]
        replay(db, follow_trace["writes"])
        assert db.execute("SELECT ACCOUNT_KEY,SOURCE_KEY,\"CURRENT_TIME\" FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='LOCAL_FOLLOW_RESET' ORDER BY SOURCE_KEY").fetchall() == [("shared", m[2], 101) for m in markers]
        db.close()
        db = sqlite3.connect(path)
        assert db.execute("SELECT COUNT(*) FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='LOCAL_FOLLOW_RESET'").fetchone()[0] == len(markers)
        replay(db, follow_trace["removal"])
        assert db.execute("SELECT COUNT(*) FROM FEED_CHECKPOINT WHERE SOURCE_TYPE LIKE 'LOCAL_FOLLOW%' AND SOURCE_KEY='artist:example'").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='LOCAL_FOLLOW_RESET' AND SOURCE_KEY='group:kept'").fetchone()[0] == 1
        db.execute("INSERT INTO LOCAL_FOLLOW_TAG VALUES ('artist:example',200000,0)")
        db.commit()
        assert db.execute("SELECT COUNT(*) FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='LOCAL_FOLLOW_RESET' AND SOURCE_KEY='artist:example'").fetchone()[0] == 0
        assert db.execute("SELECT * FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE='QUICK_SEARCH' ORDER BY rowid").fetchall() == [row for row in before["LOCAL_UNREAD_GALLERY"] if row[0] == "QUICK_SEARCH"]
        db.close()

        db = sqlite3.connect(":memory:")
        prepare(db)
        before = snapshot(db)
        db.execute("CREATE TRIGGER fail_reset BEFORE DELETE ON LOCAL_GLOBAL_CURSOR "
                   "WHEN OLD.JOB_TYPE='FOLLOW' BEGIN SELECT RAISE(ABORT,'injected failure'); END")
        try:
            replay(db, follow_trace["writes"])
            raise AssertionError("expected failure")
        except sqlite3.IntegrityError as error:
            assert "injected failure" in str(error)
        assert snapshot(db) == before
        db.close()
    print(json.dumps({"follow_checks": 7, "failures": 0,
                      "scenarios": ["preserved unread and history", "bookmark isolation", "repeat", "reopen",
                                    "removal and readd", "retained tag", "transaction rollback"]}))


def main():
    trace = json.loads(TRACE.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="bookmark-reset-test-") as directory:
        path = Path(directory) / "synthetic.db"
        db = sqlite3.connect(path)
        seed(db, trace)
        before = snapshot(db)
        replay(db, trace["writes"])
        after = snapshot(db)
        changed_tables = {"FEED_CHECKPOINT", "LOCAL_BASELINE_QUEUE", "LOCAL_GLOBAL_CURSOR"}
        for table in before.keys() - changed_tables:
            assert after[table] == before[table], table
        assert db.execute("SELECT * FROM FEED_CHECKPOINT WHERE SOURCE_TYPE<>'BOOKMARK_RESET' ORDER BY rowid").fetchall() == before["FEED_CHECKPOINT"]
        assert db.execute("SELECT ACCOUNT_KEY,SOURCE_KEY,\"CURRENT_TIME\" FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='BOOKMARK_RESET' ORDER BY SOURCE_KEY").fetchall() == [("shared", "7", 101), ("shared", "8", 101)]
        assert db.execute("SELECT SOURCE_TYPE FROM LOCAL_BASELINE_QUEUE").fetchall() == [("LOCAL_FOLLOW",)]
        assert db.execute("SELECT JOB_TYPE FROM LOCAL_GLOBAL_CURSOR").fetchall() == [("FOLLOW",)] * 3
        replay(db, trace["writes"])
        assert db.execute("SELECT COUNT(*) FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='BOOKMARK_RESET'").fetchone()[0] == 2
        db.close()
        db = sqlite3.connect(path)
        assert db.execute("SELECT MIN(\"CURRENT_TIME\") FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='BOOKMARK_RESET'").fetchone()[0] == 101
        db.close()

        db = sqlite3.connect(":memory:")
        seed(db, trace)
        before = snapshot(db)
        db.execute("CREATE TRIGGER fail_reset BEFORE DELETE ON LOCAL_GLOBAL_CURSOR "
                   "WHEN OLD.JOB_TYPE='BOOKMARK' BEGIN SELECT RAISE(ABORT,'injected failure'); END")
        try:
            replay(db, trace["writes"])
            raise AssertionError("expected failure")
        except sqlite3.IntegrityError as error:
            assert "injected failure" in str(error)
        assert snapshot(db) == before, "failed reset was not rolled back"
        db.close()
    verify_follow_reset(trace)
    print(json.dumps({"checks": 6, "failures": 0,
                      "scenarios": ["preserved data", "shared floor", "queue and cursor isolation",
                                    "repeat", "reopen", "transaction rollback"]}))


if __name__ == "__main__":
    main()
