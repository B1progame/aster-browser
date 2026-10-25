@file:Suppress("DEPRECATION")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.aster.browser"
    compileSdkVersion("android-37.2")
    buildFeatures { buildConfig = true }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    defaultConfig {
        applicationId = "app.aster.browser"
        minSdk = 29
        targetSdk = 34
        // Keep the Android package code above Chromium's upstream-derived
        // 792,203,879 used by the previous combined APK release.
        versionCode = 805000000
        versionName = "5.0.0"
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug" }
        // Keep sideloaded Gecko updates installable over the existing development-signed build.
        getByName("release") { signingConfig = signingConfigs.getByName("debug") }
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
    testImplementation("junit:junit:4.13.2")
    configurations.configureEach {
        resolutionStrategy.force("androidx.core:core:1.16.0", "androidx.core:core-ktx:1.16.0")
    }
}
