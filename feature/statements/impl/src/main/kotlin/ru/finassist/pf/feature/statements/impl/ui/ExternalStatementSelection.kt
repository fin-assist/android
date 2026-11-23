package ru.finassist.pf.feature.statements.impl.ui

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf

/** Resolves an in-memory external selection once the upload screen is ready. */
val LocalExternalStatementSelection = staticCompositionLocalOf<((String) -> Uri?)?> { null }
