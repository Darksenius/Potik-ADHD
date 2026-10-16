package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Whole payload expectations come from the actual store and notifPayload.ts, not Kotlin copies. */
@RunWith(Parameterized::class)
class NativeNotificationParityTest(private val name: String, private val fixture: JsonObject) {
    @Test fun matchesCompleteTypeScriptNotificationPayload() {
        val actual = NotificationProjection.build(
            FlowSnapshot.parse(fixture["state"]!!.toString()),
            fixture["now"]!!.jsonPrimitive.long,
            ZoneId.of(fixture["zone"]!!.jsonPrimitive.content),
        )
        assertEquals(name, fixture["expectedNotification"]!!.jsonObject, actual)
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
