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

    // Features: the app is the only module that sees :impl modules and binds them to their :api.
    listOf("auth", "applock", "operations", "statements", "analytics", "assistant", "profile").forEach { feature ->
        implementation(project(":feature:$feature:api"))
        implementation(project(":feature:$feature:impl"))
    }

    "mockImplementation"(projects.mock.backend)
    "mockImplementation"(projects.providers.togglesLocal)
    "mockImplementation"(projects.providers.trackingLog)

    "prodImplementation"(projects.core.network)
    // stage 8: prodImplementation(projects.providers.togglesRustore), trackingMytracker, crashAppmetrica

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
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
