import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from io import BytesIO


ROOT = pathlib.Path(__file__).resolve().parents[2]


class SinglePackageVariantTests(unittest.TestCase):
    def test_release_variants_use_the_gecko_update_package(self):
        for variant in ("aster-x64", "aster-arm64"):
            args = (ROOT / ".build/args" / f"{variant}.gn").read_text()
            self.assertRegex(
                args,
                r'chrome_public_manifest_package\s*=\s*"app\.aster\.browser"',
            )
            self.assertRegex(args, r'android_override_version_code\s*=\s*"800000000"')
            self.assertRegex(args, r'android_override_version_name\s*=\s*"4\.0\.0"')

    def test_pipeline_stages_the_gecko_engine_for_both_release_abis(self):
        pipeline = (ROOT / "ci/chromium_android_pipeline.sh").read_text()
        self.assertRegex(pipeline, r"aster-x64\|aster-arm64")
        self.assertIn("stage_aster_engine", pipeline)
        self.assertTrue(
            (ROOT.parents[1] / "aster-engine/scripts/stage_chromium_integration.sh").exists()
        )

    def test_single_apk_patch_targets_gecko_activity_in_package(self):
        patch = "".join(
            (ROOT / "patches/m151" / name).read_text()
            for name in (
                "0037-aster-single-apk-dual-engine.patch",
                "0038-aster-gecko-native-libraries.patch",
            )
        )
        self.assertIn("aster_gecko_native_libraries", patch)
        self.assertIn('android:taskAffinity="app.aster.shared"', patch)
        self.assertIn("app.aster.browser.MainActivity.class", patch)
        added_lines = [
            line[1:]
            for line in patch.splitlines()
            if line.startswith("+") and not line.startswith("+++")
        ]
        self.assertFalse(any("getLaunchIntentForPackage" in line for line in added_lines))

    def test_gecko_runtime_is_shared_across_activity_instances(self):
        activity = (ROOT.parents[1] / "app/src/main/java/app/aster/browser/MainActivity.kt").read_text()
        self.assertIn("private object AsterGeckoRuntime", activity)
        self.assertIn("GeckoRuntime.create(context.applicationContext, settings)", activity)
        self.assertIn("runtime = AsterGeckoRuntime.get(this", activity)

    def test_combined_apk_packages_litertlm_native_runtime(self):
        generator = ROOT.parents[1] / "aster-engine/scripts/generate_chromium_gn.py"
        with tempfile.TemporaryDirectory() as temp_dir:
            chromium = pathlib.Path(temp_dir)
            runtime = chromium / "third_party/aster/runtime"
            runtime.mkdir(parents=True)
            (chromium / "build/android/gyp").mkdir(parents=True)
            (chromium / "build/android/gyp/aar.py").write_text("import sys\nsys.exit(0)\n")
            artifacts = [
                ("org.mozilla.geckoview:geckoview", "geckoview.aar", "libxul.so"),
                ("com.google.ai.edge.litertlm:litertlm-android", "litertlm.aar", "liblitertlm_jni.so"),
            ]
            lock_lines = []
            for coordinate, filename, native_name in artifacts:
                classes = BytesIO()
                with zipfile.ZipFile(classes, "w") as class_jar:
                    class_jar.writestr("sample/Runtime.class", b"class")
                with zipfile.ZipFile(runtime / filename, "w") as aar:
                    aar.writestr("classes.jar", classes.getvalue())
                    for abi in ("arm64-v8a", "x86_64"):
                        aar.writestr(f"jni/{abi}/{native_name}", b"native")
                lock_lines.append(f"{coordinate}|{filename}|aar|sha256")
            (runtime / "dependencies.lock").write_text("\n".join(lock_lines))
            subprocess.run([sys.executable, str(generator), str(runtime)], check=True, capture_output=True)
            generated = (chromium / "third_party/aster/BUILD.gn").read_text()
        for native_name in ("libxul.so", "liblitertlm_jni.so"):
            with self.subTest(native_name=native_name):
                self.assertIn(native_name, generated)

    def test_combined_apk_packages_only_kotlin_builtin_resources(self):
        generator = ROOT.parents[1] / "aster-engine/scripts/generate_chromium_gn.py"
        with tempfile.TemporaryDirectory() as temp_dir:
            chromium = pathlib.Path(temp_dir)
            runtime = chromium / "third_party/aster/runtime"
            runtime.mkdir(parents=True)
            (chromium / "build/android/gyp").mkdir(parents=True)
            (chromium / "build/android/gyp/aar.py").write_text("import sys\nsys.exit(0)\n")
            with zipfile.ZipFile(runtime / "kotlin-stdlib.jar", "w") as kotlin:
                kotlin.writestr("kotlin/Any.class", b"must-not-be-packaged")
                kotlin.writestr("kotlin/kotlin.kotlin_builtins", b"builtins")
                kotlin.writestr("kotlin/collections/collections.kotlin_builtins", b"collections")
            (runtime / "dependencies.lock").write_text(
                "org.jetbrains.kotlin:kotlin-stdlib:2.4.20|kotlin-stdlib.jar|jar|sha256"
            )
            subprocess.run([sys.executable, str(generator), str(runtime)], check=True, capture_output=True)
            generated = (chromium / "third_party/aster/BUILD.gn").read_text()
            with zipfile.ZipFile(runtime / "kotlin-builtins-resources.jar") as metadata:
                names = metadata.namelist()
        self.assertIn("include_java_resources = true", generated)
        self.assertNotIn("kotlin/Any.class", names)
        self.assertIn("kotlin/kotlin.kotlin_builtins", names)
        self.assertIn("kotlin/collections/collections.kotlin_builtins", names)

    def test_chromium_shortcuts_open_native_feature_ui_without_gecko_handoff(self):
        root = ROOT.parents[1]
        patches = ROOT / "patches/m151"
        combined = "".join((patches / name).read_text() for name in (
            "0035-aster-single-task-dual-engine.patch",
            "0037-aster-single-apk-dual-engine.patch",
        ))
        activity = (ROOT.parents[1] / "app/src/main/java/app/aster/browser/ChromiumFeaturesActivity.kt").read_text()
        for feature in ("ai", "tor", "tools", "issue"):
            with self.subTest(feature=feature):
                self.assertIn(f'"aster_feature", "{feature}"', combined)
                self.assertIn(f'FEATURE_{feature.upper()} = "{feature}"', activity)
        self.assertIn("ChromiumFeaturesActivity.class", combined)
        self.assertIn("ChromiumTorActivity::class.java", activity)
        bridge = (root / "app/src/main/java/app/aster/browser/TorSocksHttpProxy.java").read_text()
        self.assertIn("new ServerSocket(0, 32, InetAddress.getByName(\"127.0.0.1\"))", bridge)
        self.assertIn("Tor SOCKS5 could not open destination stream", bridge)
        self.assertIn('"chromium_local_ai_model_path"', activity)
        self.assertIn("FEATURE_UPDATES = \"updates\"", activity)
        self.assertIn("AsterAppUpdater.check(this, updateStatus)", activity)
        updater = (root / "app/src/main/java/app/aster/browser/AsterAppUpdater.kt").read_text()
        self.assertIn("combined build", updater)
        self.assertNotIn("for GeckoView", updater)
        self.assertNotIn('putExtra("aster_action", "ai")', combined)
        self.assertNotIn('putExtra("aster_action", "tor")', combined)
        for name in (
            "0028-aster-local-ai-chat-companion.patch",
            "0029-aster-chromium-tor-updater-version.patch",
            "0031-aster-gecko-tools-handoff.patch",
            "0034-aster-chromium-issue-report-handoff.patch",
        ):
            with self.subTest(patch=name):
                self.assertNotIn("GeckoView to", (patches / name).read_text())
        feature_patch = (patches / "0036-aster-chromium-feature-request-shortcut.patch").read_text()
        self.assertIn("aster_feature_request_shortcut", feature_patch)
        self.assertIn("issues/new?labels=enhancement", feature_patch)

    def test_chromium_tor_window_isolated_and_proxy_required(self):
        root = ROOT.parents[1]
        activity = (root / "app/src/main/java/app/aster/browser/ChromiumTorActivity.kt").read_text()
        manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
        webkit = (root / "aster-engine/build.gradle.kts").read_text()
        self.assertIn('android:name=".ChromiumTorActivity"', manifest)
        self.assertIn('androidx.webkit:webkit:1.18.0', webkit)
        self.assertIn("WebViewFeature.PROXY_OVERRIDE", activity)
        self.assertIn("TorProxyRoutePlan.forLocalHttpProxyPort(localProxyPort)", activity)
        self.assertIn("TorSocksHttpProxy(socksPort)", activity)
        self.assertIn("removeImplicitRules()", activity)
        self.assertIn("diagnosticSummary()", activity)
        self.assertIn('setDataDirectorySuffix("aster_chromium_tor")', activity)
        self.assertIn("failClosed(", activity)
        self.assertIn("check.torproject.org/api/ip", activity)
        self.assertIn("CookieManager.getInstance().removeAllCookies", activity)
        self.assertNotIn("org.mozilla.geckoview", activity)

    def test_chromium_to_gecko_handoff_preserves_http_tabs_in_combined_task(self):
        patch = (ROOT / "patches/m151/0037-aster-single-apk-dual-engine.patch").read_text()
        self.assertIn('intent.putStringArrayListExtra("aster_tabs", urls);', patch)
        self.assertIn('intent.putStringArrayListExtra("aster_tab_titles", titles);', patch)
        self.assertIn('intent.putExtra("aster_active_tab", active);', patch)
        self.assertIn('android:taskAffinity="app.aster.shared"', patch)
        self.assertIn("mActivity.startActivity(intent);", patch)

    def test_gecko_runtime_reuses_chromium_androidx_and_play_services(self):
        generator = (ROOT.parents[1] / "aster-engine/scripts/generate_chromium_gn.py").read_text()
        self.assertIn('group("aster_gecko_native_libraries")', generator)
        self.assertIn('shared_libraries = [', generator)
        self.assertIn("$root_out_dir/obj/third_party/aster/", generator)
        for artifact in (
            "androidx.core:core",
            "androidx.exifinterface:exifinterface",
            "androidx.interpolator:interpolator",
            "androidx.lifecycle:lifecycle-runtime-android",
            "com.google.android.gms:play-services-base",
            "com.google.android.gms:play-services-tasks",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android",
        ):
            with self.subTest(artifact=artifact):
                self.assertIn(f'"{artifact}"', generator)

    def test_gecko_updater_selects_the_same_combined_apk_as_chromium(self):
        updater = (ROOT.parents[1] / "app/src/main/java/app/aster/browser/AsterAppUpdater.kt").read_text()
        self.assertIn('"AsterBrowser-$remoteVersion-Android-$abi.apk"', updater)
        self.assertNotIn("-Gecko-Android-", updater)
        chromium_patch = (ROOT / "patches/m151/0029-aster-chromium-tor-updater-version.patch").read_text()
        self.assertIn('"AsterBrowser-" + tag + "-Android-" + abi + ".apk"', chromium_patch)

    def test_pipeline_accepts_an_existing_android_signing_key(self):
        pipeline = (ROOT / "ci/chromium_android_pipeline.sh").read_text()
        self.assertIn("AFTERBIRD_ANDROID_KEYSTORE_PATH", pipeline)
        self.assertIn("AFTERBIRD_ANDROID_KEYSTORE_NAME", pipeline)
        self.assertIn("AFTERBIRD_ANDROID_KEYSTORE_PASSWORD", pipeline)


if __name__ == "__main__":
    unittest.main()
