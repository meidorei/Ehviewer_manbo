"""Read a stopped Debug database and every present SQLite sidecar; never write it."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
from datetime import datetime, timezone, timedelta

ADB = r'C:\Users\21555\AppData\Local\Android\Sdk\platform-tools\adb.exe'
PACKAGE = 'com.ehviewer.manbo.debug'
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--out', type=Path)
a = p.parse_args()
out = a.out or Path(__file__).resolve().parent
out.mkdir(parents=True, exist_ok=True)
raw = out / 'raw'
raw.mkdir(exist_ok=False)

def adb(*args, check=True):
    return subprocess.run([ADB, '-s', a.serial, *args], check=check,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)

devices = subprocess.check_output([ADB, 'devices', '-l'], timeout=20).decode()
assert any(line.split()[:2] == [a.serial, 'device'] for line in devices.splitlines())
version = adb('shell', 'dumpsys', 'package', PACKAGE).stdout.decode()
version = [line.strip() for line in version.splitlines()
           if 'versionCode=' in line or 'versionName=' in line]
assert len(version) == 2, 'Debug package version unavailable'
adb('shell', 'run-as', PACKAGE, 'pwd')
adb('shell', 'am', 'force-stop', PACKAGE)
record = dict(device=a.serial, package=PACKAGE, version=version,
              capturedAt=datetime.now(timezone(timedelta(hours=8))).isoformat(),
              method='Stop Debug app; copy main DB plus every present sidecar; preserve phone files')
try:
    assert not adb('shell', 'pidof', PACKAGE, check=False).stdout.strip(), 'App still running'
    names = adb('shell', 'run-as', PACKAGE, 'ls', 'databases').stdout.decode().splitlines()
    names = [n for n in ('eh.db', 'eh.db-journal', 'eh.db-wal', 'eh.db-shm') if n in names]
    assert 'eh.db' in names
    paths = ['databases/' + n for n in names]
    before = adb('shell', 'run-as', PACKAGE, 'sha256sum', *paths).stdout
    data = adb('exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', *paths).stdout
    after = adb('shell', 'run-as', PACKAGE, 'sha256sum', *paths).stdout
    assert before == after, 'Database files changed during capture'
    (out / 'database-files.tar').write_bytes(data)
    files = {}
    expected_hashes = dict(reversed(line.split()) for line in before.decode().splitlines())
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        members = archive.getmembers()
        assert {m.name for m in members} == set(paths)
        for member in members:
            assert member.isfile() and member.name in paths
            content = archive.extractfile(member).read()
            digest = hashlib.sha256(content).hexdigest()
            assert digest == expected_hashes[member.name]
            dest = raw / member.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(content)
            files[member.name] = dict(bytes=len(content), sha256=digest)
    record.update(status='captured', files=files, hashesStable=True)
finally:
    launch = adb('shell', 'am', 'start', '-W', '-n',
                 PACKAGE + '/com.hippo.ehviewer.ui.MainActivity', check=False)
    record['launch'] = launch.stdout.decode('utf-8', errors='replace')
    (out / 'capture.json').write_text(json.dumps(record, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps(record, ensure_ascii=False))
