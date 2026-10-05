package ru.finassist.pf.feature.analytics.impl.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.client.apiCall
import ru.finassist.pf.core.network.dto.AnalyticsDto
import ru.finassist.pf.core.network.dto.AnalyticsPeriodItemDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsRepository @Inject constructor(private val api: PfApi) {
    suspend fun analytics(period: String?, date: String?, transferMode: String): AnalyticsDto =
        withContext(Dispatchers.IO) { apiCall { api.getAnalytics(period, date, transferMode) } }

    suspend fun periods(period: String): List<AnalyticsPeriodItemDto> =
        withContext(Dispatchers.IO) { apiCall { api.listAnalyticsPeriods(period) } }.periods

    /** «Не подписка» — the mark survives re-imports (api.md 6.3). */
    suspend fun dismissRegularPayment(id: String) = withContext(Dispatchers.IO) { apiCall { api.dismissRegularPayment(id) } }
}

private val Context.analyticsPrefs: DataStore<Preferences> by preferencesDataStore("analytics_prefs")

/** Coachmarks dismissed on «Аналитика» and the last chosen transfer mode (a screen-wide setting, kept between launches). */
@Singleton
class AnalyticsPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val modeKey = stringPreferencesKey("transfer_mode")

    fun dismissed(id: String): Flow<Boolean> = context.analyticsPrefs.data.map { it[booleanPreferencesKey(id)] ?: false }
    suspend fun dismiss(id: String) = context.analyticsPrefs.edit { it[booleanPreferencesKey(id)] = true }

    val transferMode: Flow<String?> = context.analyticsPrefs.data.map { it[modeKey] }
    suspend fun setTransferMode(mode: String) = context.analyticsPrefs.edit { it[modeKey] = mode }
}
