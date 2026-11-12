#!/usr/bin/env python3
"""Exercise an installed APK without clearing its profile; save local evidence.

The selected device must already be unlocked and have completed first-run setup.
This checks launches and process health, not controller input or page rendering.
"""

import argparse
import datetime
import json
import pathlib
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="Explicit adb device serial")
    parser.add_argument("--package", default="app.aster.browser")
    parser.add_argument("--cycles", type=int, default=5)
    parser.add_argument("--settle-seconds", type=float, default=10)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.]+", args.package):
        parser.error("invalid package")
    if not 1 <= args.cycles <= 100 or not 1 <= args.settle_seconds <= 60:
        parser.error("cycles must be 1..100; settle-seconds must be 1..60")

    def adb(*command, timeout=45):
        result = subprocess.run(
            [args.adb, "-s", args.serial, *command],
            capture_output=True, text=True, timeout=timeout,
        )
        if result.returncode:
            raise RuntimeError(f"adb {command[0]} failed: {result.stderr.strip()}")
        return result.stdout.strip()

    report = {"started_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "package": args.package, "runs": [], "limitations":
              "Launch/process checks only; manually verify page rendering, tabs and Quest controller input."}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        args.output.write_text(json.dumps(report, indent=2) + "\n")

    def process_alive():
        process = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", args.package],
                                 capture_output=True, text=True, timeout=10)
        if process.returncode not in (0, 1):
            raise RuntimeError("Could not query the app process")
        return process.returncode == 0 and bool(process.stdout.strip())

    try:
        adb("get-state")
        report["device"] = {name: adb("shell", "getprop", key) for name, key in (
            ("model", "ro.product.model"), ("device", "ro.product.device"),
            ("android", "ro.build.version.release"), ("abi", "ro.product.cpu.abi"))}
        package_info = adb("shell", "dumpsys", "package", args.package)
        report["version"] = [line.strip() for line in package_info.splitlines()
                             if re.search(r"versionCode=|versionName=", line)]
        if not report["version"]:
            raise RuntimeError("Package is not installed")
        resolved = adb("shell", "cmd", "package", "resolve-activity", "--brief",
                       "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                       args.package).splitlines()[-1].strip()
        if not re.fullmatch(re.escape(args.package) + r"/[A-Za-z0-9_.$]+", resolved):
            raise RuntimeError("Could not resolve the installed launch Activity")
        report["launcher"] = resolved
        start_epoch = adb("shell", "date", "+%s")
        if not start_epoch.isdigit():
            raise RuntimeError("Could not read device time")

        for cycle in range(args.cycles):
            for mode in ("cold", "resume", "background-kill"):
                if mode == "cold":
                    adb("shell", "am", "force-stop", args.package)
                else:
                    adb("shell", "input", "keyevent", "KEYCODE_HOME")
                    time.sleep(1)
                    if mode == "background-kill":
                        adb("shell", "am", "kill", args.package)
                        for _ in range(10):
                            if not process_alive():
                                break
                            time.sleep(0.2)
                        else:
                            report["runs"].append({"cycle": cycle + 1, "mode": mode,
                                                   "exercised": False, "passed": False,
                                                   "reason": "Android kept the background process alive"})
                            save()
                            print("Process-death check not exercised: Android did not kill the app", flush=True)
                            return 2
                launch = adb("shell", "am", "start", "-W",
                             "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                             "-f", "0x10200000", "-n", resolved)
                time.sleep(args.settle_seconds)
                alive = process_alive()
                events = adb("logcat", "-b", "events", "-v", "epoch", "-T", start_epoch + ".000",
                             "-d", "-s", "am_anr", "am_crash")
                failures = [line for line in events.splitlines()
                            if re.search(r"(?<![A-Za-z0-9_.])" + re.escape(args.package) + r"(?=[:,\s\]])", line)]
                run = {"cycle": cycle + 1, "mode": mode, "launch": launch,
                       "process_alive": alive,
                       "failure_events": failures}
                run["passed"] = bool(re.search(r"^Status: ok$", launch, re.MULTILINE)) and run["process_alive"] and not failures
                report["runs"].append(run)
                save()
                print(f"Cycle {cycle + 1} {mode}: {'PASS' if run['passed'] else 'FAIL'}", flush=True)
                if not run["passed"]:
                    report["exit_info"] = adb("shell", "dumpsys", "activity", "exit-info", args.package)
                    save()
                    return 1
        return 0
    except (RuntimeError, subprocess.TimeoutExpired, OSError) as error:
        report["error"] = str(error)
        save()
        print(f"Check stopped: {error}", flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
