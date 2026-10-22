package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test

/**
 * The changed-files block ends a turn closed.
 *
 * A turn that touched a dozen files used to stack a dozen pills over the reply. Only the count
 * belongs in the transcript, so these pin that the list stays out of the way until it is asked for.
 */
class ChangedFilesRowTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val summary get() = context.resources.getQuantityString(R.plurals.chat_changed_files, 2, 2)

    private fun call(seq: Long, id: String, name: String, path: String) =
        ToolCallNode(seq, id, name, """{"file_path":"$path"}""", 1, 1)

    private fun result(seq: Long, id: String) = ToolResultNode(seq, id, JsonPrimitive("ok"), false, 1, 1)

    private fun row() {
        val nodes = listOf(
            call(1, "a", "edit", "src/todos.js"), result(2, "a"),
            call(3, "b", "write", "test/pagination.test.js"), result(4, "b"),
        )
        compose.setContent {
            DshTheme {
                ChangedFilesRow(nodes = nodes, turn = 1, cwd = null)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theFilesAreHiddenUntilTheRowIsOpened() {
        row()

        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithText(technicalDisplay("src/todos.js")).assertDoesNotExist()
        compose.onNodeWithText(technicalDisplay("test/pagination.test.js")).assertDoesNotExist()
    }

    @Test
    fun tappingTheCountRevealsTheFilesAndTappingItAgainHidesThem() {
        row()

        compose.onNodeWithText(summary).performClick()
        compose.onNodeWithText(technicalDisplay("src/todos.js")).assertIsDisplayed()
        compose.onNodeWithText(technicalDisplay("test/pagination.test.js")).assertIsDisplayed()

        compose.onNodeWithText(summary).performClick()
        compose.onNodeWithText(technicalDisplay("src/todos.js")).assertDoesNotExist()
    }
}
