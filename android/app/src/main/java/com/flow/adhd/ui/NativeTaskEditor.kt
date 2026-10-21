package com.flow.adhd.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.FlowSnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    val context = LocalContext.current
    val catalog by produceState<EditorTypeCatalog?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val raw = com.flow.adhd.data.NativeContent.read(context, "task-types.json")
                val source = Json.parseToJsonElement(raw) as JsonObject
                require(source.isNotEmpty())
                EditorTypeCatalog(source.map { (id, definition) ->
                    val fields = definition as JsonObject
                    EditorType(id, fields.editorText("label") ?: error("Missing task label"), fields.editorText("hint").orEmpty())
                })
            }.getOrElse { EditorTypeCatalog(emptyList(), "Не вдалося завантажити типи задач. Закрий редактор і відкрий його знову.") }
        }
    }
    val existing = snapshot.tasks.firstOrNull { it.id == taskId }
    val seed = remember(taskId) { existing?.raw ?: newEditorSeed(snapshot.data.editorText("taskEditorDraft").orEmpty()) }
    var title by rememberSaveable(taskId) { mutableStateOf(seed.editorText("title").orEmpty()) }
    var type by rememberSaveable(taskId) { mutableStateOf(seed.editorText("type") ?: "simple") }
    var zoneId by rememberSaveable(taskId) { mutableStateOf(seed.editorNumberText("zoneId", "")) }
    var folderId by rememberSaveable(taskId) { mutableStateOf(seed.editorText("folderId").orEmpty()) }
    var date by rememberSaveable(taskId) { mutableStateOf((if (seed.editorText("type") == "sched") seed.editorText("schedDate") else null) ?: seed.editorText("planDate").orEmpty()) }
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
        if (id == "counter") { counterEnabled = true; if (counterTarget == "0") counterTarget = "10" }
        if (id == "sched" && date.isEmpty()) date = LocalDate.now(localZone).toString()
    }

    val draftText = buildJsonObject {
        put("version", 2)
        put("values", buildJsonObject {
            put("title", title); put("type", type); put("zoneId", zoneId.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull); put("folderId", folderId)
            put("planDate", date); put("schedDate", date); put("schedTime", time); put("alarmTime", alarmTime); put("windowStart", windowStart); put("windowEnd", windowEnd)
            put("note", note); put("repeat", repeat); put("repeatDays", JsonArray(List(7) { JsonPrimitive(repeatMask and (1 shl it) != 0) }))
            put("repeatInterval", repeatInterval); put("repeatUnit", repeatUnit); put("counterEnabled", counterEnabled); put("counterTarget", counterTarget); put("counterStep", counterStep); put("counterUnit", counterUnit)
            put("negXp", negativeXp); put("reminderEnabled", reminderEnabled); put("remindBeforeMinutes", reminderAdvance); put("checklistDraft", checklist)
            put("tags", JsonArray(tags.split(',').map { JsonPrimitive(it.trim()) })); put("ctxTags", JsonArray(contextTags.split(',').map { JsonPrimitive(it.trim()) }))
        })
    }.toString()
    LaunchedEffect(taskId, draftText) { if (taskId == null) onDraftChanged(draftText) }

    fun save(postponeMinutes: Long? = null, tomorrow: Boolean = false) {
        val step = editorWholeNumber(counterStep)
        val target = editorWholeNumber(counterTarget)
        val penalty = editorWholeNumber(negativeXp)
        val advance = editorWholeNumber(reminderAdvance)
        val interval = repeatInterval.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        val intervalMs = interval?.times(editorRepeatUnitMillis[repeatUnit] ?: 60_000L)
        val problem = when {
            unavailable -> "Цієї задачі вже немає. Закрий редактор і онови список."
            title.isBlank() -> "Напиши, що потрібно зробити."
            catalog?.types?.none { it.id == type } != false -> "Вибери доступний тип задачі."
            date.isNotBlank() && parsedDate == null -> "Введи дату у форматі РРРР-ММ-ДД, наприклад 2026-10-17."
            scheduled && parsedDate == null -> "Вкажи дату запланованої задачі."
            scheduled && parsedTime == null -> "Введи час задачі у форматі ГГ:ХХ, наприклад 15:30."
            type == "alarm" && editorTime(alarmTime) == null -> "Введи час будильника у форматі ГГ:ХХ."
            type == "timewin" && (editorTime(windowStart) == null || editorTime(windowEnd) == null) -> "Введи початок і кінець вікна у форматі ГГ:ХХ."
            repeat == "custom" && repeatMask == 0 -> "Вибери хоча б один день повторення."
            parsedDate != null && repeat in setOf("weekdays", "weekend", "custom") && !editorRepeatsOn(repeat, repeatMask, parsedDate) -> "Перша дата не входить у вибрані дні повторення. Зміни дату або повторення."
            (type == "zonelinked" || repeat == "everyzone") && zoneOptions.none { it.id == zoneId } -> "Вибери наявну зону для цієї задачі."
            repeat == "interval" && (intervalMs == null || !intervalMs.isFinite() || intervalMs > EDITOR_MAX_INTEGER) -> "Вкажи додатний інтервал у вибраних одиницях."
            hasCounter && (step == null || step <= 0 || target == null) -> "Крок лічильника має бути цілим і більшим за 0, ціль — цілим числом від 0."
            type == "negative" && penalty == null -> "Штраф досвіду має бути цілим числом від 0."
            (scheduled || type == "alarm") && advance == null -> "Час додаткового нагадування має бути цілим числом хвилин від 0."
            pastTime && !keepPast && postponeMinutes == null && !tomorrow -> "Цей час уже минув. Підтвердь збереження з минулим часом або зміни його."
            else -> null
        }
        if (problem != null) {
            if (hasCounter && (step == null || step <= 0 || target == null)) advancedExpanded = true
            error = problem
            return
        }
        val payload = (existing?.raw ?: JsonObject(emptyMap())).toMutableMap().apply {
            put("title", JsonPrimitive(title.trim())); put("type", JsonPrimitive(type))
            put("zoneId", zoneId.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("folderId", folderId.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
            put("planDate", date.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
            put("note", JsonPrimitive(note))
            put("repeat", JsonPrimitive(repeat)); put("repeatDays", JsonArray(List(7) { JsonPrimitive(repeatMask and (1 shl it) != 0) }))
            put("tags", JsonArray(tags.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) }))
            if (type == "ctx") put("ctxTags", JsonArray(contextTags.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) }))
            put("counterEnabled", JsonPrimitive(hasCounter))
            if (hasCounter) {
                put("counterStep", JsonPrimitive(step!!)); put("counterTarget", JsonPrimitive(target!!))
                put("counterUnit", JsonPrimitive(counterUnit))
            }
            if (repeat == "interval") {
                put("repeatInterval", JsonPrimitive(interval!!)); put("repeatUnit", JsonPrimitive(repeatUnit))
            }
            if (scheduled) { put("schedDate", JsonPrimitive(date.trim())); put("schedTime", JsonPrimitive(time.trim())) }
            if (scheduled || type == "alarm") {
                put("reminderEnabled", JsonPrimitive(reminderEnabled)); put("remindBeforeMinutes", JsonPrimitive(advance!!))
            }
            if (type == "alarm") put("alarmTime", JsonPrimitive(alarmTime.trim()))
            if (type == "timewin") { put("windowStart", JsonPrimitive(windowStart.trim())); put("windowEnd", JsonPrimitive(windowEnd.trim())) }
            if (type == "negative") put("negXp", JsonPrimitive(penalty!!))
            if (type == "check" && (storedItems.isNullOrEmpty() || checklistDefinition)) put("items", JsonArray(checklist.lineSequence().mapNotNull { line ->
                line.trim().takeIf(String::isNotEmpty)?.let { text -> buildJsonObject {
                    put("text", text); put("done", false); put("credited", false)
                } }
            }.toList()))
        }
        error = ""
        if (onSnooze != null && (postponeMinutes != null || tomorrow)) onSnooze(JsonObject(payload), postponeMinutes, tomorrow)
        else onSave(JsonObject(payload))
    }

    Column(modifier.fillMaxSize().imePadding().testTag("native-task-editor")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("native-task-editor-close")) { Text("Назад") }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (taskId == null) "Нова задача" else "Редагування задачі", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text("Спочатку назва й час. Решту можна налаштувати за потреби.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("native-task-editor-body"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                EditorInput("Що потрібно зробити?", title, { title = it; error = "" }, "native-task-title", enabled, minLines = 1, maxLines = 2)
            }
            item {
                Text("Тип задачі", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("simple", "sched").forEach { commonId ->
                        val label = catalog?.types?.firstOrNull { it.id == commonId }?.label ?: if (commonId == "simple") "Звичайна" else "Запланована"
                        FilterChip(selected = type == commonId, onClick = { chooseType(commonId) }, enabled = enabled,
                            label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-task-type-$commonId"))
                    }
                }
                val otherTypes = catalog?.types.orEmpty().filterNot { it.id == "simple" || it.id == "sched" }
                EditorPicker("Інший тип", type.takeIf { it !in setOf("simple", "sched") }.orEmpty(),
                    otherTypes.map { EditorChoice(it.id, it.label) }, enabled, "native-task-type", "Вибрати інший тип") { chooseType(it) }
                catalog?.types?.firstOrNull { it.id == type }?.hint?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (catalog == null) Text("Завантажую типи задач…")
                catalog?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                    if (!scheduled) TextButton(onClick = { date = ""; keepPast = false; error = "" }, enabled = enabled, modifier = Modifier.testTag("native-task-no-date")) { Text("Без дати") }
                    TextButton(onClick = { date = LocalDate.now(localZone).toString(); keepPast = false; error = "" }, enabled = enabled) { Text("Сьогодні") }
                    TextButton(onClick = { date = LocalDate.now(localZone).plusDays(1).toString(); keepPast = false; error = "" }, enabled = enabled) { Text("Завтра") }
                }
                EditorDateInput("Дата${if (scheduled) " — обов’язково" else " — необов’язково"}", date,
                    { date = it; keepPast = false; error = "" }, enabled, localZone)
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
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (taskId != null && onSnooze != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { save(15) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("+15 хв") }
                TextButton(onClick = { save(60) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("+1 год") }
                TextButton(onClick = { save(tomorrow = true) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Завтра") }
            }
            val message = if (unavailable) "Цієї задачі вже немає. Закрий редактор і онови список." else error
            if (message.isNotEmpty()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("native-task-editor-error"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { save() }, enabled = enabled && catalog?.types?.isNotEmpty() == true, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-task-save")) {
                Text(if (busy) "Зберігаю…" else if (taskId == null) "Створити задачу" else "Зберегти зміни")
            }
        }
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
            DatePickerDialog(context, dialogTheme, { _, year, month, day ->
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
            TimePickerDialog(context, dialogTheme, { _, hour, minute -> onChange("%02d:%02d".format(java.util.Locale.ROOT, hour, minute)) },
                initial.hour, initial.minute, true).show()
        }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-picker")) {
            Text("Годинник")
        }
    }
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
