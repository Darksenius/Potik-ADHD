package com.flow.adhd.data

import com.flow.adhd.domain.*
import java.time.ZoneId
import kotlinx.serialization.json.*

/** A startup policy only. Invalid input can become a fresh state only after durable archival. */
class SnapshotPreparation(
    private val taskTypes: Set<String>,
    private val archive: (String, String) -> Boolean,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun prepare(raw: String?): FlowSnapshot {
        if (raw.isNullOrBlank()) return FlowSnapshot.fromData(FlowDefaults.create())
        return try {
            val source = Json.parseToJsonElement(raw) as? JsonObject ?: error("Snapshot must be an object")
            val validated = BackupValidator.validate(source, taskTypes)
            // Import validation deliberately drops unknown top-level fields. Existing state must retain them.
            val normalized = SnapshotNormalizer.normalize(JsonObject(FlowDefaults.create() + source + validated), zone(), clock())
            FlowSnapshot.fromData(normalized)
        } catch (error: Exception) {
            if (!archive("native-incompatible-state", raw)) throw SnapshotFormatException("Could not archive incompatible state", error)
            FlowSnapshot.fromData(FlowDefaults.create())
        }
    }

    fun archiveJournal(raw: String) = archive("native-command-journal", raw)
    fun archiveReplacement(raw: String) = archive("before-native-import", raw)
    fun archivePending(event: QueuedNotification, reason: String) = archive("native-pending-event", buildJsonObject {
        put("id", event.id); put("event", event.event); put("createdAt", event.createdAt); put("reason", reason)
    }.toString())
}
