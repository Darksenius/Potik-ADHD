package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Historical web payload contract; r29 deliberately replaces only candidate ordering. */
@RunWith(Parameterized::class)
class NativeNotificationParityTest(private val name: String, private val fixture: JsonObject) {
    @Test fun matchesCompleteTypeScriptNotificationPayload() {
        val actual = NotificationProjection.build(
            FlowSnapshot.parse(fixture["state"]!!.toString()),
            fixture["now"]!!.jsonPrimitive.long,
            ZoneId.of(fixture["zone"]!!.jsonPrimitive.content),
        )
        val expected = fixture["expectedNotification"]!!.jsonObject
        // Preserve every candidate's exact fields; ordering now has its own TaskOrder contract.
        fun canonicalCandidates(value: JsonObject) = JsonObject(value + ("questTasks" to JsonArray(
            value["questTasks"]!!.jsonArray.sortedBy { it.jsonObject["id"]!!.jsonPrimitive.long },
        )))
        assertEquals(name, canonicalCandidates(expected), canonicalCandidates(actual))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val resource = NativeNotificationParityTest::class.java.getResourceAsStream("/native-notification-parity.json")
                ?: throw AssertionError("Run node scripts/generate-native-notification-parity.mjs before JVM parity tests")
            val root = resource.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
            return root["cases"]!!.jsonArray.map { raw ->
                val fixture = raw.jsonObject
                arrayOf(fixture["name"]!!.jsonPrimitive.content, fixture)
            }
        }
    }
}
