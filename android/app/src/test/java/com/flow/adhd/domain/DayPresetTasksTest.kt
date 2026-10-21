package com.flow.adhd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPresetTasksTest {
    private val types = setOf("simple", "check", "counter", "note", "alarm", "sched", "timewin", "ctx", "negative", "zonelinked")
    private val presets = DayPresetTasks(types)
    private fun obj(raw: String) = Json.parseToJsonElement(raw) as JsonObject
    private val zone = obj("""{"id":7,"nm":"Evening","color":"#123456"}""")

    @Test fun allTenTypesMaterializeWithFactoryDefaultsAndAuthoredContent() {
        val payloads = listOf(
            """{"title":"simple","type":"simple"}""",
            """{"title":"check","type":"check","items":[{"text":"one","done":true}]}""",
            """{"title":"counter","type":"counter","counterTarget":4,"counterStep":2,"counterUnit":"cups"}""",
            """{"title":"note","type":"note","note":"details"}""",
            """{"title":"alarm","type":"alarm","alarmTime":"7:05"}""",
            """{"title":"sched","type":"sched","schedTime":"8:10"}""",
            """{"title":"timewin","type":"timewin","windowStart":"9:00","windowEnd":"10:30"}""",
            """{"title":"ctx","type":"ctx","ctxTags":["home"]}""",
            """{"title":"negative","type":"negative","negXp":6}""",
            """{"title":"zonelinked","type":"zonelinked","zoneId":7}""",
        )
        payloads.forEachIndexed { index, raw ->
            val entry = obj("""{"id":"e$index","task":$raw}""")
            val task = presets.materialize(entry, "morning", "2026-10-16", 100L + index, listOf(zone), 0)!!
            assertEquals(100L + index, task.long("id"))
            assertEquals("morning:e$index:2026-10-16", task.string("dayPresetKey"))
            assertEquals("2026-10-16", task.string("planDate"))
            assertEquals(false, task.boolean("done"))
            assertEquals("none", task.string("repeat"))
            if (index == 0) assertEquals(
                obj("""{"id":100,"title":"simple","type":"simple","done":false,"someday":false,"zoneId":null,"zoneColor":null,"zoneName":null,"expanded":false,"created":"1970-01-01T00:00:00.000Z","counterEnabled":false,"counterStep":1,"counterUnit":"count","alarmFired":false,"repeat":"none","repeatDays":[false,false,false,false,false,false,false],"tags":[],"dayPresetKey":"morning:e0:2026-10-16","planDate":"2026-10-16","folderId":null,"snoozeUntil":0,"trashed":false,"nextRepeatAt":0,"cntHit":false,"completionCredited":false,"firedSched":false,"firedPre":false}"""),
                task,
            )
            if (index == 1) assertEquals(false, ((task["items"] as JsonArray).first() as JsonObject).boolean("done"))
            if (index == 4) assertEquals("07:05", task.string("alarmTime"))
            if (index == 9) assertEquals("Evening", task.string("zoneName"))
        }
    }

    @Test fun repeatApplyIsIdempotentDatesAreIndependentAndExistingDataIsUntouched() {
        val entry = obj("""{"id":"e","task":{"title":"Task","type":"simple"}}""")
        val existing = obj("""{"id":2,"title":"edited","type":"simple","dayPresetKey":"t:e:2026-10-16","done":true,"trashed":true}""")
        val once = presets.append(listOf(existing), 2, "t", listOf(entry), "2026-10-16", emptyList(), 0)
        assertEquals(listOf(existing), once.tasks)
        val otherDay = presets.append(once.tasks, once.nid, "t", listOf(entry), "2026-10-17", emptyList(), 0)
        assertEquals(2, otherDay.tasks.size)
        assertEquals("t:e:2026-10-17", otherDay.tasks.first().string("dayPresetKey"))
        val repeated = presets.append(otherDay.tasks, otherDay.nid, "t", listOf(entry), "2026-10-17", emptyList(), 0)
        assertEquals(otherDay, repeated)
        assertEquals(existing, repeated.tasks.last())
    }

    @Test fun titleMarkerPromotesPresetEntryToScheduledTaskOnMaterializedDate() {
        val entry = obj("""{"id":"timed","task":{"title":"Pay bill ///12:40///","type":"simple"}}""")
        val task = presets.materialize(entry, "work", "2026-10-22", 321, emptyList(), 0)!!
        assertEquals("Pay bill", task.string("title"))
        assertEquals("sched", task.string("type"))
        assertEquals("12:40", task.string("schedTime"))
        assertEquals("2026-10-22", task.string("schedDate"))
        assertNull(presets.sanitize(obj("""{"id":"bad","task":{"title":"///25:00///","type":"simple"}}""")))
    }

    @Test fun collisionAdvancesSafeIdAndOverflowThrows() {
        val entry = obj("""{"id":"e","task":{"title":"Task","type":"simple"}}""")
        val existing = obj("""{"id":5,"title":"keep","type":"simple"}""")
        val result = presets.append(listOf(existing), 5, "t", listOf(entry), "d", emptyList(), 0)
        assertEquals(6L, result.tasks.first().long("id"))
        assertThrows(IllegalStateException::class.java) {
            presets.append(listOf(existing), 9_007_199_254_740_991L, "t", listOf(entry), "d", emptyList(), 0)
        }
    }

    @Test fun orphanZoneIdSurvivesAndInvalidDefinitionsAreSkipped() {
        val orphan = obj("""{"id":"orphan","task":{"title":"Zone task","type":"zonelinked","zoneId":777}}""")
        val invalid = obj("""{"id":"bad","task":{"title":"Bad","type":"alarm","alarmTime":"25:80"}}""")
        val safe = presets.sanitize(orphan)!!
        assertEquals(777L, ((safe.getValue("task") as JsonObject).long("zoneId")))
        assertNull(presets.sanitize(invalid))
        val existing = obj("""{"id":2,"title":"keep","type":"simple","done":true}""")
        val result = presets.append(listOf(existing), 3, "t", listOf(invalid), "d", emptyList(), 0)
        assertEquals(listOf(existing), result.tasks)
        assertEquals(3L, result.nid)
        assertTrue(result.tasks.contains(existing))
        assertEquals(true, result.tasks.first().boolean("done"))
    }

    @Test fun negativeHasNoInventedCounterTargetAndEmptyTimeWindowUsesDefaults() {
        val negative = obj("""{"id":"negative","task":{"title":"Journal","type":"negative"}}""")
        val negativeTask = presets.materialize(negative, "t", "d", 11, emptyList(), 0)!!
        assertFalse(negativeTask.containsKey("counterTarget"))
        assertEquals(false, negativeTask.boolean("completionCredited"))
        assertEquals(false, negativeTask.boolean("firedSched"))
        assertEquals(false, negativeTask.boolean("firedPre"))
        assertEquals(0L, negativeTask["counterCreditedProgress"]?.let { (it as JsonPrimitive).longOrNull })

        val timewin = obj("""{"id":"window","task":{"title":"Window","type":"timewin","windowStart":"","windowEnd":""}}""")
        val windowTask = presets.materialize(timewin, "t", "d", 12, emptyList(), 0)!!
        assertEquals("07:00", windowTask.string("windowStart"))
        assertEquals("09:00", windowTask.string("windowEnd"))
    }

    @Test fun scheduleIntegralMinutesSerializeAsIntegerAndTimeInputIsNotTrimmed() {
        val schedule = obj("""{"id":"schedule","task":{"title":"Schedule","type":"sched","schedTime":"08:00","remindBeforeMinutes":0}}""")
        val safe = presets.sanitize(schedule)!!
        val definition = safe.getValue("task") as JsonObject
        assertEquals("0", definition.getValue("remindBeforeMinutes").toString())
        val invalid = obj("""{"id":"bad","task":{"title":"Alarm","type":"alarm","alarmTime":" 7:05"}}""")
        assertNull(presets.sanitize(invalid))
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
