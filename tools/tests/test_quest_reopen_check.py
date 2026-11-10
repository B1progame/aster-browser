import importlib.util
import json
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "quest_reopen_check", pathlib.Path(__file__).resolve().parents[1] / "quest_reopen_check.py")
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class ReopenCheckTests(unittest.TestCase):
    def run_check(self, *, events="", alive=True, launch="Status: ok\nTotalTime: 100", kill_sticks=True):
        state = {"killed": False}
        def fake_adb(command, **kwargs):
            args = command[3:]
            output = ""
            status = 0
            if args == ["get-state"]:
                output = "device"
            elif args[:2] == ["shell", "getprop"]:
                output = "test-device"
            elif args[:3] == ["shell", "dumpsys", "package"]:
                output = "versionCode=805000000\nversionName=5.0.0"
            elif args[:4] == ["shell", "cmd", "package", "resolve-activity"]:
                output = "app.aster.browser/org.chromium.chrome.browser.ChromeTabbedActivity"
            elif args[:2] == ["shell", "date"]:
                output = "1794348000"
            elif args[:3] == ["shell", "am", "start"]:
                state["killed"] = False
                output = launch
            elif args[:3] == ["shell", "am", "kill"]:
                state["killed"] = kill_sticks
            elif args[:2] == ["shell", "pidof"]:
                output, status = ("1234", 0) if alive and not state["killed"] else ("", 1)
            elif args[0] == "logcat":
                output = events
            return subprocess.CompletedProcess(command, status, stdout=output, stderr="")

        with tempfile.TemporaryDirectory() as temp:
            output = pathlib.Path(temp) / "report.json"
            args = ["check", "--serial", "fake", "--cycles", "1", "--settle-seconds", "1", "--output", str(output)]
            with patch("sys.argv", args), patch.object(CHECK.subprocess, "run", side_effect=fake_adb), patch.object(CHECK.time, "sleep"):
                result = CHECK.main()
            return result, json.loads(output.read_text())

    def test_all_three_launch_modes_and_unrelated_anr(self):
        result, report = self.run_check(events="1794348001.0 I am_anr: [0,123,other.browser,0,reason]")
        self.assertEqual(0, result)
        self.assertEqual(["cold", "resume", "background-kill"], [r["mode"] for r in report["runs"]])

    def test_successful_am_start_does_not_hide_subsequent_anr(self):
        result, report = self.run_check(events="1794348001.0 I am_anr: [0,123,app.aster.browser,0,reason]")
        self.assertEqual(1, result)
        self.assertFalse(report["runs"][0]["passed"])
        self.assertIn("exit_info", report)

    def test_successful_launch_then_process_crash_fails(self):
        result, report = self.run_check(alive=False)
        self.assertEqual(1, result)
        self.assertFalse(report["runs"][0]["process_alive"])

    def test_launch_permission_failure_fails(self):
        result, report = self.run_check(launch="Error: Permission Denial")
        self.assertEqual(1, result)
        self.assertFalse(report["runs"][0]["passed"])

    def test_best_effort_kill_must_actually_stop_the_process(self):
        result, report = self.run_check(kill_sticks=False)
        self.assertEqual(2, result)
        self.assertFalse(report["runs"][-1]["exercised"])


if __name__ == "__main__":
    unittest.main()
