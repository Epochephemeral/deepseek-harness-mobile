package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TrajectoryJournalTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private val records = listOf(SessionEventEnvelope("fixtureRaw", 5, 5, JsonNull))
    private fun content(load: suspend () -> List<SessionEventEnvelope>) {
        compose.setContent { DshTheme {
            TrajectoryTab(ConversationSnapshot("raw", loadedFromSeq = 0, loadedThroughSeq = 10),
                null, null, null, rememberLazyListState(), loadJournal = load)
        } }
        compose.waitForIdle()
    }

    @Test fun journalLoadsOnlyOnExpansionAndMustBeReadAgainAfterCollapse() {
        var loads = 0
        content { loads++; records }
        compose.runOnIdle { assertEquals(0, loads) }
        compose.onNodeWithText(label(R.string.trajectory_tab_raw)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("#5 · fixtureRaw", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, loads) }
        compose.onNodeWithText(label(R.string.trajectory_tab_raw)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("#5 · fixtureRaw", substring = true).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.trajectory_tab_raw)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, loads) }
    }

    @Test fun collapseCancelsPendingJournalAndFailureOffersOnlyExplicitRetry() {
        val release = CompletableDeferred<Unit>()
        var loads = 0
        var cancelled = false
        content {
            loads++
            when (loads) {
                1 -> { try { release.await(); records } finally { cancelled = true } }
                2 -> throw java.io.IOException("journal unavailable")
                else -> records
            }
        }
        compose.onNodeWithText(label(R.string.trajectory_tab_raw)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.common_loading)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.trajectory_tab_raw)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(cancelled); release.complete(Unit) }
        compose.onNodeWithText("#5 · fixtureRaw", substring = true).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.trajectory_tab_raw)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("journal unavailable", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, loads) }
        compose.onNodeWithText(label(R.string.common_retry)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("#5 · fixtureRaw", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(3, loads) }
    }
}
