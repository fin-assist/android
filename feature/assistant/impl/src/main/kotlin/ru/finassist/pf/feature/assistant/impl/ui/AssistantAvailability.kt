package ru.finassist.pf.feature.assistant.impl.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The composition root's immutable main-layer assistant snapshot, shared by its tab and all route guards.
 * Missing ownership is disabled: a standalone/restored route cannot create an assistant ViewModel by default.
 */
val LocalAssistantEnabled = staticCompositionLocalOf { false }
