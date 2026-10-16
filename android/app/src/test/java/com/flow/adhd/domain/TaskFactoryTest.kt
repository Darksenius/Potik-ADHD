package com.flow.adhd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TaskFactoryTest {
    @Test fun simpleTaskMatchesExistingPersistedDefaultsExactly() {
        val expected = Json.parseToJsonElement("""{"id":300,"title":"Забрати посилку","type":"simple","done":false,"someday":false,"zoneId":null,"zoneColor":null,"zoneName":null,"expanded":false,"created":"2026-10-16T08:00:00.000Z","counterEnabled":false,"counterStep":1,"counterUnit":"count","alarmFired":false,"repeat":"none","repeatDays":[false,false,false,false,false,false,false],"tags":[]}""")
        assertEquals(expected, TaskFactory.create(300, "Забрати посилку", "simple", 1_792_137_600_000L))
    }

    @Test fun scheduledAndCounterDefaultsStayDistinct() {
        val scheduled = TaskFactory.create(301, "Подзвонити", "sched", 0)
        val counter = TaskFactory.create(302, "П’ять листів", "counter", 0)
        assertEquals("\"\"", scheduled["schedDate"].toString())
        assertFalse(scheduled.containsKey("counter"))
        assertEquals("10", counter["counterTarget"].toString())
        assertEquals("true", counter["counterEnabled"].toString())
        assertFalse(counter.containsKey("schedDate"))
    }

    @Test fun zoneIdentityAndDetailsArePreserved() {
        val zone = Json.parseToJsonElement("""{"id":7,"nm":"Вечір","color":"#ff6b35"}""") as JsonObject
        val task = TaskFactory.create(303, "Підмести", "zonelinked", 0, 7, zone)
        assertEquals(zone["nm"], task["zoneName"])
        assertEquals(zone["color"], task["zoneColor"])
        assertEquals("false", task["zoneDoneToday"].toString())
    }
}
