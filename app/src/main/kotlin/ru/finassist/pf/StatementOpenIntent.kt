package ru.finassist.pf

import android.content.ContentResolver
import android.content.Intent
import ru.finassist.pf.feature.statements.impl.ui.StatementFile

internal const val OFX_MIME_TYPE = "application/x-ofx"
internal const val BINARY_MIME_TYPE = "application/octet-stream"
private val mediaStoreDownloadPath = Regex("/external(?:_primary)?/downloads/[0-9]+")

/** Extracts a document URI for the importer to validate. The incoming intent is never forwarded. */
internal fun incomingStatementFile(intent: Intent): StatementFile? {
    if (intent.action != Intent.ACTION_VIEW) return null
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val uri = intent.data ?: return null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty()) return null
    return when (intent.type) {
        OFX_MIME_TYPE -> StatementFile(uri)
        BINARY_MIME_TYPE -> {
            val path = uri.encodedPath ?: return null
            if (uri.authority != "media" || !mediaStoreDownloadPath.matches(path)) return null
            StatementFile(uri, validateDownload = true)
        }
        else -> null
    }
}
