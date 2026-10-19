package ru.finassist.pf.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidSdkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val noEnv: (String) -> String? = { null }

    @Test
    fun `without variables sdk dir comes from local properties above the start directory`() {
        val root = tmp.newFolder("repo")
        File(root, "local.properties").writeText("sdk.dir=/opt/sdk-from-file\n")
        val start = File(root, "build-logic/convention").apply { mkdirs() }

        assertEquals("/opt/sdk-from-file", findAndroidSdk(noEnv, start))
    }

    @Test
    fun `the nearest local properties wins`() {
        val root = tmp.newFolder("outer")
        File(root, "local.properties").writeText("sdk.dir=/outer\n")
        val inner = File(root, "inner").apply { mkdirs() }
        File(inner, "local.properties").writeText("sdk.dir=C\\:\\\\Android\\\\sdk\n")

        assertEquals("C:\\Android\\sdk", findAndroidSdk(noEnv, File(inner, "module").apply { mkdirs() }))
    }

    @Test
    fun `sdk dir of local properties takes precedence over the variables, as in AGP`() {
        val root = tmp.newFolder("file-first")
        File(root, "local.properties").writeText("sdk.dir=/from-file\n")

        assertEquals("/from-file", findAndroidSdk(mapOf("ANDROID_HOME" to "/home", "ANDROID_SDK_ROOT" to "/root")::get, root))
    }

    @Test
    fun `without sdk dir ANDROID_HOME comes before ANDROID_SDK_ROOT`() {
        val root = tmp.newFolder("env")
        File(root, "local.properties").writeText("pf.apiBaseUrl=http://10.0.2.2:8080/\n")

        assertEquals("/home", findAndroidSdk(mapOf("ANDROID_HOME" to "/home", "ANDROID_SDK_ROOT" to "/root")::get, root))
        assertEquals("/root", findAndroidSdk(mapOf("ANDROID_SDK_ROOT" to "/root")::get, root))
    }

    @Test
    fun `a local properties without sdk dir and no variables give null`() {
        val root = tmp.newFolder("nosdk")
        File(root, "local.properties").writeText("pf.apiBaseUrl=http://10.0.2.2:8080/\n")

        assertNull(findAndroidSdk(noEnv, root))
    }

    @Test
    fun `no variables and no local properties give null`() {
        // The temp directory could sit under a directory with its own local.properties; then the case cannot be set up.
        val start = tmp.newFolder("none")
        val ancestorHasFile = generateSequence(start) { it.parentFile }.any { File(it, "local.properties").isFile }
        assumeFalse("an ancestor of the temp directory has local.properties", ancestorHasFile)

        assertNull(findAndroidSdk(noEnv, start))
    }
}
