"""Read-only SQLite inspection and consistent snapshots; no device operations."""
import hashlib
import sqlite3
from pathlib import Path
from common import canonical, make_catalog, require

class ClosingConnection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()


def connect(path):
    return sqlite3.connect(Path(path).resolve().as_uri()+'?mode=ro',uri=True,factory=ClosingConnection)

def quote(name):return '"'+name.replace('"','""')+'"'

def backup_database(source,target):
    target=Path(target)
    require(not target.exists(),'backup target exists')
    target.parent.mkdir(parents=True,exist_ok=True)
    with connect(source) as incoming:
        with target.open('xb'):
            pass
        with sqlite3.connect(target,factory=ClosingConnection) as outgoing:incoming.backup(outgoing)

def catalog_from_database(path,time_unit=None):
    with connect(path) as db:
        require(db.execute('PRAGMA integrity_check').fetchall()==[('ok',)],'database integrity check failed')
        columns={row[1].upper():row for row in db.execute('PRAGMA table_info(DOWNLOADS)')}
        require('GID' in columns and ('TITLE' in columns or 'TITLE_JPN' in columns),'unsupported DOWNLOADS structure: GID and a title column required')
        def col(name):return quote(columns[name][1]) if name in columns else 'NULL'
        order=col('TIME')+' DESC, '+col('GID')+' DESC' if 'TIME' in columns else col('GID')+' DESC'
        rows=db.execute('SELECT '+','.join(col(k) for k in ('GID','TITLE','TITLE_JPN'))+' FROM DOWNLOADS ORDER BY '+order).fetchall()
        items=[dict(gid=r[0],originalPosition=i,title=r[1],titleJpn=r[2]) for i,r in enumerate(rows,1)]
        supported=(time_unit=='milliseconds' and 'TIME' in columns and columns['TIME'][2].upper()=='INTEGER'
                   and columns['GID'][2].upper()=='INTEGER'
                   and not db.execute("SELECT 1 FROM DOWNLOADS WHERE typeof(GID)!='integer' OR typeof(TIME)!='integer' LIMIT 1").fetchone()) if 'TIME' in columns else False
        source=dict(kind='sqlite',timeUnit=time_unit,writebackSupported=bool(supported))
        if not supported:source['limitation']='TIME semantics or compatible integer columns unconfirmed; catalog only.'
        return make_catalog(items,source)

def logical_digest(path,ignore_download_time=True):
    h=hashlib.sha256()
    with connect(path) as db:
        require(db.execute('PRAGMA integrity_check').fetchall()==[('ok',)],'database integrity check failed')
        schema=db.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name').fetchall()
        h.update(canonical(schema).encode())
        h.update(canonical([db.execute('PRAGMA user_version').fetchone(),db.execute('PRAGMA application_id').fetchone()]).encode())
        for table, in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
            columns=[r[1] for r in db.execute('PRAGMA table_xinfo('+quote(table)+')') if r[6] != 1]
            if ignore_download_time and table.upper()=='DOWNLOADS':columns=[c for c in columns if c.upper()!='TIME']
            query='SELECT '+','.join(quote(c) for c in columns)+' FROM '+quote(table)
            def encode(row):
                return canonical([[type(v).__name__,v.hex() if isinstance(v,bytes) else v] for v in row])
            encoded=sorted(encode(row) for row in db.execute(query))
            h.update(canonical([table,columns,encoded]).encode())
    return h.hexdigest()

def ordered_rows(path):
    with connect(path) as db:return db.execute('SELECT GID,TIME FROM DOWNLOADS ORDER BY TIME DESC,GID DESC').fetchall()

def validate_database(actual,expected,catalog,order):
    fresh=catalog_from_database(actual,'milliseconds')
    require(fresh['source']['writebackSupported'],'database not compatible with millisecond TIME writeback')
    for key in ('snapshotFingerprint','metadataFingerprint'):require(fresh[key]==catalog[key],key+' changed in database')
    rows=ordered_rows(actual)
    require([r[0] for r in rows]==order['gidOrder'],'database order mismatch')
    require(len(set(r[1] for r in rows))==len(rows),'database timestamps not unique')
    require(all(rows[i][1]-rows[i+1][1]==1000 for i in range(len(rows)-1)),'database timestamps must descend in one-second steps')
    require(logical_digest(actual)==logical_digest(expected),'non-TIME fields, another table, or schema changed')
    require(rows==ordered_rows(expected),'database timestamps changed from verified copy')
    return fresh
