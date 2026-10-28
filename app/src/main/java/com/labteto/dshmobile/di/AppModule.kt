package com.labteto.dshmobile.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.labteto.dshmobile.core.wire.WireJson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "dsh_mobile")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDiskCacheStore(@ApplicationContext context: Context) =
        com.labteto.dshmobile.core.storage.DiskCacheStore(java.io.File(context.noBackupFilesDir, "managed-cache"))

    @Provides
    @Singleton
    fun provideHistoryCache(
        @ApplicationContext context: Context,
        store: com.labteto.dshmobile.core.storage.DiskCacheStore,
    ): com.labteto.dshmobile.data.HistoryCache =
        // Retain the existing disposable preview location and capacity setting. The full archive
        // and processing spools live in noBackupFilesDir; both are counted/cleared by HistoryCache.
        com.labteto.dshmobile.data.HistoryCache(
            java.io.File(context.cacheDir, "session-history"), diskStore = store,
        )

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWireJson(): Json = WireJson

    @Provides
    fun provideIoDispatcher(): kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
}
