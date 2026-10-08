package ru.finassist.pf.feature.statements.impl.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Opens the system document picker — unless the app's manifest names a replacement activity in
 * `<meta-data android:name="ru.finassist.pf.DOCUMENT_PICKER_OVERRIDE" android:value="<activity class>"/>`.
 * Only the mock e2e build declares one (`app/src/mockE2e`): UI tests cannot drive the system picker
 * reliably, so they choose bundled files there. Both return a content Uri, so the upload itself runs the same
 * code either way.
 */
internal class PickStatementDocument : ActivityResultContract<Array<String>, Uri?>() {
    private val system = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: Array<String>): Intent {
        val replacement = replacementActivity(context) ?: return system.createIntent(context, input)
        return Intent().setClassName(context, replacement).putExtra(Intent.EXTRA_MIME_TYPES, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = system.parseResult(resultCode, intent)

    private fun replacementActivity(context: Context): String? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        info.metaData?.getString(OVERRIDE_KEY)
    }.getOrNull()

    private companion object {
        const val OVERRIDE_KEY = "ru.finassist.pf.DOCUMENT_PICKER_OVERRIDE"
    }
}
