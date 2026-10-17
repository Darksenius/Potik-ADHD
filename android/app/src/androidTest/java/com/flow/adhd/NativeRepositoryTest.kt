package com.flow.adhd

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.data.CommandCommit
import com.flow.adhd.data.NativeCommandRepository
import com.flow.adhd.data.NativeContent
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
        RoomCommandStore(database), FlowEngine(mapOf("simple" to 10.0, "counter" to 2.0, "sched" to 10.0)), { ZoneId.of("UTC") }, { at }, afterCommit = afterCommit,
    )
    private fun state() = FlowDatabase.callStorage { FlowSnapshot.parse(database.flowDao().stateJson) }
    private fun pending() = FlowDatabase.callStorage { database.flowDao().pendingEvents.map { it.id } }
    private fun enqueue() = FlowDatabase.callStorage {
        database.flowDao().insertEvent(PendingEvent("capture"))
        database.flowDao().pendingEvents.last().id
    }
    private fun queueRaw(event: String, createdAt: Long) = FlowDatabase.callStorage {
        database.flowDao().insertEvent(PendingEvent(event).apply { this.createdAt = createdAt })
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

    @Test fun everydayActionsAndLateNotificationTapRemainDurableWithoutTouchingTodaysProgress() = runBlocking {
        val counter = JsonObject(TaskFactory.create(300, "Розминка", "counter", at - 3 * 86_400_000L) + mapOf(
            "repeat" to JsonPrimitive("daily"), "counter" to JsonPrimitive(2), "counterDay" to JsonPrimitive("2026-10-16"),
        ))
        val reminder = JsonObject(TaskFactory.create(301, "Подзвонити", "sched", at - 86_400_000L) + mapOf(
            "schedDate" to JsonPrimitive("2026-10-16"), "schedTime" to JsonPrimitive("09:00"),
        ))
        FlowDatabase.callStorage { database.flowDao().writeState(JsonObject(FlowDefaults.create() + mapOf(
            "tasks" to JsonArray(listOf(counter, reminder)), "nid" to JsonPrimitive(302),
        )).toString()) }
        val repo = repository()
        suspend fun commit(command: FlowCommand) { assertTrue(repo.dispatch(command) is CommandCommit.Committed) }
        commit(FlowCommand.SetTaskEditorDraft("Не втратити чернетку", "draft", at))
        commit(FlowCommand.SetPlanDayNote("2026-10-16", "День без поспіху", "day-note", at))
        commit(FlowCommand.EditPlanZoneForDay("2026-10-16", 5, "edit-zone", at))
        commit(FlowCommand.SetPlanZoneOff("2026-10-16", 5, false, "restore-zone", at))
        commit(FlowCommand.SetZoneOff(5, true, "global-off", at))
        commit(FlowCommand.SetZoneOff(5, false, "global-on", at))
        commit(FlowCommand.CreateRareEvent("Перерва", "up", "rare", at))
        val rareId = state().data["rareEvents"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.long
        commit(FlowCommand.ChangeRareEvent(rareId, 3.0, "rare-inc", at))
        val capture = FlowCommand.CaptureNote("Ідея зі шторки", "capture-note", at)
        commit(capture)
        assertTrue((repository().dispatch(capture) as CommandCommit.Committed).replayed)
        val past = FlowCommand.RecordPastTaskDone(300, at - 86_400_000L, "past-done", at - 86_400_000L)
        commit(past)
        commit(FlowCommand.RecordPastTaskDone(300, at - 86_400_000L, "past-done-again", at - 86_400_000L))
        commit(FlowCommand.MarkTaskReminderFired(301, at, false, "reminder", at))
        commit(FlowCommand.ResetPlanDay("2026-10-16", "reset-plan", at))
        val saved = (repository().read() as SnapshotRead.Ready).snapshot
        assertEquals(counter, saved.tasks.first { it.id == 300L }.raw)
        assertEquals(JsonPrimitive(true), saved.tasks.first { it.id == 301L }.raw["firedSched"])
        assertEquals(JsonPrimitive(3), saved.data["xpTotal"])
        assertEquals(1, saved.qnotes.size)
        assertEquals(JsonPrimitive("Не втратити чернетку"), saved.data["taskEditorDraft"])
        assertEquals(JsonPrimitive("День без поспіху"), saved.data["planDayLog"]!!.jsonObject["2026-10-16"]!!.jsonObject["note"])
        assertFalse(saved.data["planDayZones"]!!.jsonObject.containsKey("2026-10-16"))
        assertEquals(1, saved.data["taskHistory"]!!.jsonArray.size)
    }

    @Test fun queuedCaptureDoneAndNotesCommitWithoutAnActivityAndUpdateTheNotificationSnapshot() = runBlocking {
        val capture = "capture_task_v2:600:" + buildJsonObject { put("title", "Зі шторки"); put("createdAt", at) }
        val first = queueRaw(capture, at)
        queueRaw("task_done:600", at + 1)
        queueRaw("capture_note:Швидка думка", at + 2)
        queueRaw("share_text:Зберегти з іншого застосунку", at + 3)
        val repo = NativeCommandRepository(RoomCommandStore(database), FlowEngine(mapOf("simple" to 10.0)), { ZoneId.of("UTC") }, { at + 10 })
        val inbox = NativeNotificationInbox(database, repo)
        assertTrue(inbox.drain() is CommandCommit.Committed)
        assertTrue(state().tasks.single().done)
        assertEquals(JsonPrimitive(21), state().data["xpTotal"])
        assertEquals(2, state().qnotes.size)
        assertTrue(pending().isEmpty())
        val notif = FlowDatabase.callStorage { Json.parseToJsonElement(database.flowDao().notifJson) as JsonObject }
        assertEquals(JsonPrimitive(true), notif["questTasks"]!!.jsonArray.single().jsonObject["done"])
        // Re-delivery of the same Room envelope after a crash cannot award or capture again.
        FlowDatabase.callStorage { database.flowDao().insertEvent(PendingEvent(capture).apply { id = first; createdAt = at }) }
        assertTrue((inbox.drain() as CommandCommit.Committed).replayed)
        assertEquals(JsonPrimitive(21), state().data["xpTotal"])
        assertEquals(2, state().qnotes.size)
    }

    @Test fun invalidQueuedActionCannotCommitOrAcknowledgeAValidPrefix() = runBlocking {
        queueRaw("capture_task:Не загубити", at)
        val malformed = queueRaw("unknown-command:17", at + 1)
        queueRaw("capture_note:Ще одна думка", at + 2)
        val before = pending()
        val inbox = NativeNotificationInbox(database, repository())
        assertTrue(inbox.drain() is CommandCommit.Rejected)
        assertEquals(FlowSnapshot.parse(initial).data, state().data)
        assertEquals(before, pending())
        assertNull(FlowDatabase.callStorage { database.flowDao().commandJournalJson })
        FlowDatabase.callStorage { database.openHelper.writableDatabase.execSQL("UPDATE pending_events SET event = ? WHERE id = ?", arrayOf("task_remind:17", malformed)) }
        assertTrue(inbox.drain() is CommandCommit.Committed)
        assertTrue(pending().isEmpty())
        assertEquals(1, state().tasks.size)
        assertEquals(1, state().qnotes.size)
    }

    @Test fun drainingAPrefixDoesNotResetProgressBeforeALaterQueuedDeferral() = runBlocking {
        val yesterday = at - 86_400_000L
        val requested = yesterday + 10 * 3_600_000L
        val task = JsonObject(TaskFactory.create(300, "Розминка", "counter", yesterday - 86_400_000L, 5) + mapOf(
            "repeat" to JsonPrimitive("daily"), "counterDay" to JsonPrimitive("2026-10-15"),
            "counter" to JsonPrimitive(7), "counterCreditedProgress" to JsonPrimitive(7),
        ))
        FlowDatabase.callStorage { database.flowDao().writeState(JsonObject(FlowDefaults.create() + mapOf(
            "tasks" to JsonArray(listOf(task)), "saveDate" to JsonPrimitive("Thu Oct 15 2026"),
        )).toString()) }
        queueRaw("capture_note:Спочатку думка", requested)
        val deferral = buildJsonObject {
            put("kind", "not-today"); put("requestedAt", requested + 1000); put("sourceZoneId", 5); put("targetZoneId", 5)
            put("startAt", Instant.parse("2026-10-16T00:00:00Z").toEpochMilli())
        }
        queueRaw("task_defer:300:$deferral", requested + 1000)
        val inbox = NativeNotificationInbox(database, repository())
        assertTrue(inbox.drain(1) is CommandCommit.Committed)
        assertEquals(task, state().tasks.single().raw)
        assertEquals(1, pending().size)
        assertTrue(inbox.drain(1) is CommandCommit.Committed)
        val saved = state().tasks.single().raw
        assertEquals(JsonPrimitive(7), saved["counter"])
        assertEquals(JsonPrimitive("2026-10-15"), saved["counterDay"])
        assertEquals(deferral, saved["questDeferral"])
        assertTrue(pending().isEmpty())
    }

    @Test fun installedApkContainsAllTaskTypesAnd100InsightCardsWithoutNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val content = NativeContent.load(context)
        assertEquals(10, content.taskXp.size)
        assertEquals((1..100).toSet(), content.insights.keys)
        val repo = NativeCommandRepository(RoomCommandStore(database), FlowEngine(content.taskXp), { ZoneId.of("UTC") }, { at },
            notificationProjection = { snapshot, now, zone -> NotificationProjection.build(snapshot, now, zone, content.insights) })
        assertTrue(repo.dispatch(FlowCommand.CaptureNote("Думка для активного дня", "offline-content", at)) is CommandCommit.Committed)
        val notification = FlowDatabase.callStorage { Json.parseToJsonElement(database.flowDao().notifJson) as JsonObject }
        assertEquals(JsonPrimitive(1), notification["dailyInsight"]!!.jsonObject["cardId"])
        assertEquals(content.insights.getValue(1)["text"], notification["dailyInsight"]!!.jsonObject["text"])
    }

    @Test fun startupArchivesIncompatibleStateAndCorruptJournalBeforeReplacingThem() = runBlocking {
        val raw = """{"formatVersion":999,"original":"preserve"}"""
        FlowDatabase.callStorage {
            database.flowDao().writeState(raw)
            database.flowDao().saveState(AppState("broken journal", "").apply { id = 2 })
        }
        val archives = mutableListOf<Pair<String, String>>()
        val blocked = com.flow.adhd.data.SnapshotPreparation(setOf("simple"), { _, _ -> false })
        assertTrue(repository().initialize(blocked) is CommandCommit.Failed)
        assertEquals(raw, FlowDatabase.callStorage { database.flowDao().stateJson })
        val preparation = com.flow.adhd.data.SnapshotPreparation(setOf("simple"), { source, bytes -> archives += source to bytes; true })
        assertTrue(repository().initialize(preparation) is CommandCommit.Committed)
        assertTrue(state().tasks.isEmpty())
        assertTrue(archives.contains("native-incompatible-state" to raw))
        assertTrue(archives.contains("native-command-journal" to "broken journal"))
        assertEquals(JsonPrimitive(1), Json.parseToJsonElement(FlowDatabase.callStorage { database.flowDao().commandJournalJson })!!.jsonObject["version"])
    }

    @Test fun invalidQueuedEventIsAcknowledgedOnlyAfterItsExactPayloadIsArchived() = runBlocking {
        queueRaw("capture_task:Keep valid prefix", at)
        val invalid = queueRaw("unknown-event:keep exact bytes", at)
        queueRaw("capture_note:Keep valid suffix", at)
        val archived = mutableListOf<com.flow.adhd.data.QueuedNotification>()
        val blocked = NativeNotificationInbox(database, repository()) { _, _ -> false }
        assertTrue(blocked.drain() is CommandCommit.Rejected)
        assertEquals(3, pending().size)
        assertTrue(state().tasks.isEmpty())
        val inbox = NativeNotificationInbox(database, repository()) { envelope, _ -> archived += envelope; true }
        assertTrue(inbox.drain() is CommandCommit.Committed)
        assertEquals(1, archived.size)
        assertEquals(invalid, archived.single().id)
        assertEquals("unknown-event:keep exact bytes", archived.single().event)
        assertTrue(pending().isEmpty())
        assertEquals("Keep valid prefix", state().tasks.single().title)
        assertEquals(JsonPrimitive("Keep valid suffix"), state().qnotes.single()["txt"])
        assertEquals(11.0, state().xpTotal, 0.0)
    }
}
