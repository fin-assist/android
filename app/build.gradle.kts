import io.appmetrica.analytics.gradle.agp8.appmetrica
import ru.finassist.pf.buildlogic.ApiPins
import ru.finassist.pf.buildlogic.BuildDate
import ru.finassist.pf.buildlogic.CheckNoMockCode
import ru.finassist.pf.buildlogic.GenerateNetworkSecurityConfig
import ru.finassist.pf.buildlogic.GeneratePinningManifest
import ru.finassist.pf.buildlogic.LocalBackend
import java.time.LocalDate

plugins {
    id("pf.android.application")
    alias(libs.plugins.kotlin.serialization)
    id("pf.hilt")
    alias(libs.plugins.appmetrica)
}

// Backend of the `local` flavor: `pf.apiBaseUrl`, by default bank-bff on the emulator's host (LocalBackend, FIN-120).
// An invalid value fails the configuration with the reason.
val localBackend = LocalBackend.parse(secret("pf.apiBaseUrl"))

val localNetworkSecurityConfig = tasks.register<GenerateNetworkSecurityConfig>("generateLocalNetworkSecurityConfig") {
    // The output directory is set by AGP (`addGeneratedSourceDirectory` below).
    config.set(LocalBackend.networkSecurityConfig(localBackend))
}

// Production API. A placeholder until there is a server (AGENTS.md «Flavors»).
val prodBaseUrl = "https://api.example.ru/"
val stageBaseUrl = "https://stage.plainfin.ru/"

// Certificate pinning (FIN-121, ApiPins): `pf.apiPins` + `pf.apiPinsExpiration` (or PF_API_PINS and
// PF_API_PINS_EXPIRATION in CI) pin the API host of the flavor. Without them the flavor has no network security
// config; a partial or invalid value fails configuration. Stage uses separate properties, never production pins.
// The build date is a value source: a configuration cache entry from
// another day is not reused, and the generator checks the date again when it runs.
val buildDate: LocalDate = LocalDate.parse(providers.of(BuildDate::class.java) {}.get())
val pinnedFlavors: Map<String, Pair<String, ApiPins.Pins?>> = mapOf(
    "prod" to (prodBaseUrl to ApiPins.parse(secret(ApiPins.PINS_PROPERTY), secret(ApiPins.EXPIRATION_PROPERTY), buildDate)),
    "stage" to (stageBaseUrl to ApiPins.parse(
        secret("pf.stageApiPins"), secret("pf.stageApiPinsExpiration"), buildDate,
        pinsProperty = "pf.stageApiPins", expirationProperty = "pf.stageApiPinsExpiration",
    )),
)
val pinningTasks = pinnedFlavors.mapNotNull { (flavor, environment) ->
    val (baseUrl, pins) = environment
    if (pins == null) return@mapNotNull null
    val name = flavor.replaceFirstChar { it.uppercase() }
    val config = tasks.register<GenerateNetworkSecurityConfig>("generate${name}NetworkSecurityConfig") {
        config.set(ApiPins.networkSecurityConfig(ApiPins.host(baseUrl), pins))
        pinsExpiration.set(pins.expiration.toString())
        pinsExpirationProperty.set(if (flavor == "stage") "pf.stageApiPinsExpiration" else ApiPins.EXPIRATION_PROPERTY)
    }
    val manifest = tasks.register<GeneratePinningManifest>("generate${name}PinningManifest")
    flavor to (config to manifest)
}.toMap()

// CI: the fake backend never reaches a build that talks to a real one (scripts/check-build-separation.py checks
// the manifests of the same builds). Every variant outside the `mock` flavor is checked (onVariants below), so a new
// environment such as `stage` is covered without a change here.
val checkNoMockCode = tasks.register<CheckNoMockCode>("checkNoMockCode")

