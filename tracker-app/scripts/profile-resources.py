#!/usr/bin/env python3
"""Run the opt-in resource soak only on the dedicated ResourceProfile_API_36 AVD."""
import argparse
import csv
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
APP = "com.utbildning.tracker"
TEST = "com.utbildning.tracker.profiling.ResourceProfilingTest"


class RunWatchdog:
    def __init__(self, started, limit_seconds=7200, stall_seconds=180):
        self.started = self.progress_at = started
        self.limit_seconds, self.stall_seconds = limit_seconds, stall_seconds
        self.event_count = 0
        self.last_step = None

    def observe(self, count, step, now):
        if count > self.event_count:
            self.event_count, self.progress_at = count, now
        if step is not None:
            self.last_step = step

    def check(self, now):
        if now - self.started >= self.limit_seconds:
            raise RuntimeError(f"Overall limit reached: {self.limit_seconds} seconds")
        if now - self.progress_at >= self.stall_seconds:
            raise RuntimeError(f"No progress for {self.stall_seconds} seconds; last step: {self.last_step}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default=str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    parser.add_argument("--pilot-only", action="store_true")
    parser.add_argument("--sample-seconds", type=int, default=120)
    args = parser.parse_args()
    if args.sample_seconds < 30:
        parser.error("Sampling interval must be at least 30 seconds")
    run_started = None
    deadline_utc = None
    last_step = None
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, "-s", args.serial]

    def adb(*command, timeout=60):
        for attempt in range(3):
            try:
                remaining = 7200 - (time.monotonic() - run_started) if run_started is not None else timeout
                if remaining <= 0: raise RuntimeError("Two-hour overall limit reached")
                result = subprocess.run(base + list(command), capture_output=True, text=True, timeout=min(timeout, max(1, remaining)))
                if result.returncode == 0:
                    return result.stdout
                reason = result.stderr.strip()
            except subprocess.TimeoutExpired:
                reason = "timeout"
            if attempt < 2:
                time.sleep(2)
        raise RuntimeError(f"adb {' '.join(command[:3])}: {reason}")

    def shell(command):
        return adb("shell", command)

    if shell("getprop ro.boot.qemu.avd_name").strip() != "ResourceProfile_API_36":
        raise RuntimeError("Refusing a device other than the dedicated profiling AVD")
    if shell("getprop ro.build.version.sdk").strip() != "36":
        raise RuntimeError("API 36 required")

    def instrumentation(phase):
        return base + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
                       "-e", "resourceProfile", "true", "-e", "profilePhase", phase,
                       APP + ".test/androidx.test.runner.AndroidJUnitRunner"]

    def check_instrumentation(path):
        text = path.read_text(errors="replace")
        if not re.search(r"OK \(1 test\)", text) or any(s in text for s in ["FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"]):
            raise RuntimeError(f"Instrumentation did not pass: {path}")

    def run_short(phase):
        nonlocal last_step
        path = output / (phase + "-instrumentation.log")
        with path.open("w") as log:
            process = subprocess.Popen(instrumentation(phase), stdout=log, stderr=subprocess.STDOUT)
            started, changed = time.monotonic(), time.monotonic()
            previous = ""
            try:
                while process.poll() is None:
                    events = shell("cat /sdcard/Android/data/" + APP + "/files/resource-profile/events.jsonl 2>/dev/null || true")
                    if events != previous:
                        changed, previous = time.monotonic(), events
                        (output / (phase + "-events.jsonl")).write_text(events)
                    for line in reversed(events.splitlines()):
                        try:
                            value = json.loads(line)
                            if value.get("step"):
                                last_step = value["step"]
                                break
                        except ValueError:
                            pass
                    if time.monotonic() - changed > 180:
                        raise RuntimeError(f"Pilot has no progress for 180 seconds; last step: {last_step}")
                    if time.monotonic() - started > 600:
                        raise RuntimeError("Pilot exceeded ten-minute limit")
                    time.sleep(5)
                if process.returncode: raise RuntimeError(f"{phase} adb failed: {process.returncode}")
            finally:
                if process.poll() is None: process.terminate()
        check_instrumentation(path)

    def status(state, **values):
        data = dict(state=state, at=dt.datetime.now(dt.timezone.utc).isoformat(), controllerPid=os.getpid(), **values)
        if deadline_utc is not None:
            data["deadlineAtUtc"] = deadline_utc
            data["overallLimitSeconds"] = 7200
            data["noProgressLimitSeconds"] = 180
        target = output / "status.json"
        temp = target.with_suffix(".tmp")
        temp.write_text(json.dumps(data, indent=2))
        temp.replace(target)
        print(json.dumps(data), flush=True)

    try:
        status("preparing")
        shell("wm user-rotation lock 0")
        manifest = dict(revision=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                        diff=subprocess.check_output(["git", "diff", "--stat"], cwd=ROOT, text=True),
                        testSourceSha256=hashlib.sha256((ROOT / "tracker-app/app/src/androidTest/java/com/utbildning/tracker/profiling/ResourceProfilingTest.kt").read_bytes()).hexdigest(),
                        controllerSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                        serial=args.serial, sampleSeconds=args.sample_seconds, plannedMinutes=120, cycles=120)
        for filename in ["app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
            apk = ROOT / "tracker-app" / filename
            manifest[apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
            adb("install", "-r", str(apk), timeout=180)
        manifest["device"] = shell("getprop ro.build.fingerprint; wm size; wm density; settings get system font_scale; getprop persist.sys.timezone; date")
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2))
        marker = shell("run-as " + APP + " sh -c " + shlex.quote("test -f shared_prefs/resource_profile.xml && cat shared_prefs/resource_profile.xml || true"))
        if 'name="seeded" value="true"' not in marker:
            status("seeding")
            run_short("seed")
        if args.pilot_only:
            shell("rm -f /sdcard/Android/data/" + APP + "/files/resource-profile/events.jsonl")
            status("pilot", cycles=12)
            run_short("pilot")
            (output / "pilot-manifest.json").write_text(json.dumps(manifest, indent=2))
            status("pilot_passed")
            return
        if not (output / "pilot-instrumentation.log").is_file():
            raise RuntimeError("Run --pilot-only successfully before starting the soak")
        check_instrumentation(output / "pilot-instrumentation.log")
        pilot_manifest = json.loads((output / "pilot-manifest.json").read_text())
        for key in ["app-debug.apk", "app-debug-androidTest.apk", "testSourceSha256"]:
            if pilot_manifest[key] != manifest[key]:
                raise RuntimeError(f"Pilot was run with a different build: {key}")
        shell("am force-stop " + APP)
        shell("rm -f /sdcard/Android/data/" + APP + "/files/resource-profile/events.jsonl")
        status("running", cycles=120, sampleSeconds=args.sample_seconds)
        logcat = (output / "logcat.log").open("a")
        logcat_command = base + ["logcat", "-v", "epoch", "-T", "1", "AndroidRuntime:E", "TestRunner:I", "System.out:I", "ActivityManager:I", "libc:F", "DEBUG:F", "*:S"]
        logcat_process = subprocess.Popen(logcat_command, stdout=logcat, stderr=subprocess.STDOUT)
        start = time.monotonic()
        run_started = start
        deadline_utc = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=7200)).isoformat()
        watchdog = RunWatchdog(start)
        phase_log = output / "soak-instrumentation.log"
        events_path = "/sdcard/Android/data/" + APP + "/files/resource-profile"
        remote_stem = "/data/local/tmp/resource-profile-" + output.name
        remote_log, remote_exit = remote_stem + ".log", remote_stem + ".exit"
        shell("rm -f " + shlex.quote(remote_log) + " " + shlex.quote(remote_exit))
        command = shlex.join(instrumentation("soak")[len(base) + 1:])
        device_job = (
            "(sleep 7200; am force-stop " + APP + ") & profile_guard=$!; "
            + command + " > " + shlex.quote(remote_log)
            + " 2>&1; profile_result=$?; kill $profile_guard 2>/dev/null; "
            + "echo $profile_result > " + shlex.quote(remote_exit)
        )
        # The waiting am client lives on Android. Disconnecting host adb cannot end the test.
        shell("nohup sh -c " + shlex.quote(device_job) + " </dev/null >/dev/null 2>&1 &")
        last_pid = None
        next_sample = 0.0
        count = 0
        try:
            with (output / "metrics.csv").open("w", newline="") as metrics:
                writer = csv.DictWriter(metrics, fieldnames=["utc", "elapsedSeconds", "pid", "totalPssKb", "privateDiskKb", "cacheKb", "databaseKb", "publicBackupsKb"])
                writer.writeheader()
                while True:
                    elapsed = time.monotonic() - start
                    watchdog.check(time.monotonic())
                    if logcat_process.poll() is not None:
                        logcat_process = subprocess.Popen(logcat_command, stdout=logcat, stderr=subprocess.STDOUT)
                    events = shell("cat " + events_path + "/events.jsonl 2>/dev/null || true")
                    (output / "events.jsonl").write_text(events)
                    ready = set()
                    event_values = []
                    for line in events.splitlines():
                        try:
                            value = json.loads(line)
                            event_values.append(value)
                            if value.get("event") == "heap_done": ready.add(value["name"])
                            if value.get("event") == "failed": raise RuntimeError(value.get("error", "Device scenario failed"))
                        except (ValueError, KeyError):
                            pass
                    last_step = next((v.get("step") for v in reversed(event_values) if v.get("step")), None)
                    watchdog.observe(len(event_values), last_step, time.monotonic())
                    watchdog.check(time.monotonic())
                    if not event_values and elapsed > 60:
                        raise RuntimeError("Device instrumentation did not start within 60 seconds")
                    pids = {str(value["pid"]) for value in event_values if "pid" in value}
                    if len(pids) > 1: raise RuntimeError(f"Scenario recorded multiple PIDs: {pids}")
                    for name in ready:
                        path = output / (name + ".hprof")
                        if not path.exists():
                            partial = path.with_suffix(".hprof.partial")
                            result = subprocess.run(base + ["pull", events_path + "/" + name + ".hprof", str(partial)], capture_output=True, timeout=max(1, min(30, 7200 - (time.monotonic() - start))))
                            if result.returncode == 0: partial.replace(path)
                            elif partial.exists(): partial.unlink()
                    if elapsed >= next_sample:
                        pid = shell("pidof -s " + APP + " || true").strip()
                        if pid:
                            if last_pid and pid != last_pid: raise RuntimeError(f"App PID changed: {last_pid} -> {pid}")
                            last_pid = pid
                        memory = shell("dumpsys meminfo -a " + APP)
                        disk = shell("run-as " + APP + " du -ak .")
                        backups = shell("du -ak '/sdcard/Download/Study Tracker' 2>/dev/null || true")
                        # Wait for a real process before emitting the first sample.
                        match = re.search(r"TOTAL PSS:\s*(\d+)", memory)
                        if pid and match:
                            count += 1
                            stem = output / f"sample-{count:03d}"
                            stem.with_suffix(".memory.txt").write_text(memory)
                            stem.with_suffix(".disk.txt").write_text(disk)
                            stem.with_suffix(".backups.txt").write_text(backups)
                            sizes = {}
                            for line in disk.splitlines():
                                fields = line.split(None, 1)
                                if len(fields) == 2 and fields[0].isdigit(): sizes[fields[1]] = int(fields[0])
                            public = [int(line.split()[0]) for line in backups.splitlines() if line.split() and line.split()[0].isdigit()]
                            writer.writerow(dict(utc=dt.datetime.now(dt.timezone.utc).isoformat(), elapsedSeconds=round(elapsed, 1),
                                pid=pid, totalPssKb=match.group(1), privateDiskKb=sizes.get(".", ""), cacheKb=sizes.get("./cache", ""),
                                databaseKb=sizes.get("./databases", ""), publicBackupsKb=public[-1] if public else 0))
                            metrics.flush()
                            next_sample = elapsed + args.sample_seconds
                    latest = next((value.get("cycle") for value in reversed(event_values) if value.get("event") == "cycle_done"), 0)
                    status("running", samples=count, pid=last_pid, elapsedSeconds=round(elapsed), completedCycles=latest, lastStep=last_step, cycles=120, sampleSeconds=args.sample_seconds)
                    done = shell("cat " + shlex.quote(remote_exit) + " 2>/dev/null || true").strip()
                    if done:
                        phase_log.write_text(shell("cat " + shlex.quote(remote_log)))
                        if done != "0": raise RuntimeError(f"Device instrumentation exit: {done}")
                        check_instrumentation(phase_log)
                        if not any(value.get("event") == "completed" for value in event_values): raise RuntimeError("No completion event")
                        break
                    time.sleep(10)
            adb("pull", events_path, str(output / "device-artifacts"), timeout=180)
            status("completed", samples=count, pid=last_pid, elapsedSeconds=round(time.monotonic() - start), cycles=120)
        finally:
            logcat_process.terminate()
            logcat.close()
    except BaseException as error:
        # Diagnosis precedes termination; use short independent commands even after the run deadline.
        for name, command in [("activity", "dumpsys activity activities"), ("window", "dumpsys window"),
                              ("threads", "run-as " + APP + " kill -3 $(pidof -s " + APP + ")")]:
            try:
                result = subprocess.run(base + ["shell", command], capture_output=True, text=True, timeout=5)
                (output / ("failure-" + name + ".txt")).write_text(result.stdout + result.stderr)
            except Exception:
                pass
        try:
            subprocess.run(base + ["shell", "am force-stop " + APP], capture_output=True, timeout=5)
        except Exception:
            pass
        try:
            subprocess.run(base + ["shell", "wm user-rotation lock 0"], capture_output=True, timeout=5)
        except Exception:
            pass
        status("failed", error=str(error), lastStep=last_step)
        raise


if __name__ == "__main__":
    main()
