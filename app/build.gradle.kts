plugins {
    id("pf.android.application")
    alias(libs.plugins.kotlin.serialization)
    id("pf.hilt")
    alias(libs.plugins.tracer)
}

// Tracer (crashes, ANR): tokens come from ~/.gradle/gradle.properties or CI env and are never committed. Without
// an app token the SDK is switched off in every variant (README, «Ключи SDK»).
val tracerAppToken = secret("pf.tracerAppToken")
val tracerPluginToken = secret("pf.tracerPluginToken")

android {
    namespace = "ru.finassist.pf"

    defaultConfig {
        applicationId = "ru.finassist.pf"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Each instrumented test starts from clean app data (no session or passcode left by a previous run).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Coverage of the instrumented tests goes through the test storage service (`androidx.test.services`):
        // with `clearPackageData` the app's own directory is wiped before the data could be pulled.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        // Empty: the real clock. The e2e build pins "now" (see below).
        buildConfigField("String", "E2E_NOW", "\"\"")
        buildConfigField("boolean", "CRASH_REPORTING_ENABLED", tracerAppToken.isNotBlank().toString())
        // Enables the SDK's startup provider (providers/crash-tracer manifest).
        manifestPlaceholders["tracerEnabled"] = tracerAppToken.isNotBlank()
    }

    // `mock`: in-process fake backend on an OFX statement, local toggles, logcat tracking — no SDK keys needed.
    // `prod`: real HTTP client, RuStore Remote Config, MyTracker.
    // Both: Tracer for crashes and ANR, when a token is set.
    flavorDimensions += "backend"
    productFlavors {
        create("mock") {
            dimension = "backend"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
        }
        create("prod") {
            dimension = "backend"
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.ru/\"")
            // SDK keys come from ~/.gradle/gradle.properties or CI env; empty → the SDK stays off.
            buildConfigField("String", "RUSTORE_REMOTE_CONFIG_APP_ID", "\"${secret("pf.rustoreRemoteConfigAppId")}\"")
            buildConfigField("String", "MYTRACKER_SDK_KEY", "\"${secret("pf.mytrackerSdkKey")}\"")
        }
    }

    // Instrumented tests run against the e2e build (`connectedMockE2eAndroidTest`), the one with JaCoCo probes:
    // AGP instruments a build type only when it is the tested one. Flows and native tests then measure one APK.
    testBuildType = "e2e"

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // UI tests (.maestro/, docs/e2e.md): debug + test hooks from src/mockE2e; mock flavor only.
        // "Now" is pinned to the day after the fixture statement ends, so periods and amounts never drift.
        create("e2e") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            applicationIdSuffix = ".e2e"
            buildConfigField("String", "E2E_NOW", "\"2026-10-08T12:00:00+03:00\"")
            // UI test coverage (FIN-48, docs/testing.md): AGP instruments the classes of this build — the app and
            // every project module in it — with JaCoCo probes. `E2eCoverage` saves the data while the flows run;
            // AGP pulls the instrumented tests' data itself.
            enableAndroidTestCoverage = true
        }
    }

    // The same JaCoCo for the probes in the APK and for the report task (`:e2eCoverageReport`): execution data
    // of one version is not read by another.
    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// The e2e build type exists only for the fake backend: prodE2e would have no test hooks to run against.
androidComponents {
    beforeVariants(selector().withBuildType("e2e").withFlavor("backend" to "prod")) { it.enable = false }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.api)
    implementation(projects.core.designsystem)
    implementation(projects.core.navigation)
    implementation(projects.core.toggles)
    implementation(projects.core.tracking)
    implementation(projects.core.storage)
    implementation(projects.providers.crashTracer)

    // Features: the app is the only module that sees :impl modules and binds them to their :api.
    listOf("auth", "applock", "operations", "statements", "analytics", "assistant", "profile").forEach { feature ->
        implementation(project(":feature:$feature:api"))
        implementation(project(":feature:$feature:impl"))
    }

    "mockImplementation"(projects.mock.backend)
    "mockImplementation"(projects.providers.togglesLocal)
    "mockImplementation"(projects.providers.trackingLog)

    "prodImplementation"(projects.core.network)
    "prodImplementation"(projects.providers.togglesRustore)
    "prodImplementation"(projects.providers.trackingMytracker)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Native UI tests (src/androidTestMock, run on the mock e2e build): what Maestro cannot drive.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // The tested build type is `e2e`: `createComposeRule()` needs the test activity of this manifest there too.
    "e2eImplementation"(libs.androidx.compose.ui.test.manifest)
}

tracer {
    create("defaultConfig") {
        // An empty app token is the SDK's «off»: nothing is generated or uploaded for the variant.
        isDisabled = tracerAppToken.isBlank()
        appToken = tracerAppToken
        pluginToken = tracerPluginToken
        // R8 mappings of minified builds, so stack traces in the console are readable. Needs the plugin token.
        uploadMapping = tracerPluginToken.isNotBlank()
    }
}

/** Gradle property or environment variable (`pf.mytrackerSdkKey` → `PF_MYTRACKER_SDK_KEY`); empty when absent. */
fun secret(name: String): String =
    (findProperty(name) as String?)
        ?: System.getenv(name.replace(".", "_").replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase())
        ?: ""
