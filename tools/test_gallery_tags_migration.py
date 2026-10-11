"""SQLite checks for upstream LOCATION migration; uses only Python's standard library.

Run from the repository with: python tools/test_gallery_tags_migration.py
Android helper wiring is inspected separately; this does not run Android SQLiteDatabase.
"""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/hippo/ehviewer'


def java_strings(source):
    return ''.join(json.loads(s) for s in re.findall(r'"(?:\\.|[^"\\])*"', source))


def create_sql(path):
    source = path.read_text(encoding='utf-8')
    start = source.index('db.execSQL("CREATE TABLE')
    end = source.index('\n    }', start)
    return java_strings(source[start:end])


class GalleryTagsMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.fresh = create_sql(JAVA / 'dao/GalleryTagsDao.java')
        helper = (JAVA / 'dao/GalleryTagsSchema.java').read_text(encoding='utf-8')
        self.alter = java_strings(re.search(r'db.execSQL\((.*?)\);', helper, re.S)[1])

    def tearDown(self):
        self.db.close()

    def migrate(self):
        columns = [r[1].upper() for r in self.db.execute('PRAGMA table_info("Gallery_Tags")')]
        if 'LOCATION' not in columns:
            self.db.execute(self.alter)

    def test_v13_upgrade_preserves_tags_downloads_bookmarks_and_queue(self):
        old = self.fresh.replace(',"LOCATION" TEXT', '')
        self.db.execute(old)
        self.db.execute('INSERT INTO Gallery_Tags(GID,ARTIST,CREATE_TIME,UPDATE_TIME) VALUES(42,?,?,?)',
                        ('kept artist', 123, 456))
        fixtures = {
            'DOWNLOADS': 'GID INTEGER, TIME INTEGER, ARCHIVE_URI TEXT',
            'QUICK_SEARCH': '_id INTEGER, NAME TEXT, KEYWORD TEXT',
            'LOCAL_FOLLOW_TAG': 'TAG TEXT',
            'FEED_CHECKPOINT': 'SOURCE_TYPE TEXT, SOURCE_KEY TEXT',
            'LOCAL_UNREAD_GALLERY': 'GID INTEGER, SOURCE_TYPE TEXT',
            'READING_QUEUE': 'GID INTEGER, QUEUE_ORDER INTEGER, CURRENT_PAGE INTEGER, TOTAL_PAGES INTEGER',
        }
        before = {}
        for table, columns in fixtures.items():
            self.db.execute(f'CREATE TABLE {table} ({columns})')
            count = len(columns.split(','))
            self.db.execute(f'INSERT INTO {table} VALUES ({",".join("?" * count)})',
                            tuple(range(1, count + 1)))
            before[table] = self.db.execute(f'SELECT * FROM {table}').fetchall()
        self.migrate()
        self.assertEqual((42, 'kept artist', 123, 456, None), self.db.execute(
            'SELECT GID,ARTIST,CREATE_TIME,UPDATE_TIME,LOCATION FROM Gallery_Tags').fetchone())
        for table, rows in before.items():
            self.assertEqual(rows, self.db.execute(f'SELECT * FROM {table}').fetchall())
        migrated = list(self.db.execute('PRAGMA table_info(Gallery_Tags)'))
        fresh_db = sqlite3.connect(':memory:')
        try:
            fresh_db.execute(self.fresh)
            self.assertEqual(migrated, list(fresh_db.execute('PRAGMA table_info(Gallery_Tags)')))
        finally:
            fresh_db.close()

    def test_upstream_backup_with_location_is_preserved_and_repeat_is_safe(self):
        self.db.execute(self.fresh)
        self.db.execute('INSERT INTO Gallery_Tags(GID,LOCATION) VALUES(42,?)', ('school',))
        self.migrate()
        self.migrate()
        self.assertEqual('school', self.db.execute('SELECT LOCATION FROM Gallery_Tags').fetchone()[0])
        restored = sqlite3.connect(':memory:')
        try:
            self.db.commit()
            self.db.backup(restored)
            self.assertEqual('school', restored.execute('SELECT LOCATION FROM Gallery_Tags').fetchone()[0])
            self.assertEqual('ok', restored.execute('PRAGMA integrity_check').fetchone()[0])
        finally:
            restored.close()

    def test_app_and_import_upgrade_share_v14_migration(self):
        source = (JAVA / 'EhDB.java').read_text(encoding='utf-8')
        self.assertRegex(source, r'case 13:[^\n]*\n\s*GalleryTagsSchema.upgradeToV14\(db\)')
        self.assertGreaterEqual(source.count('upgradeDB(db, oldVersion)'), 2)
        self.assertIn('SCHEMA_VERSION = 14', (JAVA / 'dao/DaoMaster.java').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
