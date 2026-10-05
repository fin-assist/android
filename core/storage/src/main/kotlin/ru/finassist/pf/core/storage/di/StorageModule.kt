package ru.finassist.pf.core.storage.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.storage.EncryptedTokenStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class StorageBindings {
    @Binds
    abstract fun tokenStore(impl: EncryptedTokenStore): TokenStore
}

@Module
@InstallIn(SingletonComponent::class)
internal object StorageModule {
    /** One preferences DataStore for the whole app; modules keep their own key namespaces (`<feature>.<name>`). */
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("pf_prefs") }
}
