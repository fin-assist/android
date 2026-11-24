package ru.finassist.pf.feature.statements.impl.ui

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf

/** The current in-memory selection, taken only while the upload screen and its app gates are ready. */
class ExternalStatementSelection(val id: String, val take: () -> Uri?)

val LocalExternalStatementSelection = staticCompositionLocalOf<ExternalStatementSelection?> { null }
