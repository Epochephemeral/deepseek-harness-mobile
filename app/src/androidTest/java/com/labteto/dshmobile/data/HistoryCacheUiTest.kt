package com.labteto.dshmobile.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.ui.screens.settings.HistoryCacheSection
import com.labteto.dshmobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Tests the production settings section with real disk usage and the same clear operation. */
class HistoryCacheUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightSectionShowsSizeAndClearsDisk() = checkSection(ThemePreference.LIGHT, 1f)
    @Test fun darkSectionSupportsLargeTextAndEmptyState() = checkSection(ThemePreference.DARK, 2f)

    @Test fun capacityPreferenceIsPersisted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = com.labteto.dshmobile.di.AppModule.provideDataStore(context)
        val credentials = com.labteto.dshmobile.connection.RelayCredentialStore(data)
        val hosts = com.labteto.dshmobile.connection.HostsStore(data, credentials, context)
        val before = hosts.settingsOnce()
        try {
            hosts.setSetting { it.copy(historyCacheLimitBytes = 1_000_000_000L) }
            val restored = com.labteto.dshmobile.connection.HostsStore(data, credentials, context).settingsOnce()
            assertEquals(1_000_000_000L, restored.historyCacheLimitBytes)
            assertEquals(before.copy(historyCacheLimitBytes = 1_000_000_000L), restored)
        } finally {
            hosts.setSetting { it.copy(historyCacheLimitBytes = before.historyCacheLimitBytes) }
        }
    }

    private fun checkSection(theme: ThemePreference, fontScale: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = HistoryCache(File(context.cacheDir, "history-cache-ui-test"))
        runBlocking {
            cache.clear()
            cache.write("ui-test", "session", cache.ticket(), listOf(
                SessionEventEnvelope("turn/start", 1, 1, JsonObject(emptyMap()))))
        }
        val clearLabel = context.getString(R.string.settings_history_cache_clear)
        compose.setContent {
            DshTheme(theme) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    val usage by cache.usage.collectAsState()
                    val scope = rememberCoroutineScope()
                    var clearing by remember { mutableStateOf(false) }
                    var limit by remember { mutableStateOf(com.labteto.dshmobile.connection.DEFAULT_HISTORY_CACHE_LIMIT_BYTES) }
                    Column(Modifier.widthIn(max = 340.dp).fillMaxHeight()
                        .background(DsTheme.colors.bgBase).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HistoryCacheSection(usage, clearing, limitBytes = limit, onLimitChange = {
                            limit = it
                            scope.launch { cache.setLimitBytes(it) }
                        }) {
                            clearing = true
                            scope.launch { try { cache.clear() } finally { clearing = false } }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_history_cache)).assertIsDisplayed()
        val size = android.text.format.Formatter.formatFileSize(context, cache.usage.value!!.bytes)
        compose.onNodeWithText(context.getString(R.string.settings_history_cache_usage, size)).assertIsDisplayed()
        val defaultLimit = android.text.format.Formatter.formatShortFileSize(context, 2_000_000_000L)
        val newLimit = android.text.format.Formatter.formatShortFileSize(context, 1_000_000_000L)
        compose.onNodeWithText(defaultLimit).performScrollTo().performClick()
        compose.onNodeWithText(newLimit).performClick()
        compose.onNodeWithText(newLimit).assertIsDisplayed()
        compose.onNodeWithText(clearLabel).performScrollTo().assertIsEnabled()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "history-cache-ui-${theme.name.lowercase()}.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(clearLabel).performClick()
        compose.waitUntil(5_000) { cache.usage.value?.bytes == 0L }
        compose.waitForIdle()
        compose.onNodeWithText(clearLabel).assertIsNotEnabled()
        assertEquals(HistoryCacheUsage(), cache.usage.value)
    }
}
