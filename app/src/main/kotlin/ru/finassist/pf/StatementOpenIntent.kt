package ru.finassist.pf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

internal const val OFX_MIME_TYPE = "application/x-ofx"

/** Extracts a document URI for the importer to validate. The incoming intent is never forwarded. */
internal fun incomingStatementUri(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_VIEW || intent.type != OFX_MIME_TYPE) return null
    return intent.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT && !it.authority.isNullOrEmpty() }
}
