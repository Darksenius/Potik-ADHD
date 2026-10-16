package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHistoryTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val newYork = ZoneId.of("America/New_York")

    @Test fun completionFactsKeepTheirSavedTitleAfterRenameOrDeletion() {
        val saved = historyEvent("occ-1", "completed", 2, 100, "Original title")
        val renamedTask = json("""{"id":7,"created":"2024-01-01T00:00:00Z","title":"Renamed","done":false}""")
        val fromRenamed = TaskHistory.completedOccurrences(JsonArray(listOf(saved)), JsonArray(listOf(renamedTask)), kyiv).single()
        val afterDeletion = TaskHistory.completedOccurrences(JsonArray(listOf(saved)), zone = kyiv).single()

        assertEquals("Original title", fromRenamed["title"]?.toString()?.trim('"'))
        assertEquals(saved, fromRenamed)
        assertEquals(saved, afterDeletion)
    }

    @Test fun sequenceThenTimestampThenReopenedTieSelectsLastAction() {
        val sameTimeDone = historyEvent("tie", "completed", 5, 100, "done")
        val sameTimeReopened = historyEvent("tie", "reopened", 5, 100, "reopened")
        val olderHighSequence = historyEvent("sequence", "completed", 6, 10, "high")
        val laterLowSequence = historyEvent("sequence", "reopened", 5, 900, "late")
        val laterSameSequence = historyEvent("timestamp", "reopened", 7, 101, "later")
        val earlierSameSequence = historyEvent("timestamp", "completed", 7, 100, "earlier")

        val result = TaskHistory.completedOccurrences(
            JsonArray(listOf(sameTimeDone, sameTimeReopened, laterLowSequence, olderHighSequence, earlierSameSequence, laterSameSequence)),
            zone = kyiv,
        )

        assertEquals(listOf("sequence"), result.map { it["occurrenceId"]?.toString()?.trim('"') })
        assertEquals("high", result.single()["title"]?.toString()?.trim('"'))
    }

    @Test fun legacyDoneTaskSeedsOnceAndPreservesUnknownHistoryFieldsWithoutCopyingItems() {
        val unknown = json("""{"occurrenceId":"old","kind":"completed","sequence":3,"at":10,"futureField":{"kept":true}}""")
        val task = json("""{"id":9,"created":"2020-01-01T00:00:00Z","title":"Saved","type":"check","done":true,"doneDate":"2024-01-02","doneAt":"9:05","repeat":"none","items":[{"text":"x","done":true}]}""")

        val first = TaskHistory.seedKnown(JsonArray(listOf(unknown)), JsonArray(listOf(task)), kyiv)
        val second = TaskHistory.seedKnown(first, JsonArray(listOf(task)), kyiv)

        assertEquals(2, first.size)
        assertEquals(first, second)
        assertEquals(unknown, first[0])
        val seeded = first[1] as JsonObject
        assertEquals(4, seeded["sequence"]?.toString()?.toInt())
        assertEquals("2024-01-02", seeded["date"]?.toString()?.trim('"'))
        assertEquals("9:05", seeded["time"]?.toString()?.trim('"'))
        assertFalse("New history must not copy checklist items", "items" in seeded)
        assertEquals(Instant.parse("2024-01-02T07:05:00Z").toEpochMilli(), seeded["at"]?.toString()?.toLong())
    }

    @Test fun onlyValidPositiveDoneFactsAreSeeded() {
        val invalidDate = json("""{"id":1,"created":"c","title":"Bad date","type":"simple","done":true,"doneDate":"2024-02-30","doneAt":"12:00"}""")
        val invalidTime = json("""{"id":2,"created":"c","title":"Bad time","type":"simple","done":true,"doneDate":"2024-02-01","doneAt":"24:00"}""")
        val negative = json("""{"id":3,"created":"c","title":"Negative","type":"negative","done":true,"doneDate":"2024-02-01","doneAt":"12:00"}""")

        assertTrue(TaskHistory.seedKnown(JsonArray(emptyList()), JsonArray(listOf(invalidDate, invalidTime, negative)), kyiv).isEmpty())
        assertNull(TaskHistory.localDateTimeMillis("2024-2-01", "12:00", kyiv))
        assertNull(TaskHistory.localDateTimeMillis("2024-02-01", "12:60", kyiv))
    }

    @Test fun repeatIdentitiesUseOccurrenceZoneAndIntervalTimestamp() {
        val everyZone = json("""{"id":4,"created":"c","title":"Zone","repeat":"everyzone","zoneId":8,"zoneOccurrenceKey":"key-a"}""")
        val interval = json("""{"id":5,"created":"c","title":"Interval","repeat":"interval"}""")
        val deferred = json("""{"id":6,"created":"c","title":"Deferred","repeat":"daily","questDeferral":{"requestedAt":1704067200000}}""")
        val currentOccurrence = json("""{"id":7,"created":"c","title":"Current","repeat":"interval","occurrenceId":"saved-occurrence"}""")
        assertEquals("4@c|zone:key-a", TaskHistory.occurrenceId(everyZone, 1, "key-b", kyiv))
        val everyZoneWithoutSavedKey = json("""{"id":4,"created":"c","title":"Zone","repeat":"everyzone","zoneId":8}""")
        assertEquals("4@c|zone:key-b", TaskHistory.occurrenceId(everyZoneWithoutSavedKey, 1, "key-b", kyiv))
        assertEquals("4@c|zone:2024-01-01:8", TaskHistory.occurrenceId(everyZoneWithoutSavedKey, Instant.parse("2024-01-01T12:00:00Z").toEpochMilli(), zone = kyiv))
        assertEquals("5@c|interval:100", TaskHistory.occurrenceId(interval, 100, zone = kyiv))
        assertEquals("5@c|interval:101", TaskHistory.occurrenceId(interval, 101, zone = kyiv))
        assertEquals("6@c|day:2024-01-01", TaskHistory.occurrenceId(deferred, Instant.parse("2024-01-05T12:00:00Z").toEpochMilli(), zone = kyiv))
        assertEquals("saved-occurrence", TaskHistory.occurrenceId(currentOccurrence, 100, zone = kyiv))
        assertEquals("0@created|once", TaskHistory.remapIdentity("7@created|once", 7, "created", 0))
        assertEquals("other", TaskHistory.remapIdentity("other", 7, "created", 0))
    }

    @Test fun localTimeMatchesJavaScriptGapForwardAndEarlierOverlapChoice() {
        val gap = TaskHistory.localDateTimeMillis("2024-03-10", "02:30", newYork)
        val overlap = TaskHistory.localDateTimeMillis("2024-11-03", "01:30", newYork)

        assertEquals(Instant.parse("2024-03-10T07:30:00Z").toEpochMilli(), gap)
        assertEquals(Instant.parse("2024-11-03T05:30:00Z").toEpochMilli(), overlap)
    }

    private fun historyEvent(id: String, kind: String, sequence: Long, at: Long, title: String) = buildJsonObject {
        put("id", "$id#$sequence:$at:$kind")
        put("occurrenceId", id)
        put("kind", kind)
        put("sequence", sequence)
        put("at", at)
        put("title", title)
        put("futureField", "preserved")
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
}
