plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// The baseline profile generator (shield-launcher's module, cut to what Hither uses). It runs
// against the app's nonMinifiedRelease build on an API 33+ device or emulator, signed in to
// scripts/fake-seerr on the host; see BaselineProfileGenerator.
android {
    namespace = "com.tomdebres.hither.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        // The generator itself needs API 33+ (or root); the app's floor is lower.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Profiles are generated on an emulator; nothing here is a timing.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY,UNLOCKED,ENG-BUILD"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    // The device is the one ANDROID_SERIAL names (bp35): never a managed device.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    // Only the baseline profile plugin's producer variant, which :app:generateBaselineProfile
    // resolves; anything else would only add build time to ./verify and CI.
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "nonMinifiedRelease"
    }
}
