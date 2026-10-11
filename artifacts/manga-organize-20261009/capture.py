"""Copy the stopped Debug application's SQLite database, including sidecars."""
import hashlib
import json
from pathlib import Path
import subprocess

ADB = r'C:\Users\21555\AppData\Local\Android\Sdk\platform-tools\adb.exe'
SERIAL = '5da3329'
PACKAGE = 'com.ehviewer.manbo.debug'
OUT = Path(__file__).parent / 'raw'

def adb(*args):
    return subprocess.check_output([ADB, '-s', SERIAL, *args])

OUT.mkdir(parents=True, exist_ok=False)
adb('shell', 'am', 'force-stop', PACKAGE)
files = adb('shell', 'run-as', PACKAGE, 'ls', 'databases').decode().splitlines()
selected = [x for x in files if x in ('eh.db', 'eh.db-journal', 'eh.db-wal', 'eh.db-shm')]
assert 'eh.db' in selected
metadata = {}
try:
    for name in selected:
        data = adb('exec-out', 'run-as', PACKAGE, 'cat', 'databases/' + name)
        (OUT / name).write_bytes(data)
        metadata[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
finally:
    adb('shell', 'am', 'start', '-n', PACKAGE + '/com.hippo.ehviewer.ui.MainActivity')
(OUT.parent / 'capture.json').write_text(json.dumps({'device': SERIAL, 'package': PACKAGE,
    'capturedAtLocal': '2026-10-09', 'consistentSnapshot': 'Debug app force-stopped; main database and all present SQLite sidecars copied before relaunch',
    'files': metadata}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(metadata))
