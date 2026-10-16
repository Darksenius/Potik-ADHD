package com.flow.adhd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerProjectionTest {
    @Test fun manualEmptyBlocksOverrideRuleTemplateAndManualBlocksKeepUnknownFields() {
        val state = json("""{
          "planDayZones":{"2026-10-16":[]},
          "planRules":[{"id":"r","start":"2026-10-12","end":"2026-10-18","days":[{"tplId":"work","rest":false}]}],
          "dayTemplates":[{"id":"work","blocks":[{"zoneId":2,"s":"09:00","e":"10:00"}]}]
        }""")
        assertTrue(PlannerProjection.dayBlocks(state, "2026-10-16").isEmpty())

        val edited = json("""{"planDayZones":{"2026-10-16":[{"zoneId":2,"s":"09:00","e":"10:00","ov":true,"extra":"kept"}]}}""")
        val block = PlannerProjection.dayBlocks(edited, "2026-10-16").single()
        assertEquals("kept", block["extra"]?.toString()?.trim('"'))
        assertEquals("true", block["ov"]?.toString())
    }

    @Test fun explicitFalseRestFlagOverridesTrueRuleEntry() {
        val state = json("""{
          "planRestDays":{"2026-10-16":false},
          "planRules":[{"id":"r","start":"2026-10-12","end":"2026-10-18","days":[{"tplId":"","rest":true}]}]
        }""")
        assertFalse(PlannerProjection.isRest(state, "2026-10-16"))
        assertTrue(PlannerProjection.isRest(json("""{"planRules":[{"start":"2026-10-12","end":"2026-10-18","days":[{"tplId":"","rest":true}]}]}"""), "2026-10-16"))
    }

    @Test fun lastOverlappingRuleWins() {
        val state = json("""{"planRules":[
          {"id":"first","start":"2026-10-12","end":"2026-10-18","days":[{"tplId":"a","rest":false}]},
          {"id":"last","start":"2026-10-14","end":"2026-10-16","days":[{"tplId":"b","rest":true}]}
        ]}""")
        assertEquals("last", PlannerProjection.ruleFor(state, "2026-10-15")?.get("id")?.toString()?.trim('"'))
        assertEquals("b", PlannerProjection.ruleEntry(PlannerProjection.ruleFor(state, "2026-10-15"), "2026-10-15")?.get("tplId")?.toString()?.trim('"'))
        assertNull(PlannerProjection.ruleFor(state, "2026-10-19"))
    }

    @Test fun sevenDayCyclesUseWeekdayWhileOtherCyclesUseCivilDayOffset() {
        val seven = json("""{"start":"2026-10-12","days":[
          {"tplId":"mon"},{"tplId":"tue"},{"tplId":"wed"},{"tplId":"thu"},{"tplId":"fri"},{"tplId":"sat"},{"tplId":"sun"}
        ]}""")
        assertEquals("sun", PlannerProjection.ruleEntry(seven, "2026-10-18")?.get("tplId")?.toString()?.trim('"'))

        val three = json("""{"start":"2026-10-12","days":[{"tplId":"a"},{"tplId":"b"},{"tplId":"c"}]}""")
        assertEquals("b", PlannerProjection.ruleEntry(three, "2026-10-16")?.get("tplId")?.toString()?.trim('"'))
        assertNull(PlannerProjection.ruleEntry(json("""{"start":"2026-10-12","days":[]}"""), "2026-10-16"))
    }

    @Test fun missingTemplateProducesNoBlocks() {
        val state = json("""{
          "planRules":[{"id":"r","start":"2026-10-12","end":"2026-10-18","days":[{"tplId":"deleted","rest":false}]}],
          "dayTemplates":[]
        }""")
        assertTrue(PlannerProjection.dayBlocks(state, "2026-10-16").isEmpty())
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
}
