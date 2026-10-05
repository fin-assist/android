package ru.finassist.pf.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Shared Android + Kotlin configuration for application and library modules. */
internal fun Project.configureKotlinAndroid(extension: CommonExtension<*, *, *, *, *, *>) {
    extension.apply {
        compileSdk = libs.version("compileSdk").toInt()

        defaultConfig {
            minSdk = libs.version("minSdk").toInt()
            vectorDrawables.useSupportLibrary = true
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        lint {
            abortOnError = true
            warningsAsErrors = false
            checkDependencies = false
        }
    }

    configureKotlin()

    dependencies {
        add("testImplementation", libs.findLibrary("junit4").get())
        add("testImplementation", libs.findLibrary("kotlin.test").get())
        add("testImplementation", libs.findLibrary("kotlinx.coroutines.test").get())
        add("testImplementation", libs.findLibrary("turbine").get())
    }
}

/** Kotlin compiler options shared by every module (Android and JVM). */
internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.configureCommon()
    }
}

private fun KotlinJvmCompilerOptions.configureCommon() {
    jvmTarget.set(JvmTarget.JVM_17)
    allWarningsAsErrors.set(false)
    freeCompilerArgs.addAll(
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-opt-in=kotlinx.coroutines.FlowPreview",
    )
}
