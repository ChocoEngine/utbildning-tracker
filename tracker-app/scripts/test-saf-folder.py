#!/usr/bin/env python3
"""Exercise the real SAF picker across an app process restart on a test emulator."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated test emulator serial")
    parser.add_argument("--adb", type=Path, default=Path(
        os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb")
    parser.add_argument("--logs", type=Path, help="New directory for phase logs")
    args = parser.parse_args()
    adb = [str(args.adb), "-s", args.serial]

    def command(*arguments):
        return subprocess.run([*adb, *arguments], check=True, capture_output=True,
                              text=True, timeout=60).stdout.strip()

    if not args.serial.startswith("emulator-") or command("shell", "getprop", "ro.kernel.qemu") != "1":
        parser.error("An explicitly selected, dedicated Android test emulator is required")
    logs = args.logs or Path(tempfile.mkdtemp(prefix="study-tracker-saf-"))
    if args.logs:
        logs.mkdir(parents=True, exist_ok=False)
    print(f"Logs: {logs}", flush=True)
    root = Path(__file__).resolve().parents[1]
    for apk in ["app/build/outputs/apk/debug/app-debug.apk",
                "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
        path = root / apk
        if not path.is_file():
            parser.error(f"Build :app:assembleDebug :app:assembleDebugAndroidTest first: {path}")
        print(command("install", "-r", str(path)), flush=True)

    def phase(name):
        path = logs / f"{name}.txt"
        with path.open("w") as output:
            result = subprocess.run([
                *adb, "shell", "am", "instrument", "-w", "-e", "class",
                "com.utbildning.tracker.backup.SafBackupFolderTest", "-e", "saf_phase", name,
                "com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner",
            ], stdout=output, stderr=subprocess.STDOUT, timeout=180)
        text = path.read_text()
        # adb can exit 0 even when instrumentation fails. This test uses no assumptions.
        if result.returncode or "OK (1 test)" not in text or any(
                marker in text for marker in ["FAILURES!!!", "INSTRUMENTATION_FAILED", "Error in "]):
            raise RuntimeError(f"{name} failed; see {path}\n{text[-12000:]}")
        print(f"{name}: PASS", flush=True)

    try:
        command("shell", "am", "force-stop", "com.utbildning.tracker")
        phase("prepare")
        command("shell", "am", "force-stop", "com.utbildning.tracker")
        phase("verify")
    finally:
        # The probe records only its own course, folder and locale before creating them.
        command("shell", "am", "force-stop", "com.utbildning.tracker")
        phase("cleanup")
        command("shell", "am", "force-stop", "com.utbildning.tracker")
        phase("check_cleanup")
    print("SAF folder selection, persisted access and backup read/write after process restart: PASS")


if __name__ == "__main__":
    main()
