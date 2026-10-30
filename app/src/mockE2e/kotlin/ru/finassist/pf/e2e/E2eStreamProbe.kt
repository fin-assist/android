package ru.finassist.pf.e2e

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.finassist.pf.MainActivity
import ru.finassist.pf.R
import ru.finassist.pf.mock.MockBackend

/** Read-only stream counter on the running screen; mockE2e only, without opening or pausing an activity. */
class E2eStreamProbe : ContentProvider() {
    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return false
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    val backend = EntryPointAccessors.fromApplication(application, StreamProbeEntryPoint::class.java).backend()
                    installStreamProbe(activity, backend.streamRateLimited)
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface StreamProbeEntryPoint {
    fun backend(): MockBackend
}

internal fun installStreamProbe(activity: ComponentActivity, count: StateFlow<Int>) {
    if (activity.findViewById<View>(R.id.e2e_stream_rate_limited) != null) return
    val marker = TextView(activity).apply {
        id = R.id.e2e_stream_rate_limited
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }
    activity.addContentView(marker, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
    ).apply { topMargin = (48 * activity.resources.displayMetrics.density).toInt() })
    activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            count.collect { given ->
                marker.text = "e2e.stream.rate_limited=$given"
                marker.visibility = if (given > 0) View.VISIBLE else View.GONE
            }
        }
    }
}
