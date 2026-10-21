package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The history sheet's reach past what the transcript has loaded.
 *
 * A session opens on its last sixty messages, and in a tool-heavy session that is a few prompts out
 * of dozens. The sheet lists only what is loaded, so these pin the way to the rest: a paging row
 * while older history exists, and no claim that nothing was sent when the window just holds none.
 */
class HistorySheetTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val loadOlder get() = context.getString(R.string.chat_load_older)
    private val empty get() = context.getString(R.string.chat_history_empty)

    private var pages = 0

    private fun sheet(entries: List<HistoryEntry>, hasMore: Boolean) {
        compose.setContent {
            DshTheme {
                HistorySheet(
                    entries = entries,
                    hasMore = hasMore,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    onLoadOlder = { pages++ },
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun olderHistoryCanBePagedInFromTheSheet() {
        sheet(listOf(HistoryEntry(seq = 4, text = "the newest prompt", hasImage = false, timeMs = null)), hasMore = true)

        compose.onNodeWithText("the newest prompt").assertIsDisplayed()
        compose.onNodeWithText(loadOlder).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, pages) }
    }

    @Test
    fun aWindowWithNoPromptsOffersOlderHistoryInsteadOfSayingNoneWereSent() {
        sheet(emptyList(), hasMore = true)

        compose.onNodeWithText(loadOlder).assertIsDisplayed()
        compose.onNodeWithText(empty).assertDoesNotExist()
    }

    @Test
    fun theWholeHistoryLoadedNeedsNoPagingRow() {
        sheet(emptyList(), hasMore = false)

        compose.onNodeWithText(empty).assertIsDisplayed()
        compose.onNodeWithText(loadOlder).assertDoesNotExist()
    }
}
