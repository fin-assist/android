package ru.finassist.pf.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Jetpack Compose with the Kotlin 2.x compose compiler plugin. */
internal fun Project.configureCompose(extension: CommonExtension<*, *, *, *, *, *>) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

    extension.buildFeatures.compose = true

    dependencies {
        val bom = libs.findLibrary("androidx.compose.bom").get()
        add("implementation", platform(bom))
        add("androidTestImplementation", platform(bom))
        add("implementation", libs.findLibrary("androidx.compose.ui").get())
        add("implementation", libs.findLibrary("androidx.compose.ui.graphics").get())
        add("implementation", libs.findLibrary("androidx.compose.foundation").get())
        add("implementation", libs.findLibrary("androidx.compose.animation").get())
        add("implementation", libs.findLibrary("androidx.compose.ui.tooling.preview").get())
        add("debugImplementation", libs.findLibrary("androidx.compose.ui.tooling").get())
        add("androidTestImplementation", libs.findLibrary("androidx.compose.ui.test.junit4").get())
        add("debugImplementation", libs.findLibrary("androidx.compose.ui.test.manifest").get())
    }

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        // Stability config lets Compose skip recomposition for classes from :core:api etc.
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
    }
}
