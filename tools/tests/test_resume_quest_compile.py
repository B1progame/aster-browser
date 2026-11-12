import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'resume-quest-compile.sh'


class ResumeCompileTests(unittest.TestCase):
    def prepare(self, temp, active=False):
        root = Path(temp) / 'build'
        (root / 'aster-browser-questfixed/.git').mkdir(parents=True)
        (root / 'chromium/src/.git').mkdir(parents=True)
        apk = root / 'chromium/src/out/afterbird_production/apks/ChromePublic.apk'
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b'test apk')
        bindir = Path(temp) / 'bin'
        bindir.mkdir()
        def command(name, content):
            p = bindir / name
            p.write_text('#!/bin/sh\n' + content + '\n')
            p.chmod(0o755)
        command('git', 'echo invoked >> "$TEST_GIT_LOG"\nexit 0')
        command('pgrep', 'exit ' + ('0' if active else '1'))
        command('autoninja', 'touch "$TEST_STARTED"\nwhile [ ! -f "$TEST_RELEASE" ]; do sleep 0.05; done')
        script = Path(temp) / 'resume.sh'
        source = SCRIPT.read_text()
        original = 'TASK_ROOT="$HOME/aster-quest-build"'
        self.assertIn(original, source)
        script.write_text(source.replace(original, 'TASK_ROOT="' + str(root) + '"', 1))
        env = dict(os.environ, PATH=str(bindir) + os.pathsep + os.environ['PATH'],
                   TEST_GIT_LOG=str(Path(temp) / 'git.log'),
                   TEST_STARTED=str(Path(temp) / 'started'),
                   TEST_RELEASE=str(Path(temp) / 'release'))
        return script, env

    def test_active_external_build_stops_before_changing_checkout(self):
        with tempfile.TemporaryDirectory() as temp:
            script, env = self.prepare(temp, active=True)
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(2, result.returncode, result.stdout + result.stderr)
            self.assertIn('Another build is still running', result.stderr)
            self.assertFalse(Path(env['TEST_GIT_LOG']).exists())

    def test_second_resume_does_not_modify_checkout_or_start_ninja(self):
        with tempfile.TemporaryDirectory() as temp:
            script, env = self.prepare(temp)
            first = subprocess.Popen(['bash', str(script)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                end = time.monotonic() + 5
                while not Path(env['TEST_STARTED']).exists() and time.monotonic() < end:
                    time.sleep(0.05)
                self.assertTrue(Path(env['TEST_STARTED']).exists(), 'first build did not start')
                before = Path(env['TEST_GIT_LOG']).read_text()
                second = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=5)
                self.assertEqual(2, second.returncode, second.stdout + second.stderr)
                self.assertIn('Another resume command is already running', second.stderr)
                self.assertEqual(before, Path(env['TEST_GIT_LOG']).read_text())
                Path(env['TEST_RELEASE']).touch()
                stdout, stderr = first.communicate(timeout=5)
                self.assertEqual(0, first.returncode, stdout + stderr)
                self.assertIn('BUILD FINISHED', stdout)
            finally:
                Path(env['TEST_RELEASE']).touch()
                if first.poll() is None:
                    first.kill()
                first.communicate()


if __name__ == '__main__':
    unittest.main()
