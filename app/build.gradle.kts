plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.tomdebres.hither"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        // Its own id, so it installs beside the SeerrTV app it replaces rather than over it.
        applicationId = "com.tomdebres.hither"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 16
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Only issues the baseline lacks fail. Regenerate with ./gradlew :app:updateLintBaseline
        // after fixing old ones.
        baseline = file("lint-baseline.xml")
        // "A newer Gradle/AGP is available" changes with the calendar, not the code.
        disable += "AndroidGradlePluginVersion"
    }
}

baselineProfile {
    // Generated on demand, never by a normal build: ./gradlew :app:generateBaselineProfile with
    // only an API 33+ device or emulator connected (bp35; the Shield and tv31 cannot), and
    // scripts/fake-seerr running on the host (benchmark/, DECISIONS 58).
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // One profile in src/main for every build type, so the release build ships it.
    mergeIntoMain = true
}

// The words' cross-check (A8, `WordsParityTest`) reads strings.xml and the Apple TV app's Words.swift as files, not
// through the classpath: they are the unit tests' inputs too, so a change to either runs the tests again rather than
// reusing the last pass (a planted word passed in 4 s before this).
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("tvos/Hither/App/Words.swift"))
        .withPropertyName("tvosWords").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(layout.projectDirectory.file("src/main/res/values/strings.xml"))
        .withPropertyName("androidStrings").withPathSensitivity(PathSensitivity.NONE)
}

/**
 * The privacy text the app shows (Settings › About › Privacy) is docs/privacy.md itself,
 * copied in as an asset at build time, so the app and the policy a store listing points at
 * cannot say different things.
 */
abstract class PrivacyAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        source.get().asFile.copyTo(output.get().file("privacy.md").asFile, overwrite = true)
    }
}

val privacyAsset = tasks.register<PrivacyAsset>("privacyAsset") {
    source.set(rootProject.layout.projectDirectory.file("docs/privacy.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(privacyAsset, PrivacyAsset::output)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work.runtime)
    // The HTTP engine core's client runs on; core itself never names it.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Installs the baseline profile on devices whose store does not (an adb install, a sideload).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":benchmark"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // "Play on Plex": the intent is captured and checked, and nothing is launched (tv31 has no Plex).
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
