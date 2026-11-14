"""Run production follow-history SQL against SQLite using only Python's standard library.

This validates SQL and upgrade wiring, not Android SQLiteDatabase execution.
"""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/hippo/ehviewer'
SCHEMA = (JAVA / 'subscription/SubscriptionSchema.java').read_text(encoding='utf-8')
REPOSITORY = (JAVA / 'subscription/LocalFollowUpdatesRepository.java').read_text(encoding='utf-8')


def constant(source, name):
    match = re.search(r'\bString\s+' + name + r'\s*=\s*(.*?);', source, re.S)
    if not match:
        raise AssertionError(f'Missing production SQL constant: {name}')
    return ''.join(json.loads(part) for part in re.findall(r'"(?:\\.|[^"\\])*"', match[1]))


CREATE = constant(SCHEMA, 'CREATE_FOLLOW_UPDATES_TABLE')
INDEX = constant(SCHEMA, 'CREATE_FOLLOW_UPDATES_INDEX')
INSERT = constant(REPOSITORY, 'INSERT_SQL')
UPDATE = constant(REPOSITORY, 'UPDATE_SQL')
READ = constant(REPOSITORY, 'MARK_READ_SQL')
PRUNE = constant(REPOSITORY, 'PRUNE_SQL')
LIST = constant(REPOSITORY, 'LIST_SQL')
COUNT = constant(REPOSITORY, 'COUNT_SQL')
CAPACITY = int(re.search(r'CAPACITY\s*=\s*(\d+)', REPOSITORY)[1])


class FollowUpdatesSqliteTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute(CREATE)
        self.db.execute(INDEX)

    def tearDown(self):
        self.db.close()

    def put(self, gid, first_seen, tags, title='title'):
        old = self.db.execute('SELECT MATCHED_TAGS_JSON FROM LOCAL_FOLLOW_UPDATES WHERE GID=?',
                              (gid,)).fetchone()
        merged = sorted(set(json.loads(old[0]) if old else []) | set(tags))
        snapshot = json.dumps({'gid': gid, 'title': title})
        tags_json = json.dumps(merged)
        self.db.execute(INSERT, (gid, first_seen, snapshot, tags_json))
        self.db.execute(UPDATE, (snapshot, tags_json, gid))

    def list_gids(self, unread=False, offset=0, limit=50):
        return [json.loads(row[0])['gid'] for row in
                self.db.execute(LIST, ('1' if unread else '0', str(limit), str(offset)))]

    def test_upgrade_is_additive_idempotent_and_new_history_is_empty(self):
        old_tables = {
            'DOWNLOADS': 'GID INTEGER, ARCHIVE_URI TEXT',
            'QUICK_SEARCH': '_id INTEGER, NAME TEXT',
            'LOCAL_FOLLOW_TAG': 'TAG_NAME TEXT',
            'LOCAL_UNREAD_GALLERY': 'GID INTEGER, SOURCE_TYPE TEXT',
            'LOCAL_UPDATE_STATE': 'SOURCE_TYPE TEXT, COUNT INTEGER',
            'FEED_CHECKPOINT': 'CURRENT_TIME INTEGER',
            'READING_QUEUE': 'GID INTEGER, CURRENT_PAGE INTEGER',
        }
        before = {}
        for table, columns in old_tables.items():
            self.db.execute(f'CREATE TABLE {table} ({columns})')
            size = len(columns.split(','))
            self.db.execute(f'INSERT INTO {table} VALUES({",".join("?" * size)})',
                            tuple(range(1, size + 1)))
            before[table] = self.db.execute(f'SELECT * FROM {table}').fetchall()
        self.db.execute('DROP TABLE LOCAL_FOLLOW_UPDATES')
        for _ in range(2):
            self.db.execute(CREATE)
            self.db.execute(INDEX)
        for table, expected in before.items():
            self.assertEqual(expected, self.db.execute(f'SELECT * FROM {table}').fetchall())
        self.assertEqual([], self.list_gids())

    def test_duplicate_merges_tags_and_refreshes_snapshot_without_resetting_read_or_first_seen(self):
        self.put(42, 100, ['artist:a'])
        self.db.execute(READ, (150, 42))
        self.put(42, 200, ['group:b', 'artist:a'], 'new title')
        self.db.execute(READ, (250, 42))
        row = self.db.execute('SELECT FIRST_SEEN_AT,READ_AT,GALLERY_JSON,MATCHED_TAGS_JSON '
                              'FROM LOCAL_FOLLOW_UPDATES WHERE GID=42').fetchone()
        self.assertEqual((100, 150), row[:2])
        self.assertEqual('new title', json.loads(row[2])['title'])
        self.assertEqual(['artist:a', 'group:b'], json.loads(row[3]))
        self.assertEqual([], self.list_gids(unread=True))
        self.assertEqual([42], self.list_gids())
        self.assertEqual(1, self.db.execute(COUNT, ('0',)).fetchone()[0])
        self.assertEqual(0, self.db.execute(COUNT, ('1',)).fetchone()[0])

    def test_mark_read_is_noop_for_absent_gid_and_does_not_change_original_unread(self):
        self.db.execute('CREATE TABLE LOCAL_UNREAD_GALLERY(GID INTEGER)')
        self.db.execute('INSERT INTO LOCAL_UNREAD_GALLERY VALUES(42)')
        self.put(42, 100, ['artist:a'])
        self.db.execute(READ, (200, 42))
        self.db.execute(READ, (200, 99))
        self.assertEqual([42], self.list_gids())
        self.assertEqual([(42,)], self.db.execute('SELECT * FROM LOCAL_UNREAD_GALLERY').fetchall())

    def test_capacity_and_pages_share_stable_discovery_order(self):
        for gid in range(1, CAPACITY + 26):
            self.put(gid, 100 + gid // 100, ['artist:a'])
        self.db.execute(PRUNE, (CAPACITY,))
        expected = list(range(CAPACITY + 25, 25, -1))
        self.assertEqual(CAPACITY, self.db.execute('SELECT COUNT(*) FROM LOCAL_FOLLOW_UPDATES').fetchone()[0])
        combined = []
        for offset in range(0, CAPACITY, 50):
            combined.extend(self.list_gids(offset=offset))
        self.assertEqual(expected, combined)
        self.assertEqual([], self.list_gids(offset=CAPACITY))
        self.db.execute(READ, (999, expected[0]))
        self.assertEqual(expected[1:51], self.list_gids(unread=True))

    def test_more_than_21_history_entries_survive_counter_trim_and_counter_clear(self):
        self.db.execute('CREATE TABLE LOCAL_UNREAD_GALLERY(GID INTEGER)')
        for gid in range(1, 52):
            self.put(gid, 100, ['artist:a'])
            self.db.execute('INSERT INTO LOCAL_UNREAD_GALLERY VALUES(?)', (gid,))
        self.db.execute('DELETE FROM LOCAL_UNREAD_GALLERY WHERE GID NOT IN '
                        '(SELECT GID FROM LOCAL_UNREAD_GALLERY ORDER BY GID DESC LIMIT 21)')
        self.assertEqual(51, len(self.list_gids(limit=100)))
        self.db.execute('DELETE FROM LOCAL_UNREAD_GALLERY')
        self.assertEqual(51, len(self.list_gids(limit=100)))

    def test_history_rolls_back_with_checkpoint_and_unread_transaction(self):
        self.db.execute('CREATE TABLE FEED_CHECKPOINT(CURRENT_TIME INTEGER)')
        self.db.execute('CREATE TABLE LOCAL_UNREAD_GALLERY(GID INTEGER)')
        self.db.execute('INSERT INTO FEED_CHECKPOINT VALUES(100)')
        self.db.commit()
        with self.assertRaises(sqlite3.OperationalError):
            with self.db:
                self.db.execute('UPDATE FEED_CHECKPOINT SET CURRENT_TIME=200')
                self.put(42, 200, ['artist:a'])
                self.db.execute('INSERT INTO LOCAL_UNREAD_GALLERY VALUES(42)')
                self.db.execute('INSERT INTO missing_table VALUES(1)')
        self.assertEqual([], self.list_gids())
        self.assertEqual([], self.db.execute('SELECT * FROM LOCAL_UNREAD_GALLERY').fetchall())
        self.assertEqual(100, self.db.execute('SELECT * FROM FEED_CHECKPOINT').fetchone()[0])

    def test_upgrade_import_and_export_keep_local_history_separate(self):
        source = (JAVA / 'EhDB.java').read_text(encoding='utf-8')
        self.assertRegex(source, r'case 14:[^\n]*\n\s*SubscriptionSchema.upgradeToV15\(db\)')
        self.assertGreaterEqual(source.count('upgradeDB(db, oldVersion)'), 2)
        self.assertIn('SCHEMA_VERSION = 15', (JAVA / 'dao/DaoMaster.java').read_text(encoding='utf-8'))
        self.assertIn('CREATE_FOLLOW_UPDATES_TABLE,', SCHEMA)
        self.assertIn('DROP TABLE IF EXISTS LOCAL_FOLLOW_UPDATES', SCHEMA)
        # Export/import selectively copy DAO content and existing local tag/queue data.
        self.assertNotIn('LOCAL_FOLLOW_UPDATES', source)
        local = (JAVA / 'subscription/LocalFollowRepository.java').read_text(encoding='utf-8')
        self.assertIn('SOURCE_FOLLOW.equals(sourceType) && !baseline', local)
        commit = local[local.index('private TagUpdateState commitDelta('):]
        self.assertLess(commit.index('.recordNew('), commit.index('LIMIT 21'))
        self.assertLess(commit.index('db.beginTransaction()'), commit.index('.recordNew('))
        self.assertLess(commit.index('.recordNew('), commit.index('db.setTransactionSuccessful()'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
