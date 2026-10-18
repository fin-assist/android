package ru.finassist.pf.di

import android.content.Context
import ru.finassist.pf.core.toggles.LastMinVersion

/** [LastMinVersion] in plain shared preferences: not a secret, and it must be readable before the first request. */
internal class LastMinVersionPrefs(context: Context) : LastMinVersion {
    private val prefs = context.getSharedPreferences("pf_min_version", Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(value: String) {
        if (prefs.getString(KEY, null) != value) prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "app.min_version"
    }
}
