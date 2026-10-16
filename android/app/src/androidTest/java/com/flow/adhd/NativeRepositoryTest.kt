package com.flow.adhd

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.data.CommandCommit
import com.flow.adhd.data.NativeCommandRepository
import com.flow.adhd.domain.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room transactions on the dedicated emulator; never opens the user's database. */
@RunWith(AndroidJUnit4::class)
class NativeRepositoryTest {
    private lateinit var database: FlowDatabase
    private val at = Instant.parse("2026-10-16T09:00:00Z").toEpochMilli()
    private val initial = """{"formatVersion":2,"tasks":[],"zones":[],"qnotes":[],"nid":300,
        "xp":0,"xpTotal":0,"level":1,"progressionVersion":2,"progressDays":[],
        "dailyInsightClaims":[],"taskHistory":[],"custom":"keep"}"""

    @Before fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, FlowDatabase::class.java).build()
        FlowDatabase.callStorage { database.flowDao().writeState(initial) }
    }
    @After fun closeDatabase() { database.close() }
    private fun repository(afterCommit: (String) -> Unit = {}) = NativeCommandRepository(
        RoomCommandStore(database), FlowEngine(mapOf("simple" to 10.0)), { ZoneId.of("UTC") }, { at }, afterCommit = afterCommit,
    )
    private fun state() = FlowDatabase.callStorage { FlowSnapshot.parse(database.flowDao().stateJson) }
    private fun pending() = FlowDatabase.callStorage { database.flowDao().pendingEvents.map { it.id } }
    private fun enqueue() = FlowDatabase.callStorage {
        database.flowDao().insertEvent(PendingEvent("capture"))
        database.flowDao().pendingEvents.last().id
    }

    @Test fun replayAfterRepositoryRecreationDoesNotRepeatCaptureOrXpAndAcknowledgesOnlyExactEvents() = runBlocking {
        val first = enqueue(); val later = enqueue()
        val command = FlowCommand.CaptureTask("Зі шторки", "native-capture", at, CaptureOrigin.NOTIFICATION, 500)
        val result = repository().dispatch(command, listOf(first))
        assertTrue(result is CommandCommit.Committed && !result.replayed)
        assertEquals(listOf(later), pending())
        val before = state().data
        val replay = repository().dispatch(command, listOf(first))
        assertTrue(replay is CommandCommit.Committed && replay.replayed && replay.events.isEmpty())
        assertEquals(before, state().data)
        assertEquals(1, state().tasks.size)
        assertEquals(JsonPrimitive(10), state().data["xpTotal"])
        assertEquals(listOf(later), pending())
        // Legacy snapshot writes keep reserved row 2 intact, with schema still version 1.
        FlowDatabase.callStorage { database.flowDao().writeState(state().toJson()) }
        assertTrue(repository().dispatch(command) is CommandCommit.Committed)
    }

    @Test fun sqliteFailureRollsBackStateJournalAndPendingAcknowledgement() = runBlocking {
        val queued = enqueue()
        val repo = repository()
        repo.read()
        FlowDatabase.callStorage {
            database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_command_receipt BEFORE INSERT ON app_state
                WHEN NEW.id = 2 BEGIN SELECT RAISE(ABORT, 'injected receipt write failure'); END""")
        }
        val command = FlowCommand.CaptureTask("Не загубити", "retry-after-failure", at)
        assertTrue(repo.dispatch(command, listOf(queued)) is CommandCommit.Failed)
        assertEquals(FlowSnapshot.parse(initial).data, state().data)
        assertEquals(listOf(queued), pending())
        assertNull(FlowDatabase.callStorage { database.flowDao().commandJournalJson })
        assertEquals(FlowSnapshot.parse(initial).data, (repo.state.value as SnapshotRead.Ready).snapshot.data)
        FlowDatabase.callStorage { database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_command_receipt") }
        assertTrue(repo.dispatch(command, listOf(queued)) is CommandCommit.Committed)
        assertTrue(pending().isEmpty())
        assertEquals(1, state().tasks.size)
    }

    @Test fun concurrentCommandsReadTheLatestCommittedStateAndBackupFailureDoesNotUndoCommit() = runBlocking {
        val repo = repository { error("Backup destination unavailable") }
        coroutineScope {
            (1..20).map { index -> async { repo.dispatch(FlowCommand.CaptureTask("Задача $index", "parallel-$index", at)) } }
                .awaitAll().forEach { assertTrue(it is CommandCommit.Committed) }
        }
        assertEquals(20, state().tasks.size)
        assertEquals(20, state().tasks.map { it.id }.toSet().size)
        assertEquals(JsonPrimitive("keep"), state().data["custom"])
        assertEquals(state().data, (repo.state.value as SnapshotRead.Ready).snapshot.data)
    }

    @Test fun corruptJournalAndReusedIdWithDifferentPayloadFailWithoutAcknowledgement() = runBlocking {
        val first = FlowCommand.CaptureTask("Перша", "same-id", at)
        val repo = repository()
        assertTrue(repo.dispatch(first) is CommandCommit.Committed)
        val queued = enqueue()
        val before = state().data
        val conflict = repo.dispatch(FlowCommand.CaptureTask("Інша", "same-id", at), listOf(queued))
        assertTrue(conflict is CommandCommit.Rejected && conflict.code == "command-id-conflict")
        assertEquals(before, state().data)
        assertEquals(listOf(queued), pending())
        FlowDatabase.callStorage { database.flowDao().saveState(AppState("broken", "").apply { id = 2 }) }
        val failed = repo.dispatch(FlowCommand.CaptureTask("Третя", "new-id", at), listOf(queued))
        assertTrue(failed is CommandCommit.Failed && failed.code == "invalid_command_journal")
        assertEquals(before, state().data)
        assertEquals(listOf(queued), pending())
    }

    @Test fun restoreMergeAndPreferencesPersistAcrossRepositoryRecreationWithoutDuplicateTasksOrNotes() = runBlocking {
        val source = Json.parseToJsonElement("""{"formatVersion":2,"tasks":[
          {"id":5,"title":"Підмести","type":"simple","created":"2026-10-16T08:00:00.000Z",
           "dayPresetKey":"home:sweep:2026-10-16","zoneId":5,"custom":{"keep":true}}],
          "qnotes":[{"id":1000,"txt":"Думка","date":"2026-10-16","time":"09:00","folder":"general"}],
          "zones":[{"id":5,"nm":"Вечір","slots":[{"s":"18:00","e":"22:00"}]}],
          "dayTemplates":[{"id":"home","name":"Дім","blocks":[],
            "tasks":[{"id":"sweep","task":{"title":"Підмести","type":"simple","zoneId":5}}]}]}""") as JsonObject
        val repo = repository()
        assertTrue(repo.dispatch(FlowCommand.RestoreSnapshot(source, "restore", at)) is CommandCommit.Committed)
        assertTrue(repo.dispatch(FlowCommand.ImportSnapshot(source, "merge-1", at)) is CommandCommit.Committed)
        assertTrue(repository().dispatch(FlowCommand.ImportSnapshot(source, "merge-2", at)) is CommandCommit.Committed)
        assertEquals(1, state().tasks.size)
        assertEquals(1, state().qnotes.size)
        assertEquals(JsonPrimitive("home:sweep:2026-10-16"), state().tasks.single().raw["dayPresetKey"])
        assertEquals(JsonPrimitive("keep"), state().data["custom"])
        val prefs = buildJsonObject { put("themeMode", "light"); put("reduceMotion", true) }
        assertTrue(repo.dispatch(FlowCommand.SetPreferences(prefs, "settings", at)) is CommandCommit.Committed)
        assertTrue(repo.dispatch(FlowCommand.AddQuickNote(buildJsonObject { put("txt", "Ще одна"); put("folder", "general") }, "note", at)) is CommandCommit.Committed)
        assertEquals(setOf(1000L, 1001L), state().qnotes.map { it["id"]!!.jsonPrimitive.long }.toSet())
        assertEquals(JsonPrimitive("light"), state().data["theme"])
        assertEquals(JsonPrimitive(true), state().data["preferences"]!!.jsonObject["reduceMotion"])
        assertEquals(JsonPrimitive(0), state().data["xpTotal"])
    }
}
