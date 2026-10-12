package ru.finassist.pf.e2e

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * UI test coverage, mock e2e build only (FIN-48, docs/testing.md). The build is instrumented with JaCoCo probes;
 * this provider starts with the process and saves the execution data on its own, so the flows need no extra step:
 *
 * - every [INTERVAL_MS] and whenever an activity stops — only if the data has changed since the last save;
 * - into the shared `Download/pf-coverage/` folder (MediaStore): flows wipe the app with `clearState` and kill it
 *   with `stopApp`, and that folder is the one place an app can write that survives both and that `adb pull` reads;
 * - as a new file each time, deleting the previous one after the new one is complete: a process killed half-way
 *   through a write leaves a truncated file next to an intact older one (`scripts/ui-coverage.py collect` takes
 *   the newest readable file of each process).
 *
 * What a process executed in the moments between its last save and its kill is lost — at most [INTERVAL_MS].
 * Below API 29 (no `MediaStore.Downloads`) the data goes to the app's external files directory and does not
 * survive `clearState`; the UI tests run on API 34.
 */
class E2eCoverage : ContentProvider() {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pf-e2e-coverage").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val processTag = "${System.currentTimeMillis()}-${Process.myPid()}"
    private var sequence = 0
    private var lastHash = 0
    private var lastSaved: Saved? = null

    private sealed interface Saved {
        data class Media(val uri: Uri) : Saved
        data class Local(val file: File) : Saved
    }

    override fun onCreate(): Boolean {
        val agent = agent() ?: run {
            Log.i(TAG, "no JaCoCo runtime in this build: coverage is not collected")
            return true
        }
        executor.scheduleWithFixedDelay({ save(agent) }, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS)
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStopped(activity: Activity) { executor.execute { save(agent) } }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        return true
    }

    /** `org.jacoco.agent.rt.RT.getAgent()`, by reflection: the class exists only in an instrumented build. */
    private fun agent(): Any? = runCatching {
        Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null)
    }.getOrNull()

    /** Runs on [executor] only. Never throws: a failed save must not disturb the app under test. */
    private fun save(agent: Any) {
        try {
            val data = agent.javaClass.getMethod("getExecutionData", Boolean::class.javaPrimitiveType).invoke(agent, false) as ByteArray
            val hash = data.contentHashCode()
            if (hash == lastHash && lastSaved != null) return
            val name = "pf-$processTag-%05d.ec".format(++sequence)
            val saved = write(name, data) ?: return
            lastSaved?.let(::delete)
            lastSaved = saved
            lastHash = hash
        } catch (e: Throwable) {
            Log.w(TAG, "coverage data not saved", e)
        }
    }

    private fun write(name: String, data: ByteArray): Saved? {
        val context = context ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(data) } ?: return null
            return Saved.Media(uri)
        }
        val dir = File(context.getExternalFilesDir(null) ?: return null, FOLDER).apply { mkdirs() }
        return Saved.Local(File(dir, name).apply { writeBytes(data) })
    }

    private fun delete(saved: Saved) {
        when (saved) {
            is Saved.Media -> context?.contentResolver?.delete(saved.uri, null, null)
            is Saved.Local -> saved.file.delete()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "PfE2eCoverage"
        /** `/sdcard/Download/pf-coverage` on the device; `scripts/e2e.sh` clears it before the run and pulls it after. */
        const val FOLDER = "pf-coverage"
        const val INTERVAL_MS = 2000L
    }
}
