import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.aster.browser"
    // Match the Linux Android SDK bundled with the Chromium checkout used to
    // package this library into the single browser APK.
    compileSdkVersion("android-37.2")
    buildToolsVersion = "37.0.0"
    buildFeatures { buildConfig = true }

    defaultConfig {
        minSdk = 29
        buildConfigField("String", "VERSION_NAME", "\"5.0.0\"")
        buildConfigField("int", "VERSION_CODE", "805000000")
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("../app/src/main/java"))
            res.setSrcDirs(listOf("../app/src/main/res"))
            assets.setSrcDirs(listOf("../app/src/main/assets"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:158.0.20261007201254")
    implementation("info.guardianproject:tor-android:0.4.9.13.2")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.18.0")
    configurations.configureEach {
        resolutionStrategy.force("androidx.core:core:1.16.0", "androidx.core:core-ktx:1.16.0")
    }
}

// Stages the exact AAR/JAR graph resolved for the Gecko engine so Chromium's
// GN build can merge it into its single APK. The files stay under build/ and
// are not checked into source control.
tasks.register("stageChromiumIntegration") {
    dependsOn("bundleReleaseAar")
    doLast {
        val destination = layout.buildDirectory.dir("chromium-stage").get().asFile
        destination.deleteRecursively()
        val dependenciesDir = destination.resolve("dependencies")
        dependenciesDir.mkdirs()

        val engineAar = layout.buildDirectory.file("outputs/aar/aster-engine-release.aar").get().asFile
        engineAar.copyTo(destination.resolve("aster-engine.aar"), overwrite = true)

        val lines = mutableListOf<String>()
        val artifacts = configurations.getByName("releaseRuntimeClasspath")
            .incoming.artifacts.artifacts
            .sortedBy { it.id.componentIdentifier.displayName }
            .distinctBy { "${it.id.componentIdentifier.displayName}.${it.file.extension.lowercase()}" }
        for (artifact in artifacts) {
            val extension = artifact.file.extension.lowercase()
            if (extension !in setOf("aar", "jar")) continue
            val component = artifact.id.componentIdentifier.displayName
            val chromiumProvidedComponents = setOf(
                "androidx.activity:activity",
                "androidx.fragment:fragment",
            )
            if (component.substringBeforeLast(":") in chromiumProvidedComponents) {
                println("Using Chromium's AndroidX implementation instead of staging $component")
                continue
            }
            val safeName = component.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val staged = dependenciesDir.resolve("$safeName.$extension")
            artifact.file.copyTo(staged, overwrite = true)
            val messageDigest = MessageDigest.getInstance("SHA-256")
            staged.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    messageDigest.update(buffer, 0, count)
                }
            }
            val digest = messageDigest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            lines += "$component|dependencies/${staged.name}|$extension|$digest"
        }
        destination.resolve("dependencies.lock").writeText(lines.joinToString("\n", postfix = "\n"))
        println("Staged Gecko engine AAR and ${lines.size} resolved runtime artifacts at ${destination.absolutePath}")
    }
}
