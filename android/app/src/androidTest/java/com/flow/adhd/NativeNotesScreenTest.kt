package com.flow.adhd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.FlowDefaults
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.ui.FlowTheme
import com.flow.adhd.ui.NativeNotesScreen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated UI contract checks; no application repository or user data is opened. */
@RunWith(AndroidJUnit4::class)
class NativeNotesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun filingRequestsTheCurrentDraftWithoutClearingItPrematurely() {
        val draft = mutableStateOf("Перша думка")
        var filedFolder: String? = null
        var filingRequested = false
        compose.setContent {
            FlowTheme {
                NativeNotesScreen(
                    snapshot = fixture(),
                    notepadText = draft.value,
                    saveStatus = "Чернетку збережено",
                    busy = false,
                    onNotepadChange = { draft.value = it },
                    onCommand = { _, _ -> },
                    onSaveNotepadAsNote = { folder, _ -> filedFolder = folder; filingRequested = true },
                    onMakeTask = {},
                )
            }
        }
        compose.onNodeWithTag("native-notepad").performTextReplacement("Думка після зміни")
        scrollTo("native-notepad-save")
        compose.onNodeWithTag("native-notepad-save").performClick()
        compose.runOnIdle {
            assertTrue(filingRequested)
            assertEquals("thought", filedFolder)
            assertEquals("Думка після зміни", draft.value)
        }
        scrollTo("native-notepad")
        compose.onNodeWithTag("native-notepad").assertTextContains("Думка після зміни")
    }

    @Test
    fun editorKeepsChangesUntilDurableSuccessIsConfirmed() {
        var requested: FlowCommand? = null
        var afterCommit: (() -> Unit)? = null
        compose.setContent {
            FlowTheme {
                NativeNotesScreen(
                    snapshot = fixture(withNote = true),
                    notepadText = "Незавершена чернетка",
                    saveStatus = "Чернетку збережено",
                    busy = false,
                    onNotepadChange = {},
                    onCommand = { command, committed -> requested = command; afterCommit = committed },
                    onSaveNotepadAsNote = { _, _ -> },
                    onMakeTask = {},
                )
            }
        }
        scrollTo("native-note-edit-100")
        compose.onNodeWithTag("native-note-edit-100").performClick()
        compose.onNodeWithTag("native-note-editor").performTextReplacement("Змінений текст")
        compose.onNodeWithTag("native-note-save-100").performScrollTo().performClick()
        compose.onNodeWithTag("native-note-editor").assertExists()
        compose.onNodeWithTag("native-notepad").assertDoesNotExist()
        compose.runOnIdle {
            val update = requested as FlowCommand.UpdateQuickNote
            assertEquals(100L, update.noteId)
            assertEquals("Змінений текст", update.payload["txt"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
            assertTrue(update.commandId.isNotBlank())
            assertTrue(update.activityAt > 0L)
            assertNotNull(afterCommit)
            afterCommit!!.invoke()
        }
        compose.onNodeWithTag("native-note-editor").assertDoesNotExist()
        scrollTo("native-notepad")
        compose.onNodeWithTag("native-notepad").assertTextContains("Незавершена чернетка")
    }

    @Test
    fun deleteConfirmationStaysOpenUntilDurableSuccessIsConfirmed() {
        var requested: FlowCommand? = null
        var afterCommit: (() -> Unit)? = null
        compose.setContent {
            FlowTheme {
                NativeNotesScreen(
                    snapshot = fixture(withNote = true),
                    notepadText = "",
                    saveStatus = "Автозбереження увімкнено",
                    busy = false,
                    onNotepadChange = {},
                    onCommand = { command, committed -> requested = command; afterCommit = committed },
                    onSaveNotepadAsNote = { _, _ -> },
                    onMakeTask = {},
                )
            }
        }
        scrollTo("native-note-delete-100")
        compose.onNodeWithTag("native-note-delete-100").performClick()
        compose.onNodeWithTag("native-note-delete-confirm").performClick()
        compose.onNodeWithTag("native-note-delete-confirm").assertExists()
        compose.runOnIdle {
            val deletion = requested as FlowCommand.DeleteQuickNote
            assertEquals(100L, deletion.noteId)
            assertTrue(deletion.commandId.isNotBlank())
            assertTrue(deletion.activityAt > 0L)
            assertNotNull(afterCommit)
            afterCommit!!.invoke()
        }
        compose.onNodeWithTag("native-note-delete-confirm").assertDoesNotExist()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("native-notes").performScrollToNode(hasTestTag(tag))
    }

    private fun fixture(withNote: Boolean = false): FlowSnapshot {
        val data = FlowDefaults.create().toMutableMap()
        if (withNote) data["qnotes"] = JsonArray(listOf(buildJsonObject {
            put("id", 100)
            put("txt", "Збережена думка")
            put("folder", "thought")
            put("date", "2026-10-17")
            put("time", "12:30")
        }))
        return FlowSnapshot.parse(JsonObject(data).toString())
    }
}
