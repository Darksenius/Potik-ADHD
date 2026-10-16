package com.flow.adhd.data

import com.flow.adhd.domain.CaptureOrigin
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.FlowEngine
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.SnapshotRead
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCommandRepositoryTest {
    private val zone = ZoneId.of("UTC")
    private val now = 1_792_000_000_000L
    private val initial = """{"formatVersion":2,"tasks":[],"nid":300,"xp":0,"xpTotal":0,"level":1,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"custom":{"preserve":true}}"""

    private fun repository(
        store: MemoryCommandStore,
        afterCommit: (String) -> Unit = {},
    ) = NativeCommandRepository(store, FlowEngine(mapOf("simple" to 10.0)), { zone }, { now }, { JsonObject(emptyMap()) }, afterCommit)

    @Test fun retryWithNewRepositoryReplaysExactlyOnceAndAcknowledgesOnlyRequestedEvents() = runBlocking {
        val store = MemoryCommandStore(initial, pending = listOf(1, 2, 3))
        var backupWrites = 0
        val command = FlowCommand.CaptureTask("once", "same-id", now, CaptureOrigin.NOTIFICATION, 800)
        val first = repository(store) { backupWrites++ }.dispatch(command, listOf(2)) as CommandCommit.Committed
        val committedState = store.snapshot
        val replay = repository(store) { backupWrites++ }.dispatch(command, listOf(3)) as CommandCommit.Committed

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(committedState, store.snapshot)
        assertEquals("10", FlowSnapshot.parse(store.snapshot!!).data["xpTotal"].toString())
        assertEquals(1, FlowSnapshot.parse(store.snapshot!!).tasks.count { it.id == 800L })
        assertEquals(listOf(1L), store.pending)
        assertEquals(1, backupWrites)
        assertEquals(first.snapshot.data, replay.snapshot.data)
    }

    @Test fun reusingCommandIdForDifferentRequestConflictsWithoutAcknowledging() = runBlocking {
        val store = MemoryCommandStore(initial, pending = listOf(10, 11))
        val repo = repository(store)
        val original = FlowCommand.CaptureTask("one", "same-id", now)
        assertTrue(repo.dispatch(original, listOf(10)) is CommandCommit.Committed)
        val state = store.snapshot
        val journal = store.journal
        val result = repo.dispatch(FlowCommand.CaptureTask("two", "same-id", now), listOf(11))

        assertEquals("command-id-conflict", (result as CommandCommit.Rejected).code)
        assertEquals(state, store.snapshot)
        assertEquals(journal, store.journal)
        assertEquals(listOf(11L), store.pending)
    }

    @Test fun injectedTransactionFailureRollsBackStateJournalAndExactAcknowledgements() = runBlocking {
        val store = MemoryCommandStore(initial, journal = """{"version":1,"receipts":{}}""", pending = listOf(4, 5, 6))
        val repo = repository(store)
        val prior = Triple(store.snapshot, store.journal, store.pending)
        store.failNextAt = "ack"
        val result = repo.dispatch(FlowCommand.CaptureTask("rollback", "rollback-id", now), listOf(4, 6))

        assertTrue(result is CommandCommit.Failed)
        assertEquals(prior.first, store.snapshot)
        assertEquals(prior.second, store.journal)
        assertEquals(prior.third, store.pending)
    }

    @Test fun concurrentlyDispatchedCapturesBothSurviveWithDistinctIdsAndUnknownRootFields() = runBlocking {
        val store = MemoryCommandStore(initial)
        val repo = repository(store)
        val first = async(Dispatchers.Default) { repo.dispatch(FlowCommand.CaptureTask("alpha", "alpha", now)) }
        val second = async(Dispatchers.Default) { repo.dispatch(FlowCommand.CaptureTask("beta", "beta", now)) }
        assertTrue(first.await() is CommandCommit.Committed)
        assertTrue(second.await() is CommandCommit.Committed)

        val saved = FlowSnapshot.parse(store.snapshot!!)
        assertEquals(setOf("alpha", "beta"), saved.tasks.map { it.title }.toSet())
        assertEquals(2, saved.tasks.map { it.id }.toSet().size)
        assertEquals("true", (saved.data["custom"] as JsonObject)["preserve"].toString())
    }

    @Test fun corruptJournalDoesNotMutateAnything() = runBlocking {
        val store = MemoryCommandStore(initial, journal = "not-json", pending = listOf(7, 8))
        val before = Triple(store.snapshot, store.journal, store.pending)
        val result = repository(store).dispatch(FlowCommand.CaptureTask("blocked", "bad-journal", now), listOf(7))

        assertEquals("invalid_command_journal", (result as CommandCommit.Failed).code)
        assertEquals(before.first, store.snapshot)
        assertEquals(before.second, store.journal)
        assertEquals(before.third, store.pending)
    }

    @Test fun rejectedCommandDoesNotWriteReceiptOrAcknowledgeEvents() = runBlocking {
        val store = MemoryCommandStore(initial, pending = listOf(21))
        val before = Triple(store.snapshot, store.journal, store.pending)
        val rejected = FlowCommand.CreateTask(JsonObject(emptyMap()), "reject", now)
        val result = repository(store).dispatch(rejected, listOf(21))

        assertTrue(result is CommandCommit.Rejected)
        assertEquals(before.first, store.snapshot)
        assertEquals(before.second, store.journal)
        assertEquals(before.third, store.pending)
    }

    @Test fun failedCommitIsNeverPublishedToObservers() = runBlocking {
        val store = MemoryCommandStore(initial)
        val repo = repository(store)
        assertTrue(repo.read() is SnapshotRead.Ready)
        val publishedBefore = repo.state.value
        store.failNextAt = "journal"
        val result = repo.dispatch(FlowCommand.CaptureTask("not-published", "observer-fail", now))

        assertTrue(result is CommandCommit.Failed)
        assertEquals(publishedBefore, repo.state.value)
        assertTrue(FlowSnapshot.parse(store.snapshot!!).tasks.isEmpty())
    }

    /** Transactional test double: mutations are private copies until the block returns normally. */
    private class MemoryCommandStore(
        snapshot: String?,
        journal: String? = null,
        pending: List<Long> = emptyList(),
    ) : CommandStore {
        @Volatile var snapshot: String? = snapshot
            private set
        @Volatile var journal: String? = journal
            private set
        @Volatile var pending: List<Long> = pending.toList()
            private set
        @Volatile var failNextAt: String? = null

        @Synchronized override fun <T> transaction(block: (CommandStore.Transaction) -> T): T {
            var nextSnapshot = snapshot
            var nextJournal = journal
            var nextPending = pending.toMutableList()
            val failAt = failNextAt.also { failNextAt = null }
            fun fail(point: String) { if (failAt == point) throw IllegalStateException("injected $point failure") }
            val result = block(object : CommandStore.Transaction {
                override fun readSnapshot() = nextSnapshot
                override fun readJournal() = nextJournal
                override fun writeSnapshot(json: String) { fail("snapshot"); nextSnapshot = json }
                override fun writeJournal(json: String) { fail("journal"); nextJournal = json }
                override fun acknowledge(eventIds: List<Long>) {
                    fail("ack")
                    nextPending.removeAll(eventIds.toSet())
                }
            })
            snapshot = nextSnapshot
            journal = nextJournal
            pending = nextPending.toList()
            return result
        }
    }
}
