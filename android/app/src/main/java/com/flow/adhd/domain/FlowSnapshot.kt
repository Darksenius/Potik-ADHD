package com.flow.adhd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A read-only projection of the existing formatVersion 2 state document. */
class FlowSnapshot private constructor(val data: JsonObject) {
    val tasks: List<FlowTask> get() = objectList("tasks").map(::FlowTask)
    val qnotes: List<JsonObject> get() = objectList("qnotes")
    val zones: List<JsonObject> get() = objectList("zones")
    val xpTotal: Double get() = data.number("xpTotal") ?: 0.0
    val level: Long get() = data.number("level")
        ?.takeIf { it % 1.0 == 0.0 && it >= 1.0 && it <= MAX_SAFE_JS_INTEGER }
        ?.toLong() ?: 1L
    val notepad: String get() = data.string("notepad") ?: ""

    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), data)

    private fun objectList(name: String): List<JsonObject> {
        val value = data[name] ?: return emptyList()
        val array = value as? JsonArray ?: throw SnapshotFormatException("'$name' must be an array")
        return array.mapIndexed { index, element ->
            element as? JsonObject ?: throw SnapshotFormatException("'$name[$index]' must be an object")
        }
    }

    companion object {
        private const val MAX_SAFE_JS_INTEGER = 9_007_199_254_740_991.0

        fun parse(raw: String): FlowSnapshot {
            val root = try {
                Json.parseToJsonElement(raw) as? JsonObject
                    ?: throw SnapshotFormatException("Root must be an object")
            } catch (e: SnapshotFormatException) {
                throw e
            } catch (e: Exception) {
                throw SnapshotFormatException("Invalid JSON", e)
            }

            val version = root["formatVersion"]
            if (version != null && version != JsonPrimitive(2)) {
                throw SnapshotFormatException("Unsupported formatVersion")
            }

            val snapshot = FlowSnapshot(root)
            snapshot.validateOptionalCoreFields()
            val tasks = snapshot.tasks // Validate the complete core task list without dropping records.
            if (tasks.map { it.id }.toSet().size != tasks.size) {
                throw SnapshotFormatException("'tasks' contains duplicate IDs")
            }
            snapshot.qnotes
            snapshot.zones
            return snapshot
        }

        private fun FlowSnapshot.validateOptionalCoreFields() {
            data["xpTotal"]?.let { value ->
                if (value !is JsonPrimitive || value.isString || value.doubleOrNull?.isFinite() != true) {
                    throw SnapshotFormatException("'xpTotal' must be a finite number")
                }
            }
            data["level"]?.let { value ->
                val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                if (number == null || !number.isFinite() || number % 1.0 != 0.0 || number < 1.0 || number > MAX_SAFE_JS_INTEGER) {
                    throw SnapshotFormatException("'level' must be a positive JavaScript-safe integer")
                }
            }
            data["notepad"]?.let { value ->
                if (value !is JsonPrimitive || !value.isString) {
                    throw SnapshotFormatException("'notepad' must be a string")
                }
            }
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content

        private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)
            ?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

        internal fun safeId(value: JsonElement?, path: String): Long? {
            if (value == null || value == JsonNull) return null
            val primitive = value as? JsonPrimitive
                ?: throw SnapshotFormatException("$path must be an integer ID")
            val number = if (!primitive.isString) primitive.doubleOrNull else null
            if (number == null || !number.isFinite() || number < 0 || number > MAX_SAFE_JS_INTEGER || number % 1.0 != 0.0) {
                throw SnapshotFormatException("$path must be a non-negative JavaScript-safe integer")
            }
            return primitive.longOrNull ?: number.toLong()
        }
    }
}

/** The small set of task fields needed by the first native screen. */
class FlowTask internal constructor(val raw: JsonObject) {
    val id: Long = FlowSnapshot.safeId(raw["id"], "tasks.id")
        ?: throw SnapshotFormatException("tasks.id is required")
    val title: String = requiredString("title")
    val type: String = optionalString("type", "simple")
    val done: Boolean = optionalBoolean("done", false)
    val trashed: Boolean = optionalBoolean("trashed", false)
    val someday: Boolean = optionalBoolean("someday", false)
    val note: String? = optionalNullableString("note")
    val zoneId: Long? = FlowSnapshot.safeId(raw["zoneId"], "tasks.zoneId")

    private fun requiredString(name: String): String = (raw[name] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: throw SnapshotFormatException("tasks.$name must be a string")

    private fun optionalString(name: String, default: String): String = when (val value = raw[name]) {
        null -> default
        is JsonPrimitive -> if (value.isString) value.content else throw SnapshotFormatException("tasks.$name must be a string")
        else -> throw SnapshotFormatException("tasks.$name must be a string")
    }

    private fun optionalBoolean(name: String, default: Boolean): Boolean = when (val value = raw[name]) {
        null -> default
        is JsonPrimitive -> if (value.isString) throw SnapshotFormatException("tasks.$name must be a boolean")
            else value.booleanOrNull ?: throw SnapshotFormatException("tasks.$name must be a boolean")
        else -> throw SnapshotFormatException("tasks.$name must be a boolean")
    }

    private fun optionalNullableString(name: String): String? = when (val value = raw[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else throw SnapshotFormatException("tasks.$name must be a string or null")
        else -> throw SnapshotFormatException("tasks.$name must be a string or null")
    }
}

class SnapshotFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
