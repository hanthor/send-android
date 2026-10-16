plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Version contract (DEVELOPMENT.md, "Releases"): the tag vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rc.N, from
// -PsendVersion= or else the newest v* tag reachable from HEAD. versionCode = MAJOR*1_000_000 + MINOR*10_000 +
// PATCH*100 + (N for rc.N, 99 for a final release); no tag at all gives 0.0.0-dev / 1.
val sendVersionTag: String? = providers.gradleProperty("sendVersion").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v*", "--abbrev=0")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }

val sendVersion: Pair<String, Int> = sendVersionTag?.let { tag ->
    val parts = requireNotNull(Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""").matchEntire(tag)) {
        "Version tag '$tag' is not vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rc.N"
    }.groupValues
    val (major, minor, patch) = parts.subList(1, 4).map { it.toInt() }
    val rc = parts[4].takeIf { it.isNotEmpty() }?.toInt()
    require(major <= 2099 && minor < 100 && patch < 100 && (rc == null || rc in 1..98)) {
        "Version tag '$tag' does not fit versionCode (MAJOR <= 2099, MINOR/PATCH < 100, rc.N 1..98)"
    }
    tag.removePrefix("v") to major * 1_000_000 + minor * 10_000 + patch * 100 + (rc ?: 99)
} ?: ("0.0.0-dev" to 1)

// Release signing comes from the environment only (release.yml decodes the owner's keystore or generates a throwaway
// one); without SEND_KEYSTORE_FILE the release build stays unsigned. Nothing secret lives in the repository.
val releaseKeystore: String? = providers.environmentVariable("SEND_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }

fun requiredSigningEnv(name: String): String =
    requireNotNull(providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }) {
        "$name must be set together with SEND_KEYSTORE_FILE"
    }

android {
    namespace = "dev.send.matrix"
    compileSdk = 37
    // The NDK only provides llvm-strip here (Send has no native code of its own): with it AGP strips debug symbols
    // from the bundled .so files. CI installs this exact version (android.yml, release.yml).
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.send.matrix"
        minSdk = 34
        targetSdk = 37
        versionCode = sendVersion.second
        versionName = sendVersion.first
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        releaseKeystore?.let { keystorePath ->
            create("release") {
                storeFile = file(keystorePath)
                storePassword = requiredSigningEnv("SEND_KEYSTORE_PASSWORD")
                keyAlias = requiredSigningEnv("SEND_KEY_ALIAS")
                keyPassword = requiredSigningEnv("SEND_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Test-only: the release R8 configuration plus proguard-minified-test.pro, signed with the debug key, with the
        // local-Synapse flag and cleartext config from src/minified. release.yml runs the instrumentation suite on it
        // (-PsendTestBuildType=minified) to prove R8 kept everything the SDK, JNA and Element Call reach by reflection.
        create("minified") {
            initWith(getByName("release"))
            proguardFile("proguard-minified-test.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    // Per-ABI APKs for sideloading from GitHub Releases, plus a universal one; release.yml passes -PsendAbiSplits=true.
    // Off by default so debug keeps its single app-debug.apk. All split APKs share the tag's versionCode (they are not
    // published to a store side by side); the AAB ignores this block and Play splits it per device.
    splits {
        abi {
            isEnable = providers.gradleProperty("sendAbiSplits").map { it.toBoolean() }.getOrElse(false)
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    testBuildType = providers.gradleProperty("sendTestBuildType").getOrElse("debug")
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType != "debug") {
            // Release (and the minified test copy of it): drop 32-bit x86, which no minSdk 34 device needs and for
            // which matrix-rtc-android ships no core (calls could never work there); applies to APKs and the AAB.
            variant.packaging.jniLibs.excludes.add("lib/x86/**")
            // Compress the native libraries inside the APKs (the Rust SDK and RTC cores are ~100 MB per ABI
            // uncompressed); the device extracts them on install. APKs Play generates from the AAB keep AGP's default
            // (uncompressed, page-aligned), since useLegacyPackagingFromBundle stays unset.
            variant.packaging.jniLibs.useLegacyPackaging.set(true)
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    // @Preview annotations in UiPreviews.kt (main) for every build type; ui-tooling itself stays debug-only.
    implementation(libs.compose.tooling.preview)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.compose)
    implementation(libs.matrix.sdk)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core)
    implementation(libs.androidx.work)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.unifiedpush.connector)
    implementation(platform(libs.element.call.bom))
    implementation(libs.element.call.api)
    implementation(libs.element.call.impl)
    implementation(libs.element.call.matrix)
    implementation(libs.element.call.ui)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.tooling)
    add("minifiedImplementation", libs.compose.ui.test.manifest)
}
