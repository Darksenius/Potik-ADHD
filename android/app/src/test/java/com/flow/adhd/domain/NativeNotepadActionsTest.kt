package com.flow.adhd.domain

import com.flow.adhd.data.CommandCommit
import com.flow.adhd.data.CommandStore
import com.flow.adhd.data.NativeCommandRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two one-tap notepad actions clear the draft and reward it in one replayable commit. */
class NativeNotepadActionsTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")

    @Test
    fun filingCreatesOneNoteClearsTheDraftAndAwardsOnePointOnlyOnce() = runBlocking {
        val store = NotepadStore(fixture("  Зберегти думку\nДругий рядок  "))
        val command = FlowCommand.FileNotepadAsNote("impulse", "file-draft", now)
        val first = repository(store).dispatch(command) as CommandCommit.Committed
        assertFalse(first.replayed)
        assertEquals("", first.snapshot.notepad)
        assertEquals(1.0, first.snapshot.xpTotal, 0.0)
        assertEquals(1, first.snapshot.qnotes.size)
        val note = first.snapshot.qnotes.single()
        assertEquals(JsonPrimitive("Зберегти думку\nДругий рядок"), note["txt"])
        assertEquals(JsonPrimitive("impulse"), note["folder"])
        val review = first.events.single { it.type == "note_review_requested" }
        assertEquals(note["id"], review.payload["noteId"])
        assertEquals(JsonPrimitive(now + 300_000L), review.payload["dueAt"])
        val committed = store.snapshot
        val replay = repository(store).dispatch(command) as CommandCommit.Committed
        assertTrue(replay.replayed)
        assertTrue(replay.events.isEmpty())
        assertEquals(committed, store.snapshot)
        assertEquals(first.snapshot.data, replay.snapshot.data)
    }

    @Test
    fun taskConversionRollsBackDraftClearOnFailureThenCommitsTitleBodyAndTenPointBonusOnce() = runBlocking {
        val draft = "... Забрати посилку\nАдреса: пошта\nКод: 1234"
        val store = NotepadStore(fixture(draft))
        val command = FlowCommand.ConvertNotepadToTask("convert-draft", now)
        val before = store.snapshot
        store.failNextJournal = true
        assertTrue(repository(store).dispatch(command) is CommandCommit.Failed)
        assertEquals(before, store.snapshot)
        assertEquals(draft, FlowSnapshot.parse(store.snapshot).notepad)
        assertEquals(null, store.journal)
        assertEquals(null, store.notification)
        val committed = repository(store).dispatch(command) as CommandCommit.Committed
        assertEquals("", committed.snapshot.notepad)
        assertEquals(10.0, committed.snapshot.xpTotal, 0.0)
        val task = committed.snapshot.tasks.single()
        assertEquals("Забрати посилку", task.title)
        assertEquals("simple", task.type)
        assertEquals(JsonPrimitive("Адреса: пошта\nКод: 1234"), task.raw["note"])
        assertFalse(task.done)
        assertTrue(committed.snapshot.qnotes.isEmpty())
        assertEquals(JsonPrimitive("keep"), committed.snapshot.data["custom"])
        val saved = store.snapshot
        val replay = repository(store).dispatch(command) as CommandCommit.Committed
        assertTrue(replay.replayed)
        assertEquals(saved, store.snapshot)
        assertEquals(10.0, replay.snapshot.xpTotal, 0.0)
        assertEquals(1, replay.snapshot.tasks.size)
    }

    @Test
    fun bothCommandKindsRoundTripAndRejectedEmptyConversionsPreserveTheDraft() = runBlocking {
        val commands = listOf(
            FlowCommand.FileNotepadAsNote(null, "file-default", now),
            FlowCommand.FileNotepadAsNote("thought", "file-folder", now),
            FlowCommand.ConvertNotepadToTask("convert-empty", now),
        )
        commands.forEach { command ->
            val encoded = FlowCommandCodec.encode(command)
            assertEquals(command, FlowCommandCodec.decode(encoded))
            assertEquals(encoded, FlowCommandCodec.encode(FlowCommandCodec.decode(encoded)))
            val store = NotepadStore(fixture(" \n \t "))
            val before = store.snapshot
            val rejected = repository(store).dispatch(command) as CommandCommit.Rejected
            assertEquals("empty-notepad", rejected.code)
            assertEquals(before, store.snapshot)
            assertEquals(null, store.journal)
        }
        val noTitle = NotepadStore(fixture("...   "))
        val before = noTitle.snapshot
        val rejected = repository(noTitle).dispatch(FlowCommand.ConvertNotepadToTask("no-title", now)) as CommandCommit.Rejected
        assertEquals("empty-title", rejected.code)
        assertEquals(before, noTitle.snapshot)
        assertEquals(null, noTitle.journal)
    }

    private fun repository(store: NotepadStore) = NativeCommandRepository(store,
        FlowEngine(mapOf("simple" to 10.0)), { zone }, { now })

    private fun fixture(draft: String) = JsonObject(FlowDefaults.create() + mapOf(
        "notepad" to JsonPrimitive(draft), "custom" to JsonPrimitive("keep"),
    )).toString()

    /** Writes are staged until the whole transaction succeeds, including its receipt journal. */
    private class NotepadStore(var snapshot: String) : CommandStore {
        var journal: String? = null
        var notification: String? = null
        var failNextJournal = false

        @Synchronized override fun <T> transaction(block: (CommandStore.Transaction) -> T): T {
            var nextSnapshot = snapshot
            var nextJournal = journal
            var nextNotification = notification
            val fail = failNextJournal.also { failNextJournal = false }
            val result = block(object : CommandStore.Transaction {
                override fun readSnapshot() = nextSnapshot
                override fun readJournal() = nextJournal
                override fun writeSnapshot(json: String) { nextSnapshot = json }
                override fun writeNotification(json: String) { nextNotification = json }
                override fun writeJournal(json: String) {
                    if (fail) error("injected journal failure")
                    nextJournal = json
                }
                override fun acknowledge(eventIds: List<Long>) { }
                override fun hasOtherEvents(eventIds: List<Long>) = false
            })
            snapshot = nextSnapshot
            journal = nextJournal
            notification = nextNotification
            return result
        }
    }
}
