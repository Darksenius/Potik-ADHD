package com.flow.adhd.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.SimpleTaskTiming
import com.flow.adhd.domain.TaskTitleSchedule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The owner commits the authored payload and closes this screen only after a durable save. */
@Composable
fun NativeTaskEditor(
    snapshot: FlowSnapshot,
    taskId: Long?,
    busy: Boolean,
    onSave: (JsonObject) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSnooze: ((JsonObject, Long?, Boolean) -> Unit)? = null,
    onDraftChanged: (String) -> Unit = {},
    checklistDefinition: Boolean = false,
    allowQuickReminder: Boolean = true,
) {
    val context = LocalContext.current
    // The tiny, immutable APK catalog is already prepared by NativeContent.load at startup.
    // A standalone editor can initialize that same cache; Save never waits on an unrelated IO coroutine.
    val catalog = remember(context.applicationContext) {
        runCatching {
            val source = com.flow.adhd.data.NativeContent.taskTypes(context)
            require(source.isNotEmpty())
            EditorTypeCatalog(source.map { (id, definition) ->
                val fields = definition as JsonObject
                EditorType(id, fields.editorText("label") ?: error("Missing task label"), fields.editorText("hint").orEmpty())
            })
        }.getOrElse { EditorTypeCatalog(emptyList(), "Не вдалося завантажити типи задач. Закрий редактор і відкрий його знову.") }
    }
    val existing = snapshot.tasks.firstOrNull { it.id == taskId }
    val seed = remember(taskId) { existing?.raw ?: newEditorSeed(snapshot.data.editorText("taskEditorDraft").orEmpty()) }
    val draftValues = runCatching {
        (Json.parseToJsonElement(snapshot.data.editorText("taskEditorDraft").orEmpty()) as? JsonObject)?.get("values") as? JsonObject
    }.getOrNull()
    val savedMode = draftValues?.editorText("editorMode").takeIf { taskId == null }
    val seedHasReminder = seed.editorNumberText("quickReminderAt", "").toLongOrNull()?.let { it > 0L } == true
    val seedHasPlannedDate = seed.editorText("planDate").orEmpty().isNotBlank()
    val seedHasNonSimpleFields = seed.editorText("schedTime").orEmpty().isNotBlank() || seed.editorText("alarmTime").orEmpty().isNotBlank() ||
        seed.editorText("windowStart")?.let { it != "07:00" } == true || seed.editorText("windowEnd")?.let { it != "09:00" } == true ||
        seed.editorNumberText("repeatInterval", "30") != "30" || seed.editorText("repeatUnit")?.let { it != "min" } == true ||
        seed.editorNumberText("counterTarget", "0") != "0" || seed.editorNumberText("counterStep", "1") != "1" ||
        seed.editorText("counterUnit")?.let { it != "count" } == true || seed.editorNumberText("negXp", "5") != "5" ||
        seed.editorNumberText("remindBeforeMinutes", "0") != "0"
    val seedHasTags = listOf("tags", "ctxTags").any { key ->
        (seed[key] as? JsonArray).orEmpty().any { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true
        }
    }
    val seedIsPlainSimple = (seed.editorText("type") == null || seed.editorText("type") == "simple") &&
        seed.editorText("note").orEmpty().isBlank() && seed.editorText("folderId").orEmpty().isBlank() &&
        seed.editorNumberText("zoneId", "").isBlank() && !seedHasTags && (seed.editorText("repeat") ?: "none") == "none" &&
        !seed.editorBoolean("counterEnabled", false) && seed.editorText("checklistDraft").orEmpty().isBlank() &&
        (seed["items"] as? JsonArray).orEmpty().isEmpty() && !seedHasNonSimpleFields &&
        (!seedHasPlannedDate || seedHasReminder)
    val confirmedSimpleDraft = draftValues?.get("simpleModeConfirmed") == JsonPrimitive(true)
    val draftHasIntentionalContent = seed.editorText("title").orEmpty().isNotBlank() || !seedIsPlainSimple
    val initialSimpleMode = savedMode?.let { mode ->
        when {
            mode == "simple" && (seedIsPlainSimple || confirmedSimpleDraft) -> true
            // A blank mode-only draft is not a reason to reopen the full editor.
            mode == "complex" && !draftHasIntentionalContent -> true
            else -> false
        }
    } ?: (taskId == null && !checklistDefinition && allowQuickReminder && seedIsPlainSimple && seed.editorText("type") == null)
    var simpleMode by rememberSaveable(taskId) { mutableStateOf(initialSimpleMode) }
    var complexModeVisited by rememberSaveable(taskId) { mutableStateOf(!initialSimpleMode) }
    var confirmSimpleMode by rememberSaveable(taskId) { mutableStateOf(false) }
    var simpleModeConfirmed by rememberSaveable(taskId) { mutableStateOf(confirmedSimpleDraft) }
    var simpleTimingChoice by rememberSaveable(taskId) {
        mutableStateOf(if (taskId == null) draftValues?.editorText("simpleTimingChoice") ?: "delay" else "keep")
    }
    var simpleDelayIndex by rememberSaveable(taskId) {
        mutableStateOf(if (taskId == null) draftValues?.editorNumberText("simpleDelayIndex", "0")?.toIntOrNull()?.coerceIn(0, 5) ?: 0 else 0)
    }
    val delayChoices = SimpleTaskTiming.delayMinutes.map { it.toInt() }
    var previewNow by rememberSaveable(taskId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(simpleMode) {
        while (simpleMode) {
            previewNow = System.currentTimeMillis()
            delay(60_000)
        }
    }
    var title by rememberSaveable(taskId) { mutableStateOf(seed.editorText("title").orEmpty()) }
    var type by rememberSaveable(taskId) { mutableStateOf(seed.editorText("type") ?: "simple") }
    var zoneId by rememberSaveable(taskId) { mutableStateOf(seed.editorNumberText("zoneId", "")) }
    var folderId by rememberSaveable(taskId) { mutableStateOf(seed.editorText("folderId").orEmpty()) }
    val seededDate = (if (seed.editorText("type") == "sched") seed.editorText("schedDate") else null) ?: seed.editorText("planDate").orEmpty()
    var dateExplicit by rememberSaveable(taskId) { mutableStateOf(seed.editorBoolean("dateExplicit", seededDate.isNotBlank())) }
    var date by rememberSaveable(taskId) { mutableStateOf(seededDate) }
    var time by rememberSaveable(taskId) { mutableStateOf(seed.editorText("schedTime").orEmpty()) }
    var alarmTime by rememberSaveable(taskId) { mutableStateOf(seed.editorText("alarmTime").orEmpty()) }
    var windowStart by rememberSaveable(taskId) { mutableStateOf(seed.editorText("windowStart") ?: "07:00") }
    var windowEnd by rememberSaveable(taskId) { mutableStateOf(seed.editorText("windowEnd") ?: "09:00") }
    var note by rememberSaveable(taskId) { mutableStateOf(seed.editorText("note").orEmpty()) }
    var repeat by rememberSaveable(taskId) { mutableStateOf(seed.editorText("repeat") ?: "none") }
    var repeatMask by rememberSaveable(taskId) {
        mutableStateOf((seed["repeatDays"] as? JsonArray).orEmpty().foldIndexed(0) { index, mask, day ->
            if (index < 7 && (day as? JsonPrimitive)?.booleanOrNull == true) mask or (1 shl index) else mask
        })
    }
    var repeatInterval by rememberSaveable(taskId) { mutableStateOf(seed.editorNumberText("repeatInterval", "30")) }
    var repeatUnit by rememberSaveable(taskId) { mutableStateOf(seed.editorText("repeatUnit") ?: "min") }
    var counterEnabled by rememberSaveable(taskId) { mutableStateOf(type == "counter" || seed.editorBoolean("counterEnabled", false)) }
    var counterTarget by rememberSaveable(taskId) { mutableStateOf(seed.editorNumberText("counterTarget", if (type == "counter") "10" else "0")) }
    var counterStep by rememberSaveable(taskId) { mutableStateOf(seed.editorNumberText("counterStep", "1")) }
    var counterUnit by rememberSaveable(taskId) { mutableStateOf(seed.editorText("counterUnit") ?: "count") }
    var negativeXp by rememberSaveable(taskId) { mutableStateOf(seed.editorNumberText("negXp", "5")) }
    var reminderEnabled by rememberSaveable(taskId) { mutableStateOf(seed.editorBoolean("reminderEnabled", true)) }
    var reminderAdvance by rememberSaveable(taskId) { mutableStateOf(seed.editorNumberText("remindBeforeMinutes", "0")) }
    // null means preserve the saved deadline (or leave a new task unchanged).
    var quickReminderChoice by rememberSaveable(taskId) { mutableStateOf(seed.editorText("quickReminderChoice")) }
    var tags by rememberSaveable(taskId) { mutableStateOf((seed["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { entry -> entry.isString }?.content }.joinToString(", ")) }
    var contextTags by rememberSaveable(taskId) { mutableStateOf((seed["ctxTags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ")) }
    val hasSavedAdvancedValues = folderId.isNotBlank() || zoneId.isNotBlank() || tags.isNotBlank() || contextTags.isNotBlank() || counterEnabled || type == "counter"
    var advancedExpanded by rememberSaveable(taskId) { mutableStateOf(hasSavedAdvancedValues) }
    var checklist by rememberSaveable(taskId) { mutableStateOf(seed.editorText("checklistDraft") ?: if (checklistDefinition)
        (seed["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().joinToString("\n") { it.editorText("text").orEmpty() } else "") }
    var keepPast by rememberSaveable(taskId) { mutableStateOf(false) }
    var error by rememberSaveable(taskId) { mutableStateOf("") }
    val unavailable = taskId != null && existing == null
    val enabled = !busy && !unavailable
    val zoneOptions = snapshot.zones.mapNotNull { item ->
        (item["id"] as? JsonPrimitive)?.longOrNull?.let { EditorChoice(it.toString(), item.editorText("nm") ?: "Без назви") }
    }
    val folderOptions = (snapshot.data["folders"] as? JsonArray).orEmpty().mapNotNull { value ->
        val folder = value as? JsonObject ?: return@mapNotNull null
        folder.editorText("id")?.let { EditorChoice(it, folder.editorText("nm") ?: "Без назви") }
    }
    val storedItems = existing?.raw?.get("items") as? JsonArray
    val existingQuickReminder = existing?.raw?.editorNumberText("quickReminderAt", "")?.toLongOrNull()?.let { it > 0L } == true
    val savedReminderDateCanStaySimple = existing != null && existingQuickReminder && date == seededDate
    val existingHasOtherFields = existing?.raw?.let { raw ->
        val rawType = raw.editorText("type") ?: "simple"
        raw["items"]?.let { it != JsonNull && (it as? JsonArray)?.isNotEmpty() == true } == true ||
            listOf("schedDate", "schedTime", "alarmTime").any { raw.editorText(it).orEmpty().isNotBlank() } ||
            raw.editorText("windowStart")?.let { it != "07:00" } == true || raw.editorText("windowEnd")?.let { it != "09:00" } == true ||
            raw.editorNumberText("negXp", "5") != "5" || raw.editorNumberText("repeatInterval", "30") != "30" ||
            raw.editorText("repeatUnit")?.let { it != "min" } == true || raw.editorNumberText("counterTarget", "0") !in setOf("0", "10") ||
            raw.editorNumberText("counterStep", "1") != "1" || raw.editorText("counterUnit")?.let { it != "count" } == true ||
            rawType != "simple" && (raw["reminderEnabled"] != null || raw["remindBeforeMinutes"] != null) ||
            raw.editorNumberText("remindBeforeMinutes", "0") != "0"
    } == true
    val simpleModeEligible = allowQuickReminder && !checklistDefinition && (taskId == null || type == "simple")
    val simpleModeHasAdvancedValues = type != "simple" || note.isNotBlank() || zoneId.isNotBlank() || folderId.isNotBlank() || tags.isNotBlank() || contextTags.isNotBlank() ||
        repeat != "none" || repeatMask != 0 || counterEnabled || quickReminderChoice != null || checklist.isNotBlank() || storedItems.orEmpty().isNotEmpty() ||
        time.isNotBlank() || alarmTime.isNotBlank() || windowStart != "07:00" || windowEnd != "09:00" || repeatInterval != "30" || repeatUnit != "min" ||
        counterTarget != "0" || counterStep != "1" || counterUnit != "count" || negativeXp != "5" || reminderAdvance != "0" ||
        (date.isNotBlank() && !savedReminderDateCanStaySimple) || (seedHasReminder && taskId == null)
    val simpleModeAllowed = simpleModeEligible && (taskId == null || (!simpleModeHasAdvancedValues && !existingHasOtherFields))
    LaunchedEffect(simpleModeAllowed, simpleModeEligible) {
        if (!simpleModeEligible || taskId != null && !simpleModeAllowed) simpleMode = false
    }
    val localZone = ZoneId.systemDefault()
    val scheduled = type == "sched"
    val hasCounter = type != "negative" && (type == "counter" || counterEnabled)
    val parsedDate = editorDate(date)
    val parsedTime = editorTime(time)
    val pastTime = scheduled && parsedDate != null && parsedTime != null &&
        LocalDateTime.of(parsedDate, parsedTime).atZone(localZone).toInstant().toEpochMilli() < System.currentTimeMillis()

    fun chooseType(id: String) {
        type = id
        error = ""
        if (id != "simple") { simpleMode = false; complexModeVisited = true; simpleModeConfirmed = false }
        if (id == "counter") { counterEnabled = true; if (counterTarget == "0") counterTarget = "10" }
        if (id == "sched" && date.isEmpty()) { date = LocalDate.now(localZone).toString(); dateExplicit = false }
    }

    val draftText = buildJsonObject {
        put("version", 2)
        put("values", buildJsonObject {
            put("title", title); put("type", type); put("zoneId", zoneId.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull); put("folderId", folderId)
            put("planDate", date); put("schedDate", date); put("schedTime", time); put("dateExplicit", dateExplicit); put("alarmTime", alarmTime); put("windowStart", windowStart); put("windowEnd", windowEnd)
            put("note", note); put("repeat", repeat); put("repeatDays", JsonArray(List(7) { JsonPrimitive(repeatMask and (1 shl it) != 0) }))
            put("repeatInterval", repeatInterval); put("repeatUnit", repeatUnit); put("counterEnabled", counterEnabled); put("counterTarget", counterTarget); put("counterStep", counterStep); put("counterUnit", counterUnit)
            put("negXp", negativeXp); put("reminderEnabled", reminderEnabled); put("remindBeforeMinutes", reminderAdvance); put("checklistDraft", checklist)
            quickReminderChoice?.let { put("quickReminderChoice", it) }
            put("editorMode", if (simpleMode) "simple" else "complex")
            put("simpleModeConfirmed", simpleModeConfirmed)
            put("simpleTimingChoice", simpleTimingChoice); put("simpleDelayIndex", simpleDelayIndex)
            put("tags", JsonArray(tags.split(',').map { JsonPrimitive(it.trim()) })); put("ctxTags", JsonArray(contextTags.split(',').map { JsonPrimitive(it.trim()) }))
        })
    }.toString()
    LaunchedEffect(taskId, draftText) { if (taskId == null) onDraftChanged(draftText) }

    fun save(postponeMinutes: Long? = null, tomorrow: Boolean = false) {
        val now = System.currentTimeMillis()
        val titleResult = TaskTitleSchedule.parse(title)
        val parsedTitle = (titleResult as? TaskTitleSchedule.Result.Valid)?.value
        val markerTime = parsedTitle?.time
        if (simpleMode && simpleModeAllowed && postponeMinutes == null && !tomorrow) {
            val problem = when {
                unavailable -> "Цієї задачі вже немає. Закрий редактор і онови список."
                titleResult == TaskTitleSchedule.Result.InvalidTime -> "Час у позначці має бути від 00:00 до 23:59. Наприклад: ///12:40///."
                titleResult == TaskTitleSchedule.Result.EmptyTitle -> "Напиши, що потрібно зробити."
                markerTime != null && catalog.types.none { it.id == "sched" } -> "Запланований тип задачі недоступний."
                else -> null
            }
            if (problem != null) { error = problem; return }
            val payload = (existing?.raw ?: JsonObject(emptyMap())).toMutableMap().apply {
                put("title", JsonPrimitive(parsedTitle?.title ?: title.trim()))
                if (markerTime != null) {
                    val scheduledDate = TaskTitleSchedule.defaultDate(markerTime, now, localZone)
                    put("type", JsonPrimitive("sched"))
                    put("schedDate", JsonPrimitive(scheduledDate)); put("schedTime", JsonPrimitive(markerTime))
                    put("reminderEnabled", JsonPrimitive(true)); put("remindBeforeMinutes", JsonPrimitive(0))
                    remove("quickCapture"); remove("quickReminderDay"); remove("quickReminderDelayMinutes"); remove("clearQuickReminder")
                } else {
                    put("type", JsonPrimitive("simple"))
                    if (allowQuickReminder && simpleTimingChoice != "keep") {
                        put("quickCapture", JsonPrimitive(true))
                        when (simpleTimingChoice) {
                            "today", "tomorrow" -> {
                                put("quickReminderDay", JsonPrimitive(simpleTimingChoice))
                                remove("quickReminderDelayMinutes")
                            }
                            "delay" -> {
                                put("quickReminderDelayMinutes", JsonPrimitive(delayChoices[simpleDelayIndex]))
                                remove("quickReminderDay")
                            }
                        }
                    }
                }
                if (!simpleMode && complexModeVisited) {
                    put("type", JsonPrimitive(type)); put("zoneId", zoneId.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                    put("folderId", folderId.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
                    if (date.isNotEmpty() || existing?.raw?.get("planDate") != null) {
                        put("planDate", date.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
                    }
                    put("note", JsonPrimitive(note)); put("repeat", JsonPrimitive(repeat))
                    put("repeatDays", JsonArray(List(7) { JsonPrimitive(repeatMask and (1 shl it) != 0) }))
                    put("tags", JsonArray(tags.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) }))
                    if (type == "ctx") put("ctxTags", JsonArray(contextTags.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) }))
                    put("counterEnabled", JsonPrimitive(type == "counter" || counterEnabled))
                    if (type == "counter" || counterEnabled) {
                        counterStep.toLongOrNull()?.let { put("counterStep", JsonPrimitive(it)) }
                        counterTarget.toLongOrNull()?.let { put("counterTarget", JsonPrimitive(it)) }
                        put("counterUnit", JsonPrimitive(counterUnit))
                    }
                    if (repeat == "interval") {
                        repeatInterval.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { put("repeatInterval", JsonPrimitive(it)) }
                        put("repeatUnit", JsonPrimitive(repeatUnit))
                    }
                    if (type == "sched") {
                        put("schedDate", JsonPrimitive(date)); put("schedTime", JsonPrimitive(time))
                        put("reminderEnabled", JsonPrimitive(reminderEnabled)); reminderAdvance.toLongOrNull()?.let { put("remindBeforeMinutes", JsonPrimitive(it)) }
                    }
                    if (type == "alarm") put("alarmTime", JsonPrimitive(alarmTime))
                    if (type == "timewin") { put("windowStart", JsonPrimitive(windowStart)); put("windowEnd", JsonPrimitive(windowEnd)) }
                    if (type == "negative") negativeXp.toLongOrNull()?.let { put("negXp", JsonPrimitive(it)) }
                    if (type == "check" && checklistDefinition) put("items", JsonArray(checklist.lineSequence().mapNotNull { line ->
                        line.trim().takeIf(String::isNotEmpty)?.let { text -> buildJsonObject { put("text", text); put("done", false); put("credited", false) } }
                    }.toList()))
                }
            }
            error = ""
            onSave(JsonObject(payload))
            return
        }
        val saveType = if (markerTime != null) "sched" else type
        val saveTime = markerTime ?: time.trim()
        val explicitDateAvailable = dateExplicit && date.isNotBlank()
        val saveDate = if (markerTime != null && !explicitDateAvailable) TaskTitleSchedule.defaultDate(markerTime, now, localZone) else date.trim()
        val saveParsedDate = editorDate(saveDate)
        val saveParsedTime = editorTime(saveTime)
        val savePastTime = saveType == "sched" && saveParsedDate != null && saveParsedTime != null &&
            LocalDateTime.of(saveParsedDate, saveParsedTime).atZone(localZone).toInstant().toEpochMilli() < now
        val saveHasCounter = saveType != "negative" && (saveType == "counter" || counterEnabled)
        val step = editorWholeNumber(counterStep)
        val target = editorWholeNumber(counterTarget)
        val penalty = editorWholeNumber(negativeXp)
        val advance = editorWholeNumber(reminderAdvance)
        val interval = repeatInterval.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        val intervalMs = interval?.times(editorRepeatUnitMillis[repeatUnit] ?: 60_000L)
        val problem = when {
            unavailable -> "Цієї задачі вже немає. Закрий редактор і онови список."
            titleResult == TaskTitleSchedule.Result.InvalidTime -> "Час у позначці має бути від 00:00 до 23:59. Наприклад: ///12:40///."
            titleResult == TaskTitleSchedule.Result.EmptyTitle -> "Напиши, що потрібно зробити."
            catalog.types.none { it.id == saveType } -> "Вибери доступний тип задачі."
            saveDate.isNotBlank() && saveParsedDate == null -> "Введи дату у форматі РРРР-ММ-ДД, наприклад 2026-10-17."
            saveType == "sched" && saveParsedDate == null -> "Вкажи дату запланованої задачі."
            saveType == "sched" && saveParsedTime == null -> "Введи час задачі у форматі ГГ:ХХ, наприклад 15:30."
            saveType == "alarm" && editorTime(alarmTime) == null -> "Введи час будильника у форматі ГГ:ХХ."
            saveType == "timewin" && (editorTime(windowStart) == null || editorTime(windowEnd) == null) -> "Введи початок і кінець вікна у форматі ГГ:ХХ."
            repeat == "custom" && repeatMask == 0 -> "Вибери хоча б один день повторення."
            saveParsedDate != null && repeat in setOf("weekdays", "weekend", "custom") && !editorRepeatsOn(repeat, repeatMask, saveParsedDate) -> "Перша дата не входить у вибрані дні повторення. Зміни дату або повторення."
            (saveType == "zonelinked" || repeat == "everyzone") && zoneOptions.none { it.id == zoneId } -> "Вибери наявну зону для цієї задачі."
            repeat == "interval" && (intervalMs == null || !intervalMs.isFinite() || intervalMs > EDITOR_MAX_INTEGER) -> "Вкажи додатний інтервал у вибраних одиницях."
            saveHasCounter && (step == null || step <= 0 || target == null) -> "Крок лічильника має бути цілим і більшим за 0, ціль — цілим числом від 0."
            saveType == "negative" && penalty == null -> "Штраф досвіду має бути цілим числом від 0."
            (saveType == "sched" || saveType == "alarm") && advance == null -> "Час додаткового нагадування має бути цілим числом хвилин від 0."
            savePastTime && !keepPast && postponeMinutes == null && !tomorrow -> "Цей час уже минув. Підтвердь збереження з минулим часом або зміни його."
            else -> null
        }
        if (problem != null) {
            if (saveHasCounter && (step == null || step <= 0 || target == null)) advancedExpanded = true
            if (markerTime != null && savePastTime && !keepPast) {
                type = "sched"
                time = markerTime
                if (!explicitDateAvailable) { date = saveDate; dateExplicit = false }
            }
            error = problem
            return
        }
        val payload = (existing?.raw ?: JsonObject(emptyMap())).toMutableMap().apply {
            put("title", JsonPrimitive(parsedTitle?.title ?: title.trim())); put("type", JsonPrimitive(saveType))
            put("zoneId", zoneId.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("folderId", folderId.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
            put("planDate", saveDate.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
            put("note", JsonPrimitive(note))
            put("repeat", JsonPrimitive(repeat)); put("repeatDays", JsonArray(List(7) { JsonPrimitive(repeatMask and (1 shl it) != 0) }))
            put("tags", JsonArray(tags.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) }))
            if (saveType == "ctx") put("ctxTags", JsonArray(contextTags.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) }))
            put("counterEnabled", JsonPrimitive(saveHasCounter))
            if (saveHasCounter) {
                put("counterStep", JsonPrimitive(step!!)); put("counterTarget", JsonPrimitive(target!!))
                put("counterUnit", JsonPrimitive(counterUnit))
            }
            if (repeat == "interval") {
                put("repeatInterval", JsonPrimitive(interval!!)); put("repeatUnit", JsonPrimitive(repeatUnit))
            }
            if (saveType == "sched") { put("schedDate", JsonPrimitive(saveDate)); put("schedTime", JsonPrimitive(saveTime)) }
            if (saveType == "sched" || saveType == "alarm") {
                put("reminderEnabled", JsonPrimitive(if (markerTime != null) true else reminderEnabled))
                put("remindBeforeMinutes", JsonPrimitive(if (markerTime != null) 0 else advance!!))
            }
            if (allowQuickReminder && saveType == "simple" && markerTime == null) when (quickReminderChoice) {
                "hour" -> put("quickReminderDelayMinutes", JsonPrimitive(60))
                "off" -> put("clearQuickReminder", JsonPrimitive(true))
            }
            if (saveType == "alarm") put("alarmTime", JsonPrimitive(alarmTime.trim()))
            if (saveType == "timewin") { put("windowStart", JsonPrimitive(windowStart.trim())); put("windowEnd", JsonPrimitive(windowEnd.trim())) }
            if (saveType == "negative") put("negXp", JsonPrimitive(penalty!!))
            if (saveType == "check" && (storedItems.isNullOrEmpty() || checklistDefinition)) put("items", JsonArray(checklist.lineSequence().mapNotNull { line ->
                line.trim().takeIf(String::isNotEmpty)?.let { text -> buildJsonObject {
                    put("text", text); put("done", false); put("credited", false)
                } }
            }.toList()))
        }
        error = ""
        if (onSnooze != null && (postponeMinutes != null || tomorrow)) onSnooze(JsonObject(payload), postponeMinutes, tomorrow)
        else onSave(JsonObject(payload))
    }

    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
    val compactAuthoring = maxHeight < 280.dp
    Column(Modifier.fillMaxSize().testTag("native-task-editor")) {
        if (!compactAuthoring) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("native-task-editor-close")) { Text("Назад") }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (taskId == null) "Нова задача" else "Редагування задачі", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (allowQuickReminder && !checklistDefinition) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = simpleMode, onClick = {
                when {
                    !simpleModeEligible -> error = "Для цього типу потрібна складена задача."
                    taskId != null && !simpleModeAllowed -> error = "Для цих параметрів потрібна складена задача."
                    taskId == null && simpleModeHasAdvancedValues && !simpleModeConfirmed -> confirmSimpleMode = true
                    else -> { simpleMode = true; complexModeVisited = false; error = "" }
                }
            }, enabled = enabled, label = { Text("Проста") }, modifier = Modifier.testTag("native-task-mode-simple"))
            FilterChip(selected = !simpleMode, onClick = { simpleMode = false; complexModeVisited = true; simpleModeConfirmed = false; error = "" }, enabled = enabled, label = { Text("Складена") }, modifier = Modifier.testTag("native-task-mode-complex"))
        }
        if (!simpleModeAllowed) Text("Для цих параметрів потрібна складена задача.", Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("native-task-editor-body"),
            contentPadding = PaddingValues(if (compactAuthoring) 8.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (simpleMode) {
                item {
                    EditorInput("Що потрібно зробити?", title, { title = it; error = "" }, "native-task-title", enabled, minLines = 1, maxLines = 2)
                }
                if (simpleModeConfirmed && simpleModeHasAdvancedValues) item {
                    Text("У просту задачу потраплять лише назва й вибране нагадування.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("native-task-simple-omits-details"))
                }
                item {
                    val today = LocalDate.now(localZone)
                    val todayFive = today.atTime(5, 0).atZone(localZone).toInstant().toEpochMilli()
                    val todayAt = SimpleTaskTiming.dayReminderAt("today", previewNow, localZone)
                    val tomorrowAt = SimpleTaskTiming.dayReminderAt("tomorrow", previewNow, localZone)
                    val markerTime = (TaskTitleSchedule.parse(title) as? TaskTitleSchedule.Result.Valid)?.value?.time
                    val markerDate = markerTime?.let { TaskTitleSchedule.defaultDate(it, previewNow, localZone) }
                    val markerPreview = markerTime?.let { time ->
                        val day = markerDate?.let(::editorDate)?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("uk")))
                        "$day, $time"
                    }
                    val quickTimingEnabled = enabled && markerTime == null
                    val selectedAt = when (simpleTimingChoice) {
                        "today" -> todayAt
                        "tomorrow" -> tomorrowAt
                        "delay" -> previewNow + delayChoices[simpleDelayIndex] * 60_000L
                        else -> null
                    }
                    val formattedAt = selectedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(localZone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("uk"))) }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = simpleTimingChoice == "today", onClick = { simpleTimingChoice = "today"; error = "" }, enabled = quickTimingEnabled,
                                label = { Text(if (previewNow < todayFive) "Сьогодні · 05:00" else "Сьогодні") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-quick-today"))
                            FilterChip(selected = simpleTimingChoice == "tomorrow", onClick = { simpleTimingChoice = "tomorrow"; error = "" }, enabled = quickTimingEnabled,
                                label = { Text("Завтра · 05:00") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-quick-tomorrow"))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = simpleTimingChoice == "delay", onClick = { simpleTimingChoice = "delay"; error = "" }, enabled = quickTimingEnabled,
                                label = { Text("Через ${delayChoices[simpleDelayIndex]} хв") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-quick-delay"))
                            OutlinedButton(onClick = {
                                if (simpleTimingChoice == "delay") {
                                    val next = SimpleTaskTiming.nextDelayMinutes(delayChoices[simpleDelayIndex].toLong()).toInt()
                                    simpleDelayIndex = delayChoices.indexOf(next).coerceAtLeast(0)
                                }
                                simpleTimingChoice = "delay"; error = ""
                            }, enabled = quickTimingEnabled, modifier = Modifier.width(56.dp).heightIn(min = 48.dp).semantics { contentDescription = "Збільшити затримку нагадування" }.testTag("native-task-quick-delay-more")) { Text("+") }
                        }
                        if (markerPreview != null) Text("Заплановано на $markerPreview", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("native-task-quick-preview"))
                        else if (formattedAt != null) Text("Нагадати $formattedAt", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("native-task-quick-preview"))
                        else if (taskId != null && simpleTimingChoice == "keep") {
                            val savedAt = existing?.raw?.editorNumberText("quickReminderAt", "")?.toLongOrNull()
                            val firedAt = existing?.raw?.editorNumberText("quickReminderFiredAt", "")?.toLongOrNull()
                            val summary = savedAt?.takeIf { it > 0L }?.let { instant ->
                                if (firedAt == instant) "Нагадування вже надіслано" else runCatching {
                                    "Нагадування заплановано на " + java.time.Instant.ofEpochMilli(instant).atZone(localZone)
                                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("uk")))
                                }.getOrNull()
                            }
                            Text(summary ?: "Поточне нагадування збережеться, якщо воно є.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
            item {
                EditorInput("Що потрібно зробити?", title, { title = it; error = "" }, "native-task-title", enabled, minLines = 1, maxLines = 2)
                Text("Нагадати о 12:40: ///12:40///", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Тип задачі", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("simple", "sched").forEach { commonId ->
                        val label = catalog.types.firstOrNull { it.id == commonId }?.label ?: if (commonId == "simple") "Звичайна" else "Запланована"
                        FilterChip(selected = type == commonId, onClick = { chooseType(commonId) }, enabled = enabled,
                            label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-type-$commonId"))
                    }
                }
                val otherTypes = catalog.types.filterNot { it.id == "simple" || it.id == "sched" }
                EditorPicker("Інший тип", type.takeIf { it !in setOf("simple", "sched") }.orEmpty(),
                    otherTypes.map { EditorChoice(it.id, it.label) }, enabled, "native-task-type", "Вибрати інший тип") { chooseType(it) }
                catalog.types.firstOrNull { it.id == type }?.hint?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (type == "check") item {
                if (storedItems.isNullOrEmpty() || checklistDefinition) {
                    EditorInput("Кроки — кожен із нового рядка", checklist, { checklist = it; error = "" }, "native-task-checklist", enabled, minLines = 4)
                } else {
                    EditorHeading("Кроки задачі")
                    storedItems.forEachIndexed { index, value ->
                        val step = value as? JsonObject
                        Text("${index + 1}. ${step?.editorText("text").orEmpty()} · ${if (step?.editorBoolean("done", false) == true) "виконано" else "до виконання"}")
                    }
                    Text("Наявні кроки зберігаються разом із їхнім поступом.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { EditorInput("Нотатка до задачі", note, { note = it; error = "" }, "native-task-note", enabled, minLines = 3, maxLines = 5) }
            item {
                EditorHeading("Коли?")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!scheduled) TextButton(onClick = { date = ""; dateExplicit = false; keepPast = false; error = "" }, enabled = enabled, modifier = Modifier.testTag("native-task-no-date")) { Text("Без дати") }
                    TextButton(onClick = { date = LocalDate.now(localZone).toString(); dateExplicit = true; keepPast = false; error = "" }, enabled = enabled) { Text("Сьогодні") }
                    TextButton(onClick = { date = LocalDate.now(localZone).plusDays(1).toString(); dateExplicit = true; keepPast = false; error = "" }, enabled = enabled) { Text("Завтра") }
                }
                EditorDateInput("Дата${if (scheduled) " — обов’язково" else " — необов’язково"}", date,
                    { date = it; dateExplicit = true; keepPast = false; error = "" }, enabled, localZone)
            }
            if (scheduled) item { EditorTimeInput("Час задачі", time, "native-task-time", { time = it; keepPast = false; error = "" }, enabled) }
            if (type == "alarm") item { EditorTimeInput("Час будильника", alarmTime, "native-task-alarm-time", { alarmTime = it; error = "" }, enabled) }
            if (type == "timewin") item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorTimeInput("Початок вікна", windowStart, "native-task-window-start", { windowStart = it; error = "" }, enabled)
                    EditorTimeInput("Кінець вікна", windowEnd, "native-task-window-end", { windowEnd = it; error = "" }, enabled)
                    Text("Вікно може переходити через північ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (allowQuickReminder && type == "simple") item {
                EditorHeading("Нагадування")
                val savedAt = existing?.raw?.editorNumberText("quickReminderAt", "")?.toLongOrNull()
                val firedAt = existing?.raw?.editorNumberText("quickReminderFiredAt", "")?.toLongOrNull()
                val hasPendingReminder = savedAt != null && savedAt > 0L && firedAt != savedAt
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = quickReminderChoice == "off" || quickReminderChoice == null && !hasPendingReminder,
                        onClick = { quickReminderChoice = "off"; error = "" }, enabled = enabled,
                        label = { Text("Без нагадування") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-quick-reminder-off"),
                    )
                    FilterChip(
                        selected = quickReminderChoice == "hour" || quickReminderChoice == null && hasPendingReminder,
                        onClick = { quickReminderChoice = "hour"; error = "" }, enabled = enabled,
                        label = { Text("Через годину") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-quick-reminder-hour"),
                    )
                }
                Text("Задача залишиться доступною у списку.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (quickReminderChoice == null && savedAt != null && savedAt > 0L) {
                    val status = if (firedAt == savedAt) "Нагадування вже надіслано" else runCatching {
                        val due = java.time.Instant.ofEpochMilli(savedAt).atZone(localZone)
                        val time = due.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                        val dateLabel = if (due.toLocalDate() == LocalDate.now(localZone)) "" else
                            due.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("uk"))) + " "
                        "Нагадування заплановано на $dateLabel$time"
                    }.getOrNull()
                    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (pastTime) item {
                EditorToggle("Зберегти з минулим часом", keepPast, { keepPast = it; error = "" }, enabled, "native-task-keep-past")
            }
            if (scheduled || type == "alarm") item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorToggle("Нагадати у заданий час", reminderEnabled, { reminderEnabled = it; error = "" }, enabled, "native-task-reminder")
                    if (scheduled && reminderEnabled) EditorInput("Додатково нагадати за хвилин (0 — без додаткового)", reminderAdvance,
                        { reminderAdvance = it; error = "" }, "native-task-reminder-advance", enabled, number = true)
                }
            }
            if (type == "zonelinked" || repeat == "everyzone") item {
                EditorPicker("Потрібна зона", zoneId, zoneOptions, enabled,
                    "native-task-zone", "Зону видалено — вибери іншу") { zoneId = it; error = "" }
                Text("Для цього налаштування потрібна наявна зона.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                EditorHeading("Повторення")
                EditorPicker("Як часто?", repeat, editorRepeats, enabled, "native-task-repeat") { repeat = it; error = "" }
            }
            if (repeat == "custom") item {
                val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0..3, 4..6).forEach { indices ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            indices.forEach { index ->
                                FilterChip(selected = repeatMask and (1 shl index) != 0,
                                    onClick = { repeatMask = repeatMask xor (1 shl index); error = "" }, enabled = enabled,
                                    label = { Text(days[index]) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-repeat-day-$index"))
                            }
                        }
                    }
                }
            }
            if (repeat == "interval") item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorInput("Інтервал після виконання", repeatInterval, { repeatInterval = it; error = "" }, "native-task-repeat-interval", enabled, number = true)
                    EditorPicker("Одиниця інтервалу", repeatUnit, editorRepeatUnits, enabled, "native-task-repeat-unit") { repeatUnit = it; error = "" }
                }
            }
            if (hasCounter && (type == "counter" || advancedExpanded)) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorInput("Крок лічильника", counterStep, { counterStep = it; error = "" }, "native-task-counter-step", enabled, number = true)
                    EditorPicker("Одиниця лічильника", counterUnit, editorCounterUnits, enabled, "native-task-counter-unit") { counterUnit = it; error = "" }
                    EditorInput("Ціль лічильника (0 — без цілі)", counterTarget, { counterTarget = it; error = "" }, "native-task-counter-target", enabled, number = true)
                    existing?.raw?.editorNumberText("counter", "")?.takeIf { it.isNotEmpty() }?.let {
                        Text("Поточний поступ: $it. Зміна налаштувань його зберігає.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Досягнення цілі не завершує задачу автоматично.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (type == "negative") item {
                EditorInput("Штраф досвіду (0 — без штрафу)", negativeXp, { negativeXp = it; error = "" }, "native-task-negative-xp", enabled, number = true)
            }
            item {
                HorizontalDivider()
                TextButton(onClick = { advancedExpanded = !advancedExpanded }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-task-advanced-toggle")) {
                    Text(if (advancedExpanded) "Приховати додаткові налаштування" else "Додатково · папка, мітки, лічильник")
                }
            }
            if (advancedExpanded) {
                if (type != "zonelinked" && repeat != "everyzone") item {
                    EditorPicker("Зона", zoneId, listOf(EditorChoice("", "Будь-яка зона")) + zoneOptions, enabled,
                        "native-task-zone", "Зону видалено — вибери іншу") { zoneId = it; error = "" }
                }
                if (type != "negative") item {
                    EditorToggle("Лічильник прогресу", type == "counter" || counterEnabled,
                        { counterEnabled = it; error = "" }, enabled && type != "counter", "native-task-counter-enabled")
                }
                item {
                    EditorPicker("Папка", folderId, listOf(EditorChoice("", "Без папки")) + folderOptions, enabled,
                        "native-task-folder", "Папку видалено") { folderId = it; error = "" }
                }
                item { EditorInput("Мітки через кому", tags, { tags = it; error = "" }, "native-task-tags", enabled) }
                if (type == "ctx") item { EditorInput("Контекстні мітки через кому", contextTags, { contextTags = it; error = "" }, "native-task-context-tags", enabled) }
            }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compactAuthoring && !simpleMode && taskId != null && onSnooze != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { save(15) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("+15 хв") }
                TextButton(onClick = { save(60) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("+1 год") }
                TextButton(onClick = { save(tomorrow = true) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Завтра") }
            }
            val message = if (unavailable) "Цієї задачі вже немає. Закрий редактор і онови список." else catalog.error ?: error
            if (message.isNotEmpty()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("native-task-editor-error"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (compactAuthoring) TextButton(onClick = onClose, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("native-task-editor-close")) { Text("Назад") }
            Button(onClick = { save() }, enabled = enabled && catalog.types.isNotEmpty(), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-save")) {
                Text(if (busy) "Зберігаю…" else if (simpleMode || compactAuthoring) { if (taskId == null) "Створити" else "Зберегти" } else if (taskId == null) "Створити задачу" else "Зберегти зміни")
            }
            }
        }
    }
    if (confirmSimpleMode) AlertDialog(
        onDismissRequest = { confirmSimpleMode = false },
        title = { Text("Створити просту задачу?") },
        text = { Text("Збережуться лише назва й вибране коротке нагадування. Нотатка, дата, зона, повторення та інші додаткові параметри в задачу не потраплять. Якщо повернешся до складеної до збереження, чернетка залишиться.") },
        confirmButton = {
            TextButton(onClick = {
                confirmSimpleMode = false
                simpleMode = true
                complexModeVisited = false
                simpleModeConfirmed = true
                error = ""
            }, modifier = Modifier.testTag("native-task-simple-confirm")) { Text("Обрати просту") }
        },
        dismissButton = {
            TextButton(onClick = { confirmSimpleMode = false }, modifier = Modifier.testTag("native-task-simple-cancel")) { Text("Залишити складену") }
        },
    )
    }
}

@Composable
private fun EditorInput(
    label: String, value: String, onChange: (String) -> Unit, tag: String, enabled: Boolean,
    minLines: Int = 1, maxLines: Int = if (minLines == 1) 1 else 8, number: Boolean = false, ascii: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled,
        modifier = modifier.fillMaxWidth().testTag(tag), singleLine = minLines == 1, minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(
            capitalization = if (number || ascii) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            keyboardType = if (number) KeyboardType.Decimal else if (ascii) KeyboardType.Ascii else KeyboardType.Text,
        ))
}

@Composable
private fun EditorDateInput(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, localZone: ZoneId) {
    val context = LocalContext.current
    val initial = editorDate(value) ?: LocalDate.now(localZone)
    val dialogTheme = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) com.flow.adhd.R.style.PotikPickerDark else com.flow.adhd.R.style.PotikPickerLight
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorInput(label, value, onChange, "native-task-date", enabled, ascii = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            DatePickerDialog(ukrainianDialogContext(context), dialogTheme, { _, year, month, day ->
                onChange(LocalDate.of(year, month + 1, day).toString())
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("native-task-date-picker")) {
            Text("Календар")
        }
    }
}

@Composable
private fun EditorTimeInput(label: String, value: String, tag: String, onChange: (String) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val initial = editorTime(value) ?: LocalTime.now()
    val dialogTheme = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) com.flow.adhd.R.style.PotikPickerDark else com.flow.adhd.R.style.PotikPickerLight
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorInput(label, value, onChange, tag, enabled, ascii = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            TimePickerDialog(ukrainianDialogContext(context), dialogTheme, { _, hour, minute -> onChange("%02d:%02d".format(Locale.ROOT, hour, minute)) },
                initial.hour, initial.minute, true).show()
        }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-picker")) {
            Text("Годинник")
        }
    }
}

/** Applies the app's supported dialog language locally while retaining its Activity base context and picker theme. */
private fun ukrainianDialogContext(context: Context): ContextThemeWrapper {
    val configuration = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag("uk"))
    }
    return ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(configuration) }
}

@Composable
private fun EditorPicker(
    label: String, value: String, options: List<EditorChoice>, enabled: Boolean, tag: String,
    missingLabel: String = "Вибери значення", onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
                Text(options.firstOrNull { it.id == value }?.label ?: missingLabel, Modifier.weight(1f)); Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onSelect(option.id) },
                        modifier = Modifier.testTag("$tag-${option.id.ifEmpty { "none" }}"))
                }
            }
        }
    }
}

