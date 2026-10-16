package com.flow.adhd.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowCommandTest {
    @Test
    fun validCommandKeepsItsIdentityAndEpochTime() {
        val command = FlowCommand.LifecycleTick(commandId = "tick-1", activityAt = 1_800_000_000_000L)

        assertEquals("tick-1", command.commandId)
        assertEquals(1_800_000_000_000L, command.activityAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankCommandId() {
        FlowCommand.LifecycleTick(commandId = "  ", activityAt = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeActivityAt() {
        FlowCommand.LifecycleTick(commandId = "tick-1", activityAt = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeChecklistIndex() {
        FlowCommand.SetChecklistItem(taskId = 1, index = -1, checked = true, commandId = "check", activityAt = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteCounterDelta() {
        FlowCommand.ChangeCounter(taskId = 1, delta = Double.NaN, commandId = "counter", activityAt = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInfiniteNegativeEventDelta() {
        FlowCommand.RecordNegative(taskId = 1, delta = Double.POSITIVE_INFINITY, commandId = "negative", activityAt = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEnergyOutsideSupportedRange() {
        FlowCommand.SetEnergy(value = 6, commandId = "energy", activityAt = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrioritySlotOutsideThreeSlots() {
        FlowCommand.SetPriority(slot = 3, taskId = 1, commandId = "priority", activityAt = 0)
    }
}
