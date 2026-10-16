package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure replacement restore. Validation of the imported backup belongs to the caller. */
object SnapshotRestore {
    fun restore(current: FlowSnapshot, validated: JsonObject, zone: ZoneId, now: Long): FlowCommandResult {
        return try {
            val defaults = FlowDefaults.create()
            val allowedInputKeys = FlowDefaults.persistedKeys + "saveDate"
            val base = current.data.filterKeys { it !in allowedInputKeys && it != "qnid" }
                .toMutableMap()
            base.putAll(defaults)
            for ((key, value) in validated) {
                if (key in allowedInputKeys && key != "saveDate") base[key] = value
            }

            // The older web format had XP fields but no explicit progression schema marker.
            if (!validated.containsKey("progressionVersion") &&
                listOf("xp", "level", "xpTotal").any(validated::containsKey)
            ) {
                base["progressionVersion"] = JsonPrimitive(0)
            }

            // Keep legacy saveDate available to the retired-store migration only; it is write metadata.
            if (validated.containsKey("saveDate")) base["saveDate"] = validated.getValue("saveDate")
            var normalized = SnapshotNormalizer.normalize(JsonObject(base), zone, now)
            val progression = Progression.initialize(normalized, now, zone)
            normalized = progression.state
            normalized = ZoneReducer.recalculateUsage(normalized, now, zone)
            normalized = JsonObject(normalized - setOf("saveDate", "qnid"))
            FlowCommandResult.Applied(FlowSnapshot.fromData(normalized), emptyList())
        } catch (e: Exception) {
            FlowCommandResult.Rejected("invalid-restore", e.message ?: "Backup cannot be restored")
        }
    }
}
