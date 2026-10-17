package com.flow.adhd.domain

import com.flow.adhd.data.SnapshotPreparation
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotPreparationTest {
    private val now = 1_792_000_000_000L
    @Test fun compatibleStateKeepsUnknownFieldsWithoutArchivingOrReward() {
        var archives = 0
        val preparation = SnapshotPreparation(setOf("simple"), { _, _ -> archives++; true }, { ZoneId.of("UTC") }, { now })
        val source = JsonObject(FlowDefaults.create() + mapOf("unknown" to JsonPrimitive("keep"), "tasks" to JsonArray(listOf(
            JsonObject(TaskFactory.create(700, "Task", "simple", now) + ("taskExtension" to JsonPrimitive(42)))
        ))))
        val result = preparation.prepare(source.toString())
        assertEquals(JsonPrimitive("keep"), result.data["unknown"])
        assertEquals(JsonPrimitive(42), result.tasks.single().raw["taskExtension"])
        assertEquals(0.0, result.xpTotal, 0.0)
        assertEquals(0, archives)
    }
    @Test fun incompatibleStateBecomesFreshOnlyAfterTheOriginalWasArchived() {
        val raw = """{"formatVersion":999,"tasks":[{"unknown":"keep all original bytes"}]}"""
        val archived = mutableListOf<Pair<String, String>>()
        val preparation = SnapshotPreparation(setOf("simple"), { source, value -> archived += source to value; true })
        val result = preparation.prepare(raw)
        assertTrue(result.tasks.isEmpty())
        assertEquals(listOf("native-incompatible-state" to raw), archived)
        val failed = SnapshotPreparation(setOf("simple"), { _, _ -> false })
        assertThrows(SnapshotFormatException::class.java) { failed.prepare(raw) }
    }
    @Test fun pendingAndJournalArchivesCarryTheExactOriginalPayload() {
        val records = mutableListOf<Pair<String, String>>()
        val preparation = SnapshotPreparation(setOf("simple"), { source, value -> records += source to value; true })
        preparation.archiveJournal("broken receipt bytes")
        preparation.archivePending(com.flow.adhd.data.QueuedNotification(9, "unrecognized:payload", now), "invalid-notification-event")
        assertEquals("native-command-journal" to "broken receipt bytes", records.first())
        val pending = Json.parseToJsonElement(records[1].second) as JsonObject
        assertEquals(JsonPrimitive(9), pending["id"])
        assertEquals(JsonPrimitive("unrecognized:payload"), pending["event"])
        assertEquals(JsonPrimitive(now), pending["createdAt"])
    }
}
