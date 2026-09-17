#!/usr/bin/env python3
import argparse
import re
import hashlib
import sqlite3
import subprocess
from pathlib import Path


def run(adb, args, **kwargs):
    return subprocess.run([str(adb), *args], check=True, **kwargs)


def verify_device(adb, serial):
    result = run(adb, ["devices", "-l"], capture_output=True, text=True)
    online = [line.split()[0] for line in result.stdout.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == "device"]
    if serial not in online:
        raise RuntimeError(f"device {serial} is not online; online devices: {online}")


def verify_database(path):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
        count = db.execute("SELECT COUNT(*) FROM DOWNLOADS").fetchone()[0]
    finally:
        db.close()
    if integrity != "ok":
        raise RuntimeError(f"exported database integrity_check failed: {integrity}")
    return count


def check_device_database(adb, serial, package):
    run(adb, ["-s", serial, "shell", "am", "force-stop", package], capture_output=True, text=True)
    result = run(adb, ["-s", serial, "shell", "run-as", package, "ls", "-l", "databases"], capture_output=True, text=True)
    for line in result.stdout.splitlines():
        fields = line.split()
        if fields and fields[-1] in ("eh.db-wal", "eh.db-journal", "eh.db-shm"):
            raise RuntimeError("database sidecar present; export through the app or resolve a consistent snapshot first: " + fields[-1])


def export_database(adb, serial, package, output):
    check_device_database(adb, serial, package)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with output.open("xb") as stream:
            result = subprocess.run(
                [str(adb), "-s", serial, "exec-out", "run-as", package, "cat", "databases/eh.db"],
                stdout=stream,
                stderr=subprocess.PIPE,
                check=False,
            )
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
        count = verify_database(output)
    except Exception:
        output.unlink(missing_ok=True)
        raise
    print({"operation": "export", "output": str(output), "bytes": output.stat().st_size, "sha256": hashlib.sha256(output.read_bytes()).hexdigest(), "itemCount": count})


def upload(adb, serial, package, source, remote_name):
    if not source.is_file():
        raise FileNotFoundError(source)
    with source.open("rb") as stream:
        result = subprocess.run(
            [str(adb), "-s", serial, "exec-in", "run-as", package, "dd", f"of=cache/{remote_name}"],
            stdin=stream,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
    expected = hashlib.sha256(source.read_bytes()).hexdigest()
    remote = run(adb, ["-s", serial, "shell", "run-as", package, "sha256sum", f"cache/{remote_name}"], capture_output=True, text=True)
    actual = remote.stdout.split()[0].lower()
    if actual != expected:
        run(adb, ["-s", serial, "shell", "run-as", package, "rm", f"cache/{remote_name}"])
        raise RuntimeError("uploaded cache file hash mismatch")
    print({"operation": "upload", "remote": f"cache/{remote_name}", "sha256": actual})


def main():
    parser = argparse.ArgumentParser(description="Exact run-as export or hash-checked cache upload; never replaces the live database.")
    parser.add_argument("operation", choices=("export", "upload"))
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True, help="Explicit authorized target package; no default.")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--remote-name", default="eh.db.sorted")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        parser.error("invalid package identifier")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.remote_name):
        parser.error("remote-name must be a simple cache filename")
    adb = args.adb.resolve()
    if not adb.is_file():
        raise FileNotFoundError(adb)
    verify_device(adb, args.serial)
    if args.operation == "export":
        if args.output is None:
            parser.error("export requires --output")
        export_database(adb, args.serial, args.package, args.output.resolve())
    else:
        if args.source is None:
            parser.error("upload requires --source")
        upload(adb, args.serial, args.package, args.source.resolve(), args.remote_name)


if __name__ == "__main__":
    main()
