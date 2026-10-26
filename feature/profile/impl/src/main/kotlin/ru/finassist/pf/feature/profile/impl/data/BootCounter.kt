package ru.finassist.pf.feature.profile.impl.data

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** A missing boot identity is unknown, never permission to reuse a persisted elapsed deadline. */
fun interface BootCounter { fun current(): Int? }

class AndroidBootCounter @Inject constructor(@ApplicationContext private val context: Context) : BootCounter {
    override fun current(): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).takeIf { it >= 0 }
    }.getOrNull()
}