@Composable
private fun EditorToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun EditorHeading(label: String) {
    Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

private data class EditorChoice(val id: String, val label: String)
private data class EditorType(val id: String, val label: String, val hint: String)
private data class EditorTypeCatalog(val types: List<EditorType>, val error: String? = null)

private val editorRepeats = listOf(
    EditorChoice("none", "Без повторення"), EditorChoice("daily", "Щодня"), EditorChoice("weekly", "Щотижня"),
    EditorChoice("weekdays", "Понеділок — п’ятниця"), EditorChoice("weekend", "Субота й неділя"),
    EditorChoice("custom", "Обрані дні тижня"), EditorChoice("interval", "Через інтервал після виконання"), EditorChoice("everyzone", "Кожна зона"),
)
private val editorRepeatUnits = listOf(
    EditorChoice("sec", "секунд"), EditorChoice("min", "хвилин"), EditorChoice("hour", "годин"),
    EditorChoice("day", "днів"), EditorChoice("week", "тижнів"), EditorChoice("month", "місяців (30 днів)"),
)
private val editorRepeatUnitMillis = mapOf("sec" to 1_000L, "min" to 60_000L, "hour" to 3_600_000L,
    "day" to 86_400_000L, "week" to 604_800_000L, "month" to 2_592_000_000L)
private val editorCounterUnits = listOf(EditorChoice("count", "раз"), EditorChoice("ml", "мл"), EditorChoice("min", "хв"), EditorChoice("kcal", "ккал"))
private const val EDITOR_MAX_INTEGER = 9_007_199_254_740_991L

private fun JsonObject.editorText(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.editorBoolean(key: String, fallback: Boolean) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: fallback
private fun JsonObject.editorNumberText(key: String, fallback: String): String {
    val primitive = this[key] as? JsonPrimitive ?: return fallback
    if (primitive.isString) return primitive.content
    val number = primitive.doubleOrNull?.takeIf { it.isFinite() } ?: return fallback
    return if (number % 1.0 == 0.0 && number in -EDITOR_MAX_INTEGER.toDouble()..EDITOR_MAX_INTEGER.toDouble()) number.toLong().toString()
        else primitive.content
}
private fun editorWholeNumber(raw: String) = raw.trim().toLongOrNull()?.takeIf { it in 0..EDITOR_MAX_INTEGER }
private fun editorDate(raw: String): LocalDate? = raw.trim().takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
private fun editorTime(raw: String): LocalTime? = raw.trim().takeIf { it.matches(Regex("\\d{2}:\\d{2}")) }
    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
private fun editorRepeatsOn(repeat: String, mask: Int, date: LocalDate): Boolean = when (repeat) {
    "weekdays" -> date.dayOfWeek.value < 6
    "weekend" -> date.dayOfWeek.value >= 6
    "custom" -> mask and (1 shl (date.dayOfWeek.value - 1)) != 0
    else -> true
}

/** Existing web drafts and plain native title drafts are read without altering their stored form. */
private fun newEditorSeed(raw: String): JsonObject {
    val draft = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    if (draft?.get("version") == JsonPrimitive(2)) return draft["values"] as? JsonObject ?: JsonObject(emptyMap())
    val values = draft?.takeIf { it["version"] == JsonPrimitive(1) }?.get("values") as? JsonObject
        ?: return buildJsonObject { put("title", if (draft == null) raw else "") }
    val translated = values.toMutableMap()
    mapOf("date" to "planDate", "time" to "schedTime", "winStart" to "windowStart", "winEnd" to "windowEnd",
        "cntTgt" to "counterTarget", "reminder" to "reminderEnabled").forEach { (from, to) -> values[from]?.let { translated[to] = it } }
    values.editorText("date")?.let { translated["schedDate"] = JsonPrimitive(it) }
    translated["zoneId"] = values.editorText("zoneId")?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull
    translated["tags"] = JsonArray(values.editorText("tagsText").orEmpty().split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) })
    translated["remindBeforeMinutes"] = values.editorText("advance")?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(0)
    return JsonObject(translated)
}
