package ru.finassist.pf.feature.profile.api

import kotlinx.coroutines.flow.Flow

enum class AppTheme { System, Light, Dark }

/**
 * Theme choice. Stored locally (needed before login and offline) and mirrored to `profile.theme` after login.
 */
interface ThemeRepository {
    val theme: Flow<AppTheme>
    suspend fun set(theme: AppTheme)
}
