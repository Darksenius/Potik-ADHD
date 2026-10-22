package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotificationProjectionTest {
    private val utc = ZoneId.of("UTC")
    private val now = epoch("2026-10-16T10:00:00Z")

    @Test fun fiveQuestsKeepPriorityOrderAndFullTaskDataWhileJournalHasNoDoneAction() {
        val result = NotificationProjection.build(FlowSnapshot.parse("""{
          "tasks":[
            {"id":1,"title":"one","type":"counter","counter":3,"counterTarget":8,"counterStep":2,"counterUnit":"ml","extra":{"kept":true}},
            {"id":2,"title":"two"},{"id":3,"title":"three"},{"id":4,"title":"four"},
            {"id":5,"title":"five"},{"id":6,"title":"six"},{"id":7,"title":"seven"},
            {"id":8,"title":"other zone","zoneId":2},{"id":9,"title":"journal","type":"negative"},
            {"id":10,"title":"done","done":true,"doneDate":"2026-10-16","zoneId":1}
          ],
          "priorities":[7,null,3,9],
          "zones":[{"id":1,"nm":"Work","color":"#123456","slots":[{"s":"09:00","e":"12:00"}]},{"id":2,"nm":"Later","slots":[]}]
        }"""), now, utc)
        assertEquals(listOf(7L, 3L, 1L, 2L, 4L), result.rows("taskList").map { it.long("id") })
        assertEquals("seven|three|one|two|four", result.text("tasks"))
        assertEquals("Пріоритети: 2 · 7 активних · 1 виконано", result.text("body"))
        assertEquals(1L, result.long("done")); assertEquals(8L, result.long("total"))
        assertEquals(listOf("seven", "three"), result.array("urgentTasks").map { it.jsonPrimitive.content })
        assertFalse(result.rows("taskList").any { it.long("id") == 9L })
        val counter = result.rows("taskList").single { it.long("id") == 1L }
        assertEquals(8L, counter.long("counterTarget")); assertEquals(2L, counter.long("counterStep")); assertTrue(counter["counterEnabled"]!!.jsonPrimitive.boolean)
        val source = result.rows("questTasks").single { it.long("id") == 1L }
        assertEquals(json("""{"kept":true}"""), source["extra"])
        assertEquals(10, result.rows("questTasks").size)
        assertEquals(-1L, source.long("priorityIndex"))
        assertEquals(3L, result.rows("questTasks").single { it.long("id") == 9L }.long("priorityIndex"))
    }

    @Test fun scheduledAndAdvanceRowsKeepUnacknowledgedPastAdvanceUntilMainDeadline() {
        val result = NotificationProjection.build(FlowSnapshot.parse("""{"tasks":[
          {"id":1,"title":"Appointment","type":"sched","schedDate":"2026-10-16","schedTime":"10:00","remindBeforeMinutes":15},
          {"id":2,"title":"Disabled","type":"alarm","alarmTime":"10:00","reminderEnabled":false},
          {"id":3,"title":"Acknowledged","type":"sched","schedDate":"2026-10-16","schedTime":"10:00","firedSched":true}
        ]}"""), epoch("2026-10-16T09:55:00Z"), utc)
        val rows = result.rows("schedList")
        assertEquals(listOf("1", "1_pre", "3"), rows.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(epoch("2026-10-16T09:45:00Z"), rows[1].long("dueMs"))
        assertEquals("Незабаром: Appointment", rows[1].text("title"))
        assertTrue(rows[2]["fired"]!!.jsonPrimitive.boolean)
        assertFalse(rows[1]["fired"]!!.jsonPrimitive.boolean)
    }

    @Test fun simpleQuickReminderProjectsExplicitOneShotEvenWhenTaskIsNormallyUnavailable() {
        val deadline = epoch("2026-10-16T11:00:00Z")
        val source = FlowSnapshot.parse("""{"tasks":[
          {"id":21,"title":"Call","type":"simple","zoneId":44,"planDate":"2026-10-20","reminderEnabled":false,"quickReminderAt":$deadline},
          {"id":22,"title":"Acked","type":"simple","quickReminderAt":$deadline,"quickReminderFiredAt":$deadline},
          {"id":23,"title":"Ordinary deferral","type":"simple","questDeferral":{"kind":"time","requestedAt":${deadline - 1},"sourceZoneId":1,"targetZoneId":2,"startAt":$deadline}}
        ]}""")
        val rows = NotificationProjection.build(source, now, utc).rows("schedList")
        assertEquals(listOf("21"), rows.map { it.text("id") })
        assertEquals(deadline, rows.single().long("dueMs"))
        assertTrue(rows.single()["quickReminder"]!!.jsonPrimitive.boolean)
    }

    @Test fun calendarRowsAdvanceCompletedAndFiredOccurrencesAndKeepCalendarRules() {
        val task = json("""{"id":4,"title":"Daily","type":"sched","repeat":"daily","repeatDays":[false,false,false,false,false,false,false],
          "created":"2026-10-01T00:00:00Z","schedTime":"9:05","done":true,"doneDate":"2026-10-16","remindBeforeMinutes":20}""")
        val rows = NotificationProjection.recurringReminderRows(task, now, utc)
        assertEquals(2, rows.size)
        assertEquals("daily", rows[0].text("repeat")); assertEquals("2026-10-17", rows[0].text("anchorDate"))
        assertEquals("09:05", rows[0].text("localTime")); assertEquals(20L, rows[1].long("advanceMinutes"))
        assertEquals(epoch("2026-10-17T08:45:00Z"), rows[1].long("dueMs"))
        assertEquals(task["repeatDays"], rows[0]["repeatDays"])
        val fired = JsonObject(task - "done" - "doneDate" + ("firedSched" to JsonPrimitive(true)))
        assertEquals("2026-10-17", NotificationProjection.recurringReminderRows(fired, epoch("2026-10-16T08:00:00Z"), utc).first().text("anchorDate"))
    }

    @Test fun customAndWeeklyCalendarRemindersUseCivilDatesAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val gap = epoch("2026-03-08T05:00:00Z")
        val alarm = json("""{"id":5,"title":"Sunday alarm","type":"alarm","repeat":"weekly","planDate":"2026-03-01","alarmTime":"02:30"}""")
        val row = NotificationProjection.recurringReminderRows(alarm, gap, zone).single()
        assertEquals(epoch("2026-03-08T07:30:00Z"), row.long("dueMs"))
        assertEquals("2026-03-08", row.text("anchorDate")); assertEquals("02:30", row.text("localTime"))
        val custom = JsonObject(alarm + mapOf("repeat" to JsonPrimitive("custom"), "repeatDays" to JsonArray(listOf(true, false, false, false, false, false, false).map(::JsonPrimitive))))
        assertEquals("2026-03-09", NotificationProjection.recurringReminderRows(custom, gap, zone).single().text("anchorDate"))
    }

    @Test fun recurringDeferralProducesOnlyOneAbsoluteReminder() {
        val start = epoch("2026-10-17T09:00:00Z")
        val task = json("""{"id":6,"title":"Deferred","type":"sched","repeat":"daily","zoneId":2,"remindBeforeMinutes":15,
          "questDeferral":{"kind":"not-today","requestedAt":$now,"sourceZoneId":1,"targetZoneId":2,"startAt":$start}}""")
        val rows = NotificationProjection.recurringReminderRows(task, now, utc, listOf(TimelineSegment(2, start + 3_600_000, start + 7_200_000)))
        assertEquals(1, rows.size); assertEquals(start + 3_600_000, rows.single().long("dueMs"))
        assertFalse(rows.single().containsKey("repeat")); assertFalse(rows.single().containsKey("localTime"))
    }

    @Test fun wallCalendarRetainsAuthoredMinutesOnBothDstTransitionsAndPlannerOverrides() {
        val zone = ZoneId.of("America/New_York")
        val snapshot = FlowSnapshot.parse("""{"zones":[
          {"id":1,"nm":"Early","slots":[{"s":"01:00","e":"03:00"}]},
          {"id":2,"nm":"Plan","slots":[]}
        ],"planRules":[{"start":"2026-03-08","end":"2026-03-08","days":[{"tplId":"special"}]}],
          "dayTemplates":[{"id":"special","blocks":[{"zoneId":2,"s":"02:00","e":"02:30"}]}]}""")
        for ((day, elapsed) in listOf("2026-03-08" to 3_600_000L, "2026-11-01" to 10_800_000L)) {
            val at = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
            val result = NotificationProjection.build(snapshot, at, zone)
            val wall = result.rows("questWallDays").single { it.text("date") == day }
            assertEquals(39, result.rows("questWallDays").size)
            assertEquals(LocalDate.parse(day).minusDays(2).toString(), result.rows("questWallDays").first().text("date"))
            val assigned = wall.rows("assignedOccurrences").single { it.long("zoneId") == 1L }
            assertEquals(60L, assigned.long("startMinute")); assertEquals(180L, assigned.long("endMinute"))
            val occurrence = result.rows("questZoneOccurrences").single { it.text("key") == "$day:1:01:00" }
            assertEquals(elapsed, occurrence.long("endAt") - occurrence.long("startAt"))
            if (day == "2026-03-08") {
                val planned = wall.rows("segments").single { it.long("zoneId") == 2L }
                assertEquals(120L, planned.long("startMinute")); assertEquals(150L, planned.long("endMinute"))
                val gapOccurrence = result.rows("questZoneOccurrences").single { it.text("key") == "$day:2:02:00" }
                assertEquals(epoch("2026-03-08T07:00:00Z"), gapOccurrence.long("startAt"))
                assertEquals(epoch("2026-03-08T07:30:00Z"), gapOccurrence.long("endAt"))
            }
        }
    }

    @Test fun dailyInsightRequiresEarnedClaimInjectedContentAndEnabledGamification() {
        val state = json("""{"dailyInsightClaims":[{"day":"2026-10-16","cardId":4,"sequence":8,"earnedAt":$now}]}""")
        val content = mapOf(4 to json("""{"title":"A useful idea","text":"Take one small step."}"""))
        assertFalse(NotificationProjection.build(FlowSnapshot.fromData(state), now, utc).containsKey("dailyInsight"))
        val result = NotificationProjection.build(FlowSnapshot.fromData(state), now, utc, content)
        val insight = result["dailyInsight"]!!.jsonObject
        assertEquals("A useful idea", insight.text("title")); assertEquals(8L, insight.long("sequence")); assertEquals(4L, insight.long("cardId"))
        assertFalse(NotificationProjection.build(FlowSnapshot.fromData(JsonObject(state + ("preferences" to json("""{"showGamification":false}""")))), now, utc, content).containsKey("dailyInsight"))
        val archived = json("""{"dailyInsightClaims":[{"day":"2026-10-16","cardId":4,"sequence":8,"earnedAt":0}]}""")
        assertFalse(NotificationProjection.build(FlowSnapshot.fromData(archived), now, utc, content).containsKey("dailyInsight"))
    }

    @Test fun minimalNativeStateProvidesCompleteLegacyContractAndDefaults() {
        val result = NotificationProjection.build(FlowSnapshot.parse("{}"), now, utc)
        assertEquals("Вільний час", result.text("zone")); assertEquals("#888", result.text("zoneColor"))
        assertEquals("Потік · Вільний час", result.text("title")); assertEquals("10:00", result.text("time"))
        assertEquals("", result.text("routine")); assertEquals(0L, result.long("energy")); assertEquals(0L, result.long("total"))
        val required = setOf("zone", "zoneColor", "zoneTimeline", "zoneBaseline", "zoneTasksById", "zonelessTasks", "urgentTasks", "tlDate", "slots", "desc", "tasks", "taskList", "questTasks", "questTimeline", "questWallDays", "questZoneOccurrences", "questTimelineUntil", "questZones", "routine", "routineList", "schedList", "body", "time", "done", "total", "energy", "title")
        assertEquals(required, result.keys)
        assertEquals(result, Json.parseToJsonElement(result.toString()))
    }

    @Test fun malformedPresentNotificationDataIsRejectedInsteadOfDroppingRows() {
        for (state in listOf(
            """{"priorities":"bad"}""",
            """{"tasks":[{"id":1,"title":"alarm","type":"alarm","alarmTime":123}]}""",
            """{"tasks":[{"id":1,"title":"repeat","repeat":"custom","repeatDays":["true"]}]}""",
            """{"zones":[{"id":1,"slots":[{"s":"09:00","e":"broken"}]}]}""",
            """{"planDayZones":{"2026-10-16":[false]}}""",
            """{"dailyInsightClaims":[{"day":"2026-10-16","cardId":1,"sequence":1,"earnedAt":"bad"}]}"""
        )) {
            assertThrows(IllegalArgumentException::class.java) { NotificationProjection.build(FlowSnapshot.parse(state), now, utc) }
        }
    }

    private fun epoch(value: String) = Instant.parse(value).toEpochMilli()
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long
    private fun JsonObject.array(key: String) = this[key]!!.jsonArray
    private fun JsonObject.rows(key: String) = array(key).map { it.jsonObject }
}
