package ru.finassist.pf.feature.statements.impl.ui

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf

/** A file selected for import. Generic downloads require a local OFX check. */
data class StatementFile(val uri: Uri, val validateDownload: Boolean = false)

/** The current in-memory selection, taken only while the upload screen and its app gates are ready. */
class ExternalStatementSelection(val id: String, val take: () -> StatementFile?)

val LocalExternalStatementSelection = staticCompositionLocalOf<ExternalStatementSelection?> { null }
