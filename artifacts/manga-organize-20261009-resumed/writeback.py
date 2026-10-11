"""Confirmed Debug-only ordering with device/local backups and readback checks."""
import argparse
from datetime import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time

RUN = Path(__file__).resolve().parent
ROOT = RUN.parents[1]
ADB = r'C:\Users\21555\AppData\Local\Android\Sdk\platform-tools\adb.exe'
PACKAGE = 'com.ehviewer.manbo.debug'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('organizer', ROOT / '.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py')
o = importlib.util.module_from_spec(spec);spec.loader.exec_module(o)
out = RUN / 'writeback'
out.mkdir(exist_ok=False)
stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
phone_backup = 'databases/organize-backup-' + stamp
stage = 'databases/eh.db.organize-' + stamp
report = dict(device=args.serial, package=PACKAGE, phoneOriginalBackup=phone_backup,
              status='preparing', userApproval='2026-10-09: 写回手机', phoneWritten=False)

def save():
    (out/'phone-writeback.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def adb(*command, data=None):
    result = subprocess.run([ADB,'-s',args.serial,*command], input=data,
                            capture_output=True, timeout=30, check=True)
    return result.stdout

def app(*command):
    return adb('shell','run-as',PACKAGE,*command)

def capture(name):
    target = out/name
    target.mkdir(exist_ok=False)
    names = app('ls','databases').decode().splitlines()
    assert 'eh.db' in names
    files = [n for n in ('eh.db','eh.db-journal','eh.db-wal','eh.db-shm') if n in names]
    for n in files:
        (target/n).write_bytes(adb('exec-out','run-as',PACKAGE,'cat','databases/'+n))
    o.snapshot(target/'eh.db',target/'snapshot.db')
    return target, files

plan = o.read(RUN/'preview/order.json')
plan['confirmed'] = True
o.validate_plan(o.read(RUN/'catalog/catalog.json'),plan,confirmed=True)
o.write(out/'confirmed.json',plan)
adb('shell','am','force-stop',PACKAGE)
launched = False
mutated = False
sidecars_moved = []
try:
    fresh, files = capture('fresh')
    report['freshFiles'] = files
    application = o.apply(fresh/'snapshot.db',o.read(RUN/'catalog/catalog.json'),plan,
                          out/'backup.db',out/'sorted.db')
    o.write(out/'apply-report.json',application)
    data = (out/'sorted.db').read_bytes()
    report['sortedDatabaseSha256'] = hashlib.sha256(data).hexdigest()
    # Stream bytes into app-owned staging; no shared-storage temporary file.
    adb('exec-in','run-as',PACKAGE,'dd','of='+stage,data=data)
    assert hashlib.sha256(adb('exec-out','run-as',PACKAGE,'cat',stage)).hexdigest() == report['sortedDatabaseSha256']
    app('mkdir',phone_backup)
    for name in files:
        app('cp','databases/'+name,phone_backup+'/'+name)
        expected = hashlib.sha256((fresh/name).read_bytes()).hexdigest()
        assert hashlib.sha256(adb('exec-out','run-as',PACKAGE,'cat',phone_backup+'/'+name)).hexdigest() == expected
    report['deviceBackupVerified'] = True
    report['status'] = 'backed_up_and_staged';save()
    print('Fresh library unchanged; local/device backups and staged database verified.',flush=True)
    # Keep all original sidecars in the backup rather than delete them.
    for name in files:
        if name != 'eh.db':
            app('mv','databases/'+name,phone_backup+'/active-'+name)
            sidecars_moved.append(name)
    app('mv',stage,'databases/eh.db')
    mutated = True
    before, _ = capture('readback-before-launch')
    assert o.db_digest(before/'snapshot.db') == o.db_digest(out/'sorted.db')
    report['preLaunchVerify'] = 'passed';report['phoneWritten'] = True;save()
    launch = adb('shell','am','start','-W','-n',PACKAGE+'/com.hippo.ehviewer.ui.MainActivity').decode()
    assert 'Error:' not in launch and 'Status: ok' in launch, launch
    launched = True
    report['launch'] = launch
    time.sleep(3)
    report['pidAfterLaunch'] = adb('shell','pidof',PACKAGE).decode().strip()
    assert report['pidAfterLaunch']
    adb('shell','am','force-stop',PACKAGE)
    after, _ = capture('readback-after-launch')
    assert o.db_digest(after/'snapshot.db') == o.db_digest(out/'sorted.db'), 'post-launch database changed; investigate before claiming success'
    report['postLaunchVerify'] = 'passed'
    report.update(status='passed',itemCount=len(plan['items']),onlyDownloadsTimeChanged=True,
                  sqliteIntegrity='ok',completedAt=datetime.now().astimezone().isoformat())
except Exception as error:
    report['status'] = 'failed';report['error'] = str(error)
    # Roll back only while the replacement has not been used by the app.
    if not launched and report.get('deviceBackupVerified'):
        if mutated:
            app('cp',phone_backup+'/eh.db','databases/eh.db')
        for name in sidecars_moved:
            app('mv',phone_backup+'/active-'+name,'databases/'+name)
        report['restoredBeforeLaunch'] = True;report['phoneWritten'] = False
    save()
    raise
finally:
    report['finalLaunch'] = adb('shell','am','start','-n',PACKAGE+'/com.hippo.ehviewer.ui.MainActivity').decode()
    save()
print(json.dumps(report,ensure_ascii=False))
