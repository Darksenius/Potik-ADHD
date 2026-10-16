package com.flow.adhd.data

import com.flow.adhd.domain.*
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

sealed interface CommandCommit {
    /** Only this result confirms that state and acknowledgement became durable together. */
    data class Committed(
        val snapshot: FlowSnapshot, val commandId: String, val replayed: Boolean,
        val events: List<FlowEvent>, val undoReceipt: JsonObject? = null,
    ) : CommandCommit
    data class Rejected(val code: String, val message: String) : CommandCommit
    data class Failed(val code: String) : CommandCommit
}

/** Prepared N3 writer. Not connected to React or notification receivers during migration. */
class NativeCommandRepository(
    private val store: CommandStore,
    private val engine: FlowEngine,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val initialState: () -> JsonObject = { FlowDefaults.create() },
    private val afterCommit: (String) -> Unit = {},
) : SnapshotSource {
    // Also orders observer publication after a transaction. Never expose optimistic state.
    private val lane = Mutex()
    private val mutableState = MutableStateFlow<SnapshotRead>(SnapshotRead.Loading)
    val state: StateFlow<SnapshotRead> = mutableState.asStateFlow()

    override suspend fun read(): SnapshotRead = withContext(Dispatchers.IO) {
        lane.withLock {
            try {
                store.transaction { tx ->
                    val raw = tx.readSnapshot()
                    if (raw.isNullOrBlank()) SnapshotRead.Empty else SnapshotRead.Ready(FlowSnapshot.parse(raw))
                }.also { mutableState.value = it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SnapshotFormatException) { SnapshotRead.Unavailable("invalid_snapshot").also { mutableState.value = it } }
            catch (_: Exception) { SnapshotRead.Unavailable("storage_error").also { mutableState.value = it } }
        }
    }

    suspend fun dispatch(command: FlowCommand, eventIds: List<Long> = emptyList()): CommandCommit = withContext(Dispatchers.IO) {
        lane.withLock {
            if (command.commandId.toByteArray(Charsets.UTF_8).size > 256) return@withLock CommandCommit.Rejected("invalid-command-id", "Command ID is too long")
            if (eventIds.size > 500 || eventIds.any { it <= 0 } || eventIds.toSet().size != eventIds.size)
                return@withLock CommandCommit.Rejected("invalid-events", "Event acknowledgements must be unique positive IDs")
            try {
                val digest = fingerprint(FlowCommandCodec.encode(command))
                val at = clock()
                val timeZone = zone()
                val committed = store.transaction { tx ->
                    val raw = tx.readSnapshot()
                    val before = if (raw.isNullOrBlank()) FlowSnapshot.fromData(initialState()) else FlowSnapshot.parse(raw)
                    val journal = readJournal(tx.readJournal())
                    val entries = (journal["receipts"] as JsonObject).toMutableMap()
                    val existing = entries[command.commandId] as? JsonObject
                    if (existing != null) {
                        if ((existing["sha256"] as JsonPrimitive).content != digest)
                            return@transaction CommandCommit.Rejected("command-id-conflict", "Command ID was already used for another request")
                        // A crash after commit may deliver the same envelope again. Only acknowledge it.
                        tx.acknowledge(eventIds)
                        return@transaction CommandCommit.Committed(before, command.commandId, true, emptyList())
                    }
                    when (val result = engine.apply(before, command, timeZone, at)) {
                        is FlowCommandResult.Rejected -> CommandCommit.Rejected(result.code, result.message)
                        is FlowCommandResult.Applied -> {
                            entries[command.commandId] = buildJsonObject { put("sha256", digest); put("at", at) }
                            tx.writeSnapshot(result.snapshot.toJson())
                            tx.writeJournal(JsonObject(journal + ("receipts" to JsonObject(entries))).toString())
                            tx.acknowledge(eventIds)
                            CommandCommit.Committed(result.snapshot, command.commandId, false, result.events, result.undoReceipt)
                        }
                    }
                }
                if (committed is CommandCommit.Committed) {
                    mutableState.value = SnapshotRead.Ready(committed.snapshot)
                    // A backup is a derived copy. Its failure cannot undo an already durable command.
                    if (!committed.replayed) try { afterCommit(committed.snapshot.toJson()) } catch (_: Exception) { }
                }
                committed
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SnapshotFormatException) { CommandCommit.Failed("invalid_snapshot") }
            catch (_: JournalFormatException) { CommandCommit.Failed("invalid_command_journal") }
            catch (_: Exception) { CommandCommit.Failed("storage_error") }
        }
    }

    private fun readJournal(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return buildJsonObject { put("version", 1); put("receipts", JsonObject(emptyMap())) }
        val value = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
            ?: throw JournalFormatException()
        if (value["version"] != JsonPrimitive(1)) throw JournalFormatException()
        val receipts = value["receipts"] as? JsonObject ?: throw JournalFormatException()
        for ((id, rawReceipt) in receipts) {
            val receipt = rawReceipt as? JsonObject ?: throw JournalFormatException()
            val hash = receipt["sha256"] as? JsonPrimitive ?: throw JournalFormatException()
            val at = receipt["at"] as? JsonPrimitive ?: throw JournalFormatException()
            if (id.isBlank() || !hash.isString || !hash.content.matches(Regex("[a-f0-9]{64}")) || at.isString || at.longOrNull?.let { it >= 0 } != true)
                throw JournalFormatException()
        }
        return value
    }

    private class JournalFormatException : IllegalArgumentException()

    companion object {
        internal fun fingerprint(value: JsonObject): String {
            fun canonical(element: JsonElement): JsonElement = when (element) {
                is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
                is JsonArray -> JsonArray(element.map(::canonical))
                else -> element
            }
            return MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