// Version of the app = version of the release branch (`release/0.2.16` → 0.2.16), raised with every release branch.
// Build number: `PF_VERSION_CODE` — set by the build that goes to the stores; it must only grow across every published
// build, which `GITHUB_RUN_NUMBER` does not guarantee (a counter per workflow; a re-run keeps it). Without it — the CI
// run number (`GITHUB_RUN_NUMBER`, enough to tell builds apart in logs), 1 locally. Both go into `X-Client-Version`
// (`android/0.2.16+41`, api.md «Заголовки», FIN-20/22).
val appVersionName = "0.2.21"
val appVersionCode = (System.getenv("PF_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull() ?: 1

android {
    namespace = "ru.finassist.pf"

    defaultConfig {
        applicationId = "ru.finassist.pf"
        versionCode = appVersionCode
        versionName = appVersionName
        // Without flavor suffixes (`-mock`): what the server compares with its minimum.
        buildConfigField("String", "CLIENT_VERSION_NAME", "\"$appVersionName\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Each instrumented test starts from clean app data (no session or passcode left by a previous run).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Coverage of the instrumented tests goes through the test storage service (`androidx.test.services`):
        // with `clearPackageData` the app's own directory is wiped before the data could be pulled.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        // Empty: the real clock. The e2e build pins "now" (see below).
        buildConfigField("String", "E2E_NOW", "\"\"")
        buildConfigField("String", "APPMETRICA_API_KEY", "\"\"")
        buildConfigField("boolean", "APPMETRICA_SESSIONS", "false")
        manifestPlaceholders["appMetricaEnabled"] = false
    }

    // One dimension `env`, one flavor per environment (FIN-120/385); dimensions are not multiplied.
    // `mock`: in-process fake backend on an OFX statement, local toggles, logcat tracking — no SDK keys needed.
    // `local`: real HTTP client against a bank-bff on the developer's machine (`pf.apiBaseUrl`); local toggles and
    //   logcat tracking as in `mock` — no RuStore or AppMetrica, so nothing reaches production analytics,
    //   config or crash console. Installs next to `prod` (`.local`). Plain HTTP only in localDebug (src/localDebug +
    //   generated security config).
    // `prod`: real HTTP client, RuStore Remote Config and AppMetrica; certificate pinning of the API host when
    //   `pf.apiPins` is set (pinnedFlavors above, FIN-121).
    // `stage`: the same HTTP/SDK providers as prod, with its own endpoint, app id and optional SDK keys/pins.
    // Each reporting environment uses its own AppMetrica app. Blank keys keep the SDK off.
    flavorDimensions += "env"
    productFlavors {
        create("mock") {
            dimension = "env"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
            val key = secret("pf.mockAppMetricaApiKey")
            buildConfigField("String", "APPMETRICA_API_KEY", "\"${key}\"")
            manifestPlaceholders["appMetricaEnabled"] = key.isNotBlank()
            appmetrica { if (key.isBlank()) enable.set(false) }
        }
        create("local") {
            dimension = "env"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            appmetrica { enable.set(false) }
            buildConfigField("String", "API_BASE_URL", "\"${localBackend.url}\"")
        }
        create("stage") {
            dimension = "env"
            applicationIdSuffix = ".stage"
            versionNameSuffix = "-stage"
            buildConfigField("String", "API_BASE_URL", "\"$stageBaseUrl\"")
            buildConfigField("String", "RUSTORE_REMOTE_CONFIG_APP_ID", "\"${secret("pf.stageRustoreRemoteConfigAppId")}\"")
            val key = secret("pf.stageAppMetricaApiKey")
            buildConfigField("String", "APPMETRICA_API_KEY", "\"${key}\"")
            buildConfigField("boolean", "APPMETRICA_SESSIONS", "true")
            manifestPlaceholders["appMetricaEnabled"] = key.isNotBlank()
            appmetrica { if (key.isBlank()) enable.set(false) }
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"$prodBaseUrl\"")
            // SDK keys come from ~/.gradle/gradle.properties or CI env; empty → the SDK stays off.
            buildConfigField("String", "RUSTORE_REMOTE_CONFIG_APP_ID", "\"${secret("pf.rustoreRemoteConfigAppId")}\"")
            val key = secret("pf.appMetricaApiKey")
            buildConfigField("String", "APPMETRICA_API_KEY", "\"${key}\"")
            buildConfigField("boolean", "APPMETRICA_SESSIONS", "true")
            manifestPlaceholders["appMetricaEnabled"] = key.isNotBlank()
            appmetrica { if (key.isBlank()) enable.set(false) }
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
            appmetrica { enable.set(false) }
            buildConfigField("String", "APPMETRICA_API_KEY", "\"\"")
            manifestPlaceholders["appMetricaEnabled"] = false
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
        unitTests.isIncludeAndroidResources = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    // The e2e build type exists only for the fake backend; HTTP flavors have no test hooks.
    beforeVariants(selector().withBuildType("e2e").withFlavor("env" to "local")) { it.enable = false }
    beforeVariants(selector().withBuildType("e2e").withFlavor("env" to "stage")) { it.enable = false }
    beforeVariants(selector().withBuildType("e2e").withFlavor("env" to "prod")) { it.enable = false }

    onVariants { variant ->
        if (variant.flavorName != "mock") {
            val graph = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
            checkNoMockCode.configure { graphs.put("${variant.name}RuntimeClasspath", graph) }
        }
    }

    // The network security config of localDebug, with the app's only cleartext exception: the local backend's host.
    onVariants(selector().withName("localDebug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(localNetworkSecurityConfig, GenerateNetworkSecurityConfig::outputDir)
    }

    // Pinned flavors (FIN-121): every build type of the flavor gets the pins when they are set, debug included.
    onVariants { variant ->
        val (config, manifest) = pinningTasks[variant.flavorName] ?: return@onVariants
        variant.sources.res?.addGeneratedSourceDirectory(config, GenerateNetworkSecurityConfig::outputDir)
        variant.sources.manifests.addGeneratedManifestFile(manifest, GeneratePinningManifest::manifest)
    }
}

// The mock app can report diagnostics, while its e2e variant never includes the SDK.
val mockDebugImplementation by configurations.creating
val mockReleaseImplementation by configurations.creating

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.api)
    implementation(projects.core.designsystem)
    implementation(projects.core.navigation)
    implementation(projects.core.toggles)
    implementation(projects.core.tracking)
    implementation(projects.core.storage)

    // Features: the app is the only module that sees :impl modules and binds them to their :api.
    listOf("auth", "applock", "availability", "operations", "statements", "analytics", "assistant", "profile").forEach { feature ->
        implementation(project(":feature:$feature:api"))
        implementation(project(":feature:$feature:impl"))
    }

    "mockImplementation"(projects.mock.backend)
    "mockImplementation"(projects.providers.togglesLocal)
    "mockImplementation"(projects.providers.trackingLog)
    mockDebugImplementation(projects.providers.telemetryAppmetrica)
    mockReleaseImplementation(projects.providers.telemetryAppmetrica)

    "localImplementation"(projects.core.network)
    "localImplementation"(projects.providers.togglesLocal)
    "localImplementation"(projects.providers.trackingLog)

    "stageImplementation"(projects.core.network)
    "stageImplementation"(projects.providers.togglesRustore)
    "stageImplementation"(projects.providers.telemetryAppmetrica)

    "prodImplementation"(projects.core.network)
    "prodImplementation"(projects.providers.togglesRustore)
    "prodImplementation"(projects.providers.telemetryAppmetrica)

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

    // Unit tests of the app's own small classes (src/test): Robolectric gives them a Context.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

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

// Archive release mappings for manual upload to the corresponding AppMetrica application.
appmetrica {
    offline.set(true)
    enableAnalytics.set(false)
}

/** Gradle property or environment variable (`pf.appMetricaApiKey` → `PF_APP_METRICA_API_KEY`); empty when absent. */
fun secret(name: String): String =
    (findProperty(name) as String?)
        ?: System.getenv(name.replace(".", "_").replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase())
        ?: ""
