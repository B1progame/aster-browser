import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class X64EmulatorVariantTests(unittest.TestCase):
    def test_variant_exists_and_targets_x64_desktop_android(self):
        args = (ROOT / ".build/args/aster-x64.gn").read_text()
        self.assertRegex(args, r'target_os\s*=\s*"android"')
        self.assertRegex(args, r'target_cpu\s*=\s*"x64"')
        self.assertRegex(args, r'is_desktop_android\s*=\s*true')
        self.assertRegex(args, r'is_java_debug\s*=\s*true')
        self.assertRegex(args, r'chrome_public_manifest_package\s*=\s*"app\.aster\.browser"')

    def test_pipeline_accepts_aster_x64_and_arm64_variants(self):
        pipeline = (ROOT / "ci/chromium_android_pipeline.sh").read_text()
        self.assertRegex(pipeline, r"(?m)^\s*test\|release\|aster-x64\|aster-arm64\) ;;")


if __name__ == "__main__":
    unittest.main()

