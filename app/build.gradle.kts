plugins {
    id("pf.android.application")
    alias(libs.plugins.kotlin.serialization)
    id("pf.hilt")
}

android {
    namespace = "ru.finassist.pf"

    defaultConfig {
        applicationId = "ru.finassist.pf"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // `mock`: in-process fake backend on an OFX statement, local toggles, logcat tracking — no SDK keys needed.
    // `prod`: real HTTP client, RuStore Remote Config, MyTracker, AppMetrica.
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
            buildConfigField("String", "APPMETRICA_API_KEY", "\"${secret("pf.appmetricaApiKey")}\"")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.api)
    implementation(projects.core.designsystem)
    implementation(projects.core.navigation)
    implementation(projects.core.toggles)
    implementation(projects.core.tracking)
    implementation(projects.core.storage)

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
    "prodImplementation"(projects.providers.crashAppmetrica)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

/** Gradle property or environment variable (`pf.mytrackerSdkKey` → `PF_MYTRACKER_SDK_KEY`); empty when absent. */
fun secret(name: String): String =
    (findProperty(name) as String?)
        ?: System.getenv(name.replace(".", "_").replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase())
        ?: ""
