plugins {
    id("pf.android.application")
    id("pf.android.compose")
    id("pf.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ru.finassist.pf"
    defaultConfig {
        applicationId = "ru.finassist.pf"
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "backend"
    productFlavors {
        // In-process fake backend on the user's own OFX statement; no network, no SDKs.
        create("mock") {
            dimension = "backend"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
            buildConfigField("String", "API_BASE_URL", "\"http://mock.local/\"")
        }
        // Real API, RuStore Remote Config, MyTracker, AppMetrica.
        create("prod") {
            dimension = "backend"
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.ru/\"") // TODO: real host before release
            // SDK keys come from gradle.properties / -P (never committed); empty = SDK disabled.
            fun secret(name: String) = "\"${providers.gradleProperty(name).orNull.orEmpty()}\""
            buildConfigField("String", "RUSTORE_APP_ID", secret("pf.rustoreAppId"))
            buildConfigField("String", "MYTRACKER_ID", secret("pf.myTrackerId"))
            buildConfigField("String", "APPMETRICA_KEY", secret("pf.appMetricaKey"))
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:navigation"))
    implementation(project(":core:network"))
    implementation(project(":core:toggles"))
    implementation(project(":core:tracking"))

    // Feature apis (routes used by the root graph) and impls (wired here only).
    listOf("auth", "applock", "operations", "statements", "analytics", "assistant", "profile").forEach { f ->
        implementation(project(":feature:$f:api"))
        implementation(project(":feature:$f:impl"))
    }

    // Providers per flavor.
    "mockImplementation"(project(":core:mock-backend"))
    "mockImplementation"(project(":core:toggles-local"))
    "mockImplementation"(project(":core:tracking-log"))
    "prodImplementation"(project(":core:toggles-rustore"))
    "prodImplementation"(project(":core:tracking-mytracker"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
