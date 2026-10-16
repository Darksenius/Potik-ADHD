package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotNormalizerTest {
    private val at = Instant.parse("2026-10-16T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Europe/Kyiv")
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun formerPlannerTasksBecomeRealTasksWhileEventsAndPromptsStayAnnotations() {
        val input = json("""{"nid":300,"tasks":[],"planItems":{"2026-10-16":[
          {"title":"Прибрати","done":true,"time":"9:05"},
          {"title":"Купити хліб","done":false,"time":"25:00"},
          {"title":"Зустріч","isEvent":true,"done":false,"time":"11:00","custom":7},
          {"title":"Заплануй наступний тиждень","done":true}]}}""")
        val result = SnapshotNormalizer.normalize(input, zone, at)
        val tasks = result["tasks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, tasks.size)
        assertEquals(JsonPrimitive("sched"), tasks[0]["type"])
        assertEquals(JsonPrimitive("09:05"), tasks[0]["schedTime"])
        assertEquals(JsonPrimitive("2026-10-15T21:00:00.000Z"), tasks[0]["created"])
        assertEquals(JsonPrimitive(true), tasks[0]["completionCredited"])
        assertEquals(JsonPrimitive("simple"), tasks[1]["type"])
        assertEquals(JsonPrimitive(302), result["nid"])
        val annotations = result["planItems"]!!.jsonObject["2026-10-16"]!!.jsonArray
        assertEquals(2, annotations.size)
        assertEquals(JsonPrimitive(7), annotations[0].jsonObject["custom"])
        assertEquals(JsonPrimitive(false), annotations[1].jsonObject["done"])
        assertEquals(JsonPrimitive(true), annotations[1].jsonObject["isPrompt"])
        assertEquals(1, result["taskHistory"]!!.jsonArray.size)
        assertEquals(result, SnapshotNormalizer.normalize(result, zone, at))
    }

    @Test fun routineMigrationRetainsProgressWithoutResurrectingRetiredWaterSeed() {
        val input = json("""{"tasks":[],"nid":300,"saveDate":"Fri Oct 09 2026","recur":[
          {"id":"r1","nm":"Вода","unit":"ml","neg":false},
          {"id":"custom","nm":"Розминка","unit":"count","val":3,"step":0},
          {"id":"check","nm":"Сніданок","unit":"check","done":true},
          {"id":"negative","nm":"Імпульс","unit":"check","neg":true,"done":true}]}""")
        val result = SnapshotNormalizer.normalize(input, zone, at)
        val tasks = result["tasks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Розминка", "Сніданок", "Імпульс"), tasks.map { it["title"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive(3), tasks[0]["counterCreditedProgress"])
        assertEquals(JsonPrimitive(1), tasks[0]["counterStep"])
        assertEquals(JsonPrimitive("2026-10-09"), tasks[0]["counterDay"])
        assertEquals(JsonPrimitive(true), tasks[1]["completionCredited"])
        assertEquals(JsonPrimitive(1), tasks[2]["counter"])
        assertEquals(JsonPrimitive("negative"), tasks[2]["type"])
        assertEquals(JsonArray(emptyList()), result["recur"])
        assertEquals(result, SnapshotNormalizer.normalize(result, zone, at))
    }

    @Test fun malformedPlannerDateRejectsTheWholeNormalization() {
        val input = json("""{"tasks":[],"planItems":{"2026-02-30":[{"title":"Не губити"}]}}""")
        assertThrows(IllegalStateException::class.java) { SnapshotNormalizer.normalize(input, zone, at) }
        assertEquals(1, input["planItems"]!!.jsonObject["2026-02-30"]!!.jsonArray.size)
    }
}
