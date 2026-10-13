"""Capture the current authorized Debug library without modifying its contents."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime

ADB = r'C:\Users\21555\AppData\Local\Android\Sdk\platform-tools\adb.exe'
PACKAGE = 'com.ehviewer.manbo.debug'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
args = parser.parse_args()
run = Path(__file__).resolve().parent
out = run / 'raw'
out.mkdir(exist_ok=False)

def adb(*command):
    return subprocess.check_output([ADB, '-s', args.serial, *command], timeout=30)

version = adb('shell', 'dumpsys', 'package', PACKAGE).decode('utf-8')
version = [line.strip() for line in version.splitlines()
           if 'versionCode=' in line or 'versionName=' in line]
adb('shell', 'am', 'force-stop', PACKAGE)
files = {}
try:
    names = adb('shell', 'run-as', PACKAGE, 'ls', 'databases').decode().splitlines()
    assert 'eh.db' in names
    for name in ('eh.db', 'eh.db-journal', 'eh.db-wal', 'eh.db-shm'):
        if name in names:
            data = adb('exec-out', 'run-as', PACKAGE, 'cat', 'databases/' + name)
            (out / name).write_bytes(data)
            files[name] = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
finally:
    launch = adb('shell', 'am', 'start', '-n', PACKAGE + '/com.hippo.ehviewer.ui.MainActivity').decode()
record = dict(device=args.serial, package=PACKAGE, version=version,
              capturedAt=datetime.now().astimezone().isoformat(), files=files,
              captureMethod='Force-stop Debug app; copy main DB and all present sidecars; relaunch',
              launch=launch)
(run / 'capture.json').write_text(json.dumps(record, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps(record, ensure_ascii=False))
