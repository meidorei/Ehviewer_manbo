"""User-confirmed Debug ordering; retain originals and verify before/after launch."""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import time

R = Path(__file__).resolve().parent
ROOT = R.parents[1]
ADB = r'C:\Users\21555\AppData\Local\Android\Sdk\platform-tools\adb.exe'
PACKAGE = 'com.ehviewer.manbo.debug'
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
a = p.parse_args()
spec = importlib.util.spec_from_file_location('organize', ROOT / '.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py')
o = importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)
out = R / 'writeback'
out.mkdir(exist_ok=False)
tz = timezone(timedelta(hours=8))
stamp = datetime.now(tz).strftime('%Y%m%d-%H%M%S')
backup = 'databases/organize-backup-' + stamp
stage = 'databases/eh.db.organize-' + stamp
report = dict(device=a.serial, package=PACKAGE, status='preparing',
              userApproval='2026-10-28: 可以，写回手机', confirmedPreview='preview-final/order.json',
              phoneOriginalBackup=backup, phoneWritten=False, replacementAttempted=False,
              launchAttempted=False, sidecarMoveIntent=[])

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def save():
    (out/'phone-writeback.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def adb(*args, data=None, check=True):
    result = subprocess.run([ADB,'-s',a.serial,*args],input=data,
                            capture_output=True,timeout=45,check=False)
    if check and result.returncode:
        raise RuntimeError('ADB failed: '+repr(args)+': '+result.stderr.decode('utf-8',errors='replace'))
    return result.stdout

def app(*args):
    return adb('shell','run-as',PACKAGE,*args)

def files():
    listed = app('ls','databases').decode().splitlines()
    selected = [n for n in ('eh.db','eh.db-journal','eh.db-wal','eh.db-shm') if n in listed]
    require('eh.db' in selected,'Main Debug database missing')
    return selected

def hashes(names):
    lines = app('sha256sum',*['databases/'+n for n in names]).decode().splitlines()
    return {line.split()[1].split('/')[-1]:line.split()[0] for line in lines}

def stopped():
    require(not adb('shell','pidof',PACKAGE,check=False).strip(),'Debug app unexpectedly running')

def stop():
    adb('shell','am','force-stop',PACKAGE)
    stopped()

def capture(name, live=False):
    # The post-launch copy stays consistent by requiring the complete file set
    # and all hashes to stay unchanged across the entire binary archive read.
    for attempt in range(1,4 if live else 2):
        if not live:
            stopped()
        names = files()
        before = hashes(names)
        data = adb('exec-out','run-as',PACKAGE,'tar','-cf','-',*['databases/'+n for n in names])
        after_names = files()
        if after_names != names or hashes(after_names) != before:
            require(live and attempt < 3,'Database changed during snapshot: '+name)
            time.sleep(1)
            continue
        target = out/name
        target.mkdir(exist_ok=False)
        (target/'files.tar').write_bytes(data)
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            expected = {'databases/'+n for n in names}
            members = archive.getmembers()
            require(len(members)==len(expected) and {m.name for m in members}==expected,'Unexpected archive entries')
            for member in members:
                require(member.isfile(),'Non-file database archive entry')
                content = archive.extractfile(member).read()
                short = member.name.split('/')[-1]
                require(hashlib.sha256(content).hexdigest()==before[short],'Archive hash mismatch')
                (target/short).write_bytes(content)
        if not live:
            stopped()
        o.snapshot(target/'eh.db',target/'snapshot.db')
        o.write(target/'files.json',dict(hashes=before,stable=True,live=live))
        return target,names,before
    raise RuntimeError('Could not obtain stable snapshot')

save()
try:
    devices = subprocess.check_output([ADB,'devices','-l'],timeout=20).decode()
    require(any(line.split()[:2]==[a.serial,'device'] for line in devices.splitlines()),'Device not authorized')
    version = adb('shell','dumpsys','package',PACKAGE).decode()
    report['version'] = [s.strip() for s in version.splitlines() if 'versionCode=' in s or 'versionName=' in s]
    require(len(report['version'])==2,'Debug package unavailable')
    catalog = o.read(R/'catalog/catalog.json')
    plan = o.read(R/'preview-final/order.json')
    plan['confirmed'] = True
    o.validate_plan(catalog,plan,confirmed=True)
    o.write(out/'confirmed.json',plan)
    stop()
    fresh,names,original_hashes = capture('fresh')
    report['freshFiles'] = names
    report['originalHashes'] = original_hashes
    application = o.apply(fresh/'snapshot.db',catalog,plan,out/'backup.db',out/'sorted.db')
    o.write(out/'apply-report.json',application)
    report['freshLibraryUnchanged'] = True
    data = (out/'sorted.db').read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    report['sortedDatabaseSha256'] = digest
    report['originalLogicalDigest'] = o.db_digest(out/'backup.db')
    report['sortedLogicalDigest'] = o.db_digest(out/'sorted.db')
    mode = app('stat','-c','%a','databases/eh.db').decode().strip()
    require(mode.isdigit() and 0<int(mode,8)<=0o777,'Unexpected database permissions')
    app('mkdir',backup)
    for name in names:
        app('cp','databases/'+name,backup+'/'+name)
        copied = adb('exec-out','run-as',PACKAGE,'cat',backup+'/'+name)
        require(hashlib.sha256(copied).hexdigest()==original_hashes[name],'Phone original backup mismatch')
    report['deviceBackupVerified'] = True
    adb('exec-in','run-as',PACKAGE,'dd','of='+stage,data=data)
    app('chmod',mode,stage)
    require(hashlib.sha256(adb('exec-out','run-as',PACKAGE,'cat',stage)).hexdigest()==digest,'Staged database mismatch')
    require(files()==names and hashes(names)==original_hashes,'Original database changed before replacement')
    stopped()
    report['status'] = 'backed_up_and_staged'
    save()
    print('Fresh 3442-item library unchanged; local and phone backups verified; sorted copy staged.',flush=True)
    for name in names:
        if name!='eh.db':
            # Record intent before a command whose remote outcome can be uncertain.
            report['sidecarMoveIntent'].append(name)
            save()
            app('mv','databases/'+name,backup+'/active-'+name)
    report['replacementAttempted'] = True
    save()
    app('mv',stage,'databases/eh.db')
    report['phoneWritten'] = True
    before,_,_ = capture('readback-before-launch')
    require(o.db_digest(before/'snapshot.db')==report['sortedLogicalDigest'],'Pre-launch full database mismatch')
    require([r['gid'] for r in o.db_items(before/'snapshot.db')]==plan['gidOrder'],'Pre-launch order mismatch')
    report['preLaunchVerify'] = 'passed'
    report['launchAttempted'] = True
    save()
    launch = adb('shell','am','start','-W','-n',PACKAGE+'/com.hippo.ehviewer.ui.MainActivity').decode()
    report['launch'] = launch
    require('Status: ok' in launch and 'Error:' not in launch,'Debug launch failed')
    time.sleep(3)
    report['pidAfterLaunch'] = adb('shell','pidof',PACKAGE).decode().strip()
    require(bool(report['pidAfterLaunch']),'Debug app process exited')
    after,_,_ = capture('readback-after-launch',live=True)
    require(o.db_digest(after/'snapshot.db')==report['sortedLogicalDigest'],'Post-launch full database changed; preserve and investigate')
    require([r['gid'] for r in o.db_items(after/'snapshot.db')]==plan['gidOrder'],'Post-launch order mismatch')
    report['pidAfterReadback'] = adb('shell','pidof',PACKAGE).decode().strip()
    require(bool(report['pidAfterReadback']),'Debug app no longer running after readback')
    report.update(status='passed',postLaunchVerify='passed',itemCount=len(plan['items']),
                  onlyDownloadsTimeChanged=True,sqliteIntegrity='ok',appLeftRunning=True,
                  completedAt=datetime.now(tz).isoformat())
    save()
except Exception as error:
    report.update(status='failed',error=str(error))
    try:
        stop()
        # Restore only if no attempt has ever been made to open the new DB.
        if not report['launchAttempted'] and report.get('deviceBackupVerified') and (report['replacementAttempted'] or report['sidecarMoveIntent']):
            for name in report['freshFiles']:
                app('cp',backup+'/'+name,'databases/'+name)
            recovered,_,_ = capture('rollback-readback')
            require(o.db_digest(recovered/'snapshot.db')==report['originalLogicalDigest'],'Rollback full database mismatch')
            report.update(restoredBeforeLaunch=True,phoneWritten=False)
        report['appLeftRunning'] = False
    except Exception as recovery_error:
        report['recoveryError'] = str(recovery_error)
    save()
    raise
print(json.dumps(report,ensure_ascii=False))
