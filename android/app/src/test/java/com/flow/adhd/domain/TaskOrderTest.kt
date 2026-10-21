package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TaskOrderTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()
    private val context = QuestPolicy.Context(
        now = now,
        timeline = listOf(
            TimelineSegment(1, Instant.parse("2026-10-21T09:00:00Z").toEpochMilli(), Instant.parse("2026-10-21T15:00:00Z").toEpochMilli()),
            TimelineSegment(2, Instant.parse("2026-10-21T15:00:00Z").toEpochMilli(), Instant.parse("2026-10-21T18:00:00Z").toEpochMilli()),
            TimelineSegment(1, Instant.parse("2026-10-22T09:00:00Z").toEpochMilli(), Instant.parse("2026-10-22T15:00:00Z").toEpochMilli()),
            TimelineSegment(2, Instant.parse("2026-10-22T15:00:00Z").toEpochMilli(), Instant.parse("2026-10-22T18:00:00Z").toEpochMilli()),
        ),
        zones = listOf(json("""{"id":1,"nm":"Work"}"""), json("""{"id":2,"nm":"Home"}""")),
        priorities = listOf(3L),
        zone = zone,
    )

    @Test fun keepsAllTasksAndRanksEligiblePinsThenDueThenZoneAndUnbound() {
        val unbound = json("""{"id":1,"type":"simple","title":"Any"}""")
        val currentZone = json("""{"id":2,"type":"zonelinked","zoneId":1,"title":"Work"}""")
        val dueSoon = json("""{"id":4,"type":"sched","schedDate":"2026-10-21","schedTime":"11:30","title":"Due later"}""")
        val pinned = json("""{"id":3,"type":"simple","title":"Pinned"}""")
        val overdue = json("""{"id":5,"type":"sched","schedDate":"2026-10-21","schedTime":"10:00","title":"Overdue"}""")

        val ranked = TaskOrder.rank(listOf(unbound, currentZone, dueSoon, pinned, overdue), context)

        assertEquals(listOf(3L, 5L, 4L, 2L, 1L), ranked.map { it.long("id") })
        assertEquals(5, ranked.size)
        assertSame(pinned, ranked.first())
    }

    @Test fun ordersUpcomingDatedZoneTasksByApplicableOccurrenceAndNotAnEarlierWrongZone() {
        val tomorrowHome = json("""{"id":10,"type":"zonelinked","zoneId":2,"planDate":"2026-10-22","title":"Tomorrow home"}""")
        val tonightWork = json("""{"id":11,"type":"zonelinked","zoneId":1,"planDate":"2026-10-21","title":"Next work"}""")
        val tomorrowMorning = json("""{"id":12,"type":"sched","schedDate":"2026-10-22","schedTime":"10:00","title":"Tomorrow appointment"}""")

        val ranked = TaskOrder.rank(listOf(tomorrowHome, tomorrowMorning, tonightWork), context)

        assertEquals(listOf(11L, 12L, 10L), ranked.map { it.long("id") })
    }

    @Test fun imminentScheduledAndNextZoneWorkRanksAheadOfGeneralUnboundTasks() {
        val pinned = json("""{"id":40,"type":"simple","title":"Pinned"}""")
        val overdue = json("""{"id":41,"type":"sched","schedDate":"2026-10-21","schedTime":"10:00"}""")
        val currentZone = json("""{"id":42,"type":"zonelinked","zoneId":1,"title":"Current zone"}""")
        val dueSoon = json("""{"id":43,"type":"sched","schedDate":"2026-10-21","schedTime":"14:30"}""")
        val nextZone = json("""{"id":44,"type":"zonelinked","zoneId":2,"planDate":"2026-10-21","title":"Next zone"}""")
        val general = json("""{"id":45,"type":"simple","title":"Anytime"}""")
        val tomorrowSchedule = json("""{"id":46,"type":"sched","schedDate":"2026-10-22","schedTime":"10:00"}""")
        val duringNextZone = json("""{"id":47,"type":"sched","schedDate":"2026-10-21","schedTime":"16:30"}""")

        val orderingContext = context.copy(priorities = listOf(40L))
        val ranked = TaskOrder.rank(listOf(general, nextZone, tomorrowSchedule, duringNextZone,
            dueSoon, currentZone, overdue, pinned), orderingContext)

        assertEquals(listOf(40L, 41L, 42L, 43L, 44L, 47L, 45L, 46L), ranked.map { it.long("id") })
        assertEquals(listOf(40L, 41L, 42L, 43L, 44L), ranked.take(5).map { it.long("id") })
    }

    @Test fun timeDeferredScheduleUsesItsDeadlineForEligibleDueOrdering() {
        val later = now + 60 * 60_000L
        val afterSnooze = context.copy(now = later)
        val ordinary = json("""{"id":50,"type":"sched","schedDate":"2026-10-21","schedTime":"12:45"}""")
        val snoozed = json("""{"id":51,"type":"sched","schedDate":"2026-10-21","schedTime":"10:00","questDeferral":{"kind":"time","requestedAt":${later - 5 * 60_000L},"sourceZoneId":1,"targetZoneId":0,"startAt":${later - 1 * 60_000L}}}""")

        val ranked = TaskOrder.rank(listOf(snoozed, ordinary), afterSnooze)

        assertEquals(listOf(50L, 51L), ranked.map { it.long("id") })
    }

    @Test fun timeDeferredScheduleKeepsDeadlineAcrossMidnightAndOutsideItsOriginalZone() {
        val afterMidnight = Instant.parse("2026-10-22T01:00:00Z").toEpochMilli()
        val firstDeadline = Instant.parse("2026-10-22T00:30:00Z").toEpochMilli()
        val secondDeadline = Instant.parse("2026-10-22T00:45:00Z").toEpochMilli()
        val afterSnooze = context.copy(now = afterMidnight)
        val first = json("""{"id":52,"type":"sched","zoneId":1,"schedDate":"2026-10-21","schedTime":"20:00","questDeferral":{"kind":"time","requestedAt":${firstDeadline - 60 * 60_000L},"sourceZoneId":0,"targetZoneId":1,"startAt":$firstDeadline}}""")
        val second = json("""{"id":53,"type":"sched","zoneId":2,"schedDate":"2026-10-21","schedTime":"19:00","questDeferral":{"kind":"time","requestedAt":${secondDeadline - 60 * 60_000L},"sourceZoneId":0,"targetZoneId":2,"startAt":$secondDeadline}}""")

        val ranked = TaskOrder.rank(listOf(second, first), afterSnooze)

        assertEquals(listOf(52L, 53L), ranked.map { it.long("id") })
    }

    @Test fun repeatingTaskDoneYesterdayIsOrderedByTodaysUpcomingTimeNotAsTerminal() {
        val recurring = json("""{"id":60,"type":"sched","repeat":"daily","done":true,"doneDate":"2026-10-20","schedDate":"2026-10-20","schedTime":"13:00"}""")
        val general = json("""{"id":61,"type":"simple","title":"Anytime"}""")

        val ranked = TaskOrder.rank(listOf(general, recurring), context)

        assertEquals(listOf(60L, 61L), ranked.map { it.long("id") })
    }

    @Test fun retainsUnavailableAndTerminalTasksAtTheEndInStableOrder() {
        val eligible = json("""{"id":20,"type":"simple","title":"Now"}""")
        val done = json("""{"id":21,"type":"simple","done":true,"title":"Done"}""")
        val someday = json("""{"id":22,"type":"simple","someday":true,"title":"Someday"}""")
        val missingZone = json("""{"id":23,"type":"zonelinked","zoneId":999,"title":"Missing zone"}""")

        val ranked = TaskOrder.rank(listOf(done, someday, eligible, missingZone), context)

        assertEquals(listOf(20L, 21L, 22L, 23L), ranked.map { it.long("id") })
    }

    @Test fun invalidDatesAndExtremeTimestampsDoNotDropOrMutateTasks() {
        val invalidDate = json("""{"id":30,"type":"sched","schedDate":"999999999999999999999-99-99","schedTime":"bad"}""")
        val extreme = json("""{"id":31,"type":"simple","snoozeUntil":1e300}""")
        val tasks = listOf(invalidDate, extreme)

        val ranked = TaskOrder.rank(tasks, context)

        assertEquals(tasks.toSet(), ranked.toSet())
        assertEquals(2, ranked.size)
        assertEquals(tasks.map { it.toString() }.toSet(), ranked.map { it.toString() }.toSet())
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject
    private fun JsonObject.long(key: String): Long? = this[key]?.toString()?.toLongOrNull()
}
