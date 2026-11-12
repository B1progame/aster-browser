import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'upload-quest-draft.sh'
EXPECTED = 'd808c20d0fa520857b3a02b0b3218e634ee9c9fe'
CERT = '59b7beae054026bd0269fcedb68a3144960bf227528f366b92cf878caca2e79f'

class UploadTests(unittest.TestCase):
 def run_case(self, *, existing=False, mismatch_source=False, other_cert=False):
  with tempfile.TemporaryDirectory() as temp:
   t=Path(temp); root=t/'quest'; src=root/'chromium/src'; project=root/'aster-browser-questfixed'; sdk=root/'tools/android-sdk/build-tools/37.0.0'
   (project/'.git').mkdir(parents=True); (src/'.git').mkdir(parents=True); sdk.mkdir(parents=True)
   (project/'chromium-engine/afterbird-source/patches/m151').mkdir(parents=True)
   (project/'chromium-engine/afterbird-source/patches/m151/0039-aster-chromium151-build-compat.patch').write_text('patch')
   for f in ('ntp/NewTabPageCoordinator.java','settings/MainSettings.java'):
    p=src/'chrome/android/java/src/org/chromium/chrome/browser'/f; p.parent.mkdir(parents=True,exist_ok=True); p.write_text('source')
   apk=src/'out/afterbird_production/apks/ChromePublic.apk'; apk.parent.mkdir(parents=True); apk.write_bytes(b'valid fixture apk')
   bin=t/'bin'; bin.mkdir()
   def command(path, content):
    path.write_text('#!/bin/sh\n'+content+'\n'); path.chmod(0o755)
   command(bin/'pgrep','exit 1')
   command(bin/'git', '''case "$*" in
     *"status --porcelain"*) exit 0;;
     *"apply --reverse --check"*) exit 0;;
     *"rev-parse 151.0.7922.38"*) echo chromium-sha;;
     *"chromium/src rev-parse HEAD"*) echo chromium-sha;;
     *"rev-parse HEAD"*) echo "${TEST_SOURCE}";;
     *) echo "unexpected git: $*" >&2; exit 1;; esac''')
   command(sdk/'aapt', '''echo "package: name='app.aster.browser' versionCode='805000000' versionName='5.0.0'"; echo "native-code: 'arm64-v8a'"''')
   command(sdk/'apksigner','echo "V3.0 Signer: certificate SHA-256 digest: $TEST_CERT"')
   command(bin/'gh', '''echo "$*" >> "$TEST_GH_LOG"
case "$*" in
 "auth status"*) exit 0;;
 "api user "*) echo bprosusupro-rgb;;
 *"--method PATCH"*)
   for value in "$@"; do case "$value" in body=@*) cat "${value#body=@}" > "$TEST_NOTES";; esac; done
   echo https://github.com/example/draft;;
 *"--jq .draft"*) echo true;;
 *"--jq .target_commitish"*) echo "$TEST_EXPECTED";;
 *"--jq .tag_name"*) echo quest-reopen-source-candidate-2026-11-10;;
 *"| length"*) if [ "$TEST_EXISTING" = yes ] || [ -e "$TEST_UPLOADED" ]; then echo 1; else echo 0; fi;;
 *".digest"*) if [ "$TEST_EXISTING" = yes ]; then echo "sha256:different"; else echo "sha256:$(sha256sum "$TEST_APK" | cut -d' ' -f1)"; fi;;
 "release upload "*) touch "$TEST_UPLOADED";;
 *) echo "unexpected gh: $*" >&2; exit 1;; esac''')
   script=t/'run.sh'; data=SOURCE.read_text(); marker='TASK_ROOT="$HOME/aster-quest-build"'; assert marker in data
   script.write_text(data.replace(marker, f'TASK_ROOT="{root}"',1))
   env=dict(os.environ, PATH=str(bin)+os.pathsep+os.environ['PATH'], TEST_SOURCE=('wrong' if mismatch_source else EXPECTED), TEST_EXPECTED=EXPECTED, TEST_CERT=('a'*64 if other_cert else CERT), TEST_EXISTING=('yes' if existing else 'no'), TEST_GH_LOG=str(t/'gh.log'), TEST_NOTES=str(t/'notes'), TEST_UPLOADED=str(t/'uploaded'), TEST_APK=str(apk))
   result=subprocess.run(['bash',str(script)],env=env,capture_output=True,text=True,timeout=15)
   return result, (t/'uploaded').exists(), (t/'notes').read_text() if (t/'notes').exists() else '', (t/'gh.log').read_text() if (t/'gh.log').exists() else ''
 def test_upload_updates_only_draft_and_records_matching_signer(self):
  p,uploaded,notes,log=self.run_case(); self.assertEqual(p.returncode,0,p.stdout+p.stderr); self.assertTrue(uploaded); self.assertIn('UPLOAD COMPLETE',p.stdout); self.assertIn('Matches the certificate',notes); self.assertIn('draft=true',log); self.assertNotIn('draft=false',log)
 def test_different_existing_asset_is_never_overwritten(self):
  p,uploaded,notes,log=self.run_case(existing=True); self.assertNotEqual(p.returncode,0); self.assertFalse(uploaded); self.assertIn('was not replaced',p.stderr); self.assertNotIn('--method PATCH',log)
 def test_wrong_source_does_not_contact_github(self):
  p,uploaded,notes,log=self.run_case(mismatch_source=True); self.assertNotEqual(p.returncode,0); self.assertFalse(uploaded); self.assertEqual('',log)
 def test_different_signer_is_described_honestly(self):
  p,uploaded,notes,log=self.run_case(other_cert=True); self.assertEqual(p.returncode,0,p.stdout+p.stderr); self.assertIn('Differs from the certificate',notes)

if __name__=='__main__': unittest.main(verbosity=2)
