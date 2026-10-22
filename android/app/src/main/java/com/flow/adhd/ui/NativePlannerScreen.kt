package com.flow.adhd.ui

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

@Composable
fun NativePlannerScreen(
    snapshot: FlowSnapshot,
    busy: Boolean,
    onCommand: (FlowCommand, () -> Unit) -> Unit,
    onEditTask: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onAuthoringChanged: (Boolean) -> Unit = {}
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    var editSchedule by rememberSaveable { mutableStateOf(false) }
    var scheduleJson by rememberSaveable { mutableStateOf("[]") }
    var resetRequested by remember { mutableStateOf(false) }
    var showZones by rememberSaveable(selected) { mutableStateOf(false) }
    var showNote by rememberSaveable(selected) { mutableStateOf(false) }
    var showDayActions by rememberSaveable { mutableStateOf(false) }
    var showQuickAdd by rememberSaveable { mutableStateOf(false) }
    var noteSaved by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.parse(selected)
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    val context = remember(snapshot) {
        ProjectionContext(commandTime(), ZoneCalendar.timeline(snapshot.data, commandTime(), zone), zone)
    }
    val history = snapshot.data.objects("taskHistory")
    val completed = remember(snapshot, selected) {
        TaskHistory.completedOccurrences(JsonArray(history), JsonArray(snapshot.tasks.map { it.raw }), zone)
            .filter { it.text("date") == selected }
    }
    val completedIdentities = completed.map { it["taskId"] to it["taskCreated"] }.toSet()
    val dayTasks = snapshot.tasks
        .filter {
            TaskSchedule.occursOn(it.raw, selected, context) &&
                (selected >= today.toString() || (it.raw["id"] to it.raw["created"]) !in completedIdentities)
        }
        .sortedBy { it.raw.text("schedTime").ifEmpty { it.raw.text("alarmTime") } }
    val planItems = (snapshot.data["planItems"] as? JsonObject)?.objects(selected).orEmpty()
    val ranges = ZoneCalendar.dayRanges(snapshot.data, selected).sortedBy { it.start }
    val rest = PlannerProjection.isRest(snapshot.data, selected)
    val savedNote = ((snapshot.data["planDayLog"] as? JsonObject)?.get(selected) as? JsonObject)
        ?.text("note").orEmpty()
    var dayNote by rememberSaveable(selected) { mutableStateOf(savedNote) }
    var entry by rememberSaveable(selected) { mutableStateOf("") }
    var isEvent by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val dayScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val weekScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(selected) { weekScroll.animateScrollToItem((date.dayOfWeek.value - 4).coerceAtLeast(0)) }
    LaunchedEffect(showNote) {
        if (showNote && dayScroll.layoutInfo.totalItemsCount > 0)
            dayScroll.animateScrollToItem(dayScroll.layoutInfo.totalItemsCount - 1)
    }

    fun send(factory: (String, Long) -> FlowCommand, done: () -> Unit = {}) =
        onCommand(factory(newCommandId(), commandTime()), done)

    fun addEntry() {
        if (busy || entry.isBlank()) return
        val submittedText = entry.trim()
        val submittedDate = selected
        val submittedEvent = isEvent
        send({ id, at ->
            FlowCommand.AddPlanItem(
                submittedDate,
                buildJsonObject { put("text", submittedText); put("isEvent", submittedEvent) },
                id,
                at
            )
        }) {
            if (selected == submittedDate && entry.trim() == submittedText) entry = ""
            showQuickAdd = false
        }
    }

    val authoringChanged by rememberUpdatedState(onAuthoringChanged)
    DisposableEffect(showQuickAdd, editSchedule) { authoringChanged(showQuickAdd || editSchedule); onDispose { } }
    DisposableEffect(Unit) { onDispose { authoringChanged(false) } }
    BackHandler((showQuickAdd || editSchedule) && !busy) {
        showQuickAdd = false; editSchedule = false; keyboard?.hide()
    }
    if (showQuickAdd) {
        Column(modifier.fillMaxSize().imePadding().testTag("native-planner-quick-add")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showQuickAdd = false; keyboard?.hide() }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("native-planner-add-close")) { Text("Назад") }
                Text("Додати до плану", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(plannerDateLabel(date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(entry, { entry = it }, label = { Text(if (isEvent) "Подія дня" else "Задача") },
                        placeholder = { if (!isEvent) Text("Напр. 15:30 підготувати звіт") },
                        modifier = Modifier.fillMaxWidth().testTag("native-planner-entry"), enabled = !busy, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addEntry(); keyboard?.hide() }))
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { isEvent = !isEvent }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(isEvent, { isEvent = it }, enabled = !busy)
                        Text("Лише запис календаря", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Button(onClick = { addEntry(); keyboard?.hide() }, enabled = !busy && entry.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp)
                    .testTag("native-planner-submit")) { Text(if (busy) "Зберігаю…" else "Додати") }
        }
        return
    }

    if (editSchedule) {
        NativeForm("Зони на $selected", busy, { editSchedule = false }, {
            send({ id, at ->
                FlowCommand.SetPlanDay(
                    selected,
                    buildJsonObject { put("blocks", Json.parseToJsonElement(scheduleJson)) },
                    id,
                    at
                )
            }) { editSchedule = false }
        }, modifier) {
            NativeScheduleRows(
                snapshot.zones,
                (Json.parseToJsonElement(scheduleJson) as JsonArray).filterIsInstance<JsonObject>(),
                busy
            ) { scheduleJson = JsonArray(it).toString() }
        }
        return
    }

    LazyColumn(
        modifier.fillMaxSize().imePadding().testTag("native-planner"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        state = dayScroll,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { selected = date.minusWeeks(1).toString() },
                        modifier = Modifier.size(48.dp)
                            .semantics { contentDescription = "Попередній тиждень" }
                            .testTag("native-planner-previous-week")
                    ) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                    Text(
                        weekLabel(monday, monday.plusDays(6)),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall
                    )
                    IconButton(
                        onClick = { selected = date.plusWeeks(1).toString() },
                        modifier = Modifier.size(48.dp)
                            .semantics { contentDescription = "Наступний тиждень" }
                            .testTag("native-planner-next-week")
                    ) { Text("›", style = MaterialTheme.typography.headlineSmall) }
                }
                LazyRow(
                    Modifier.fillMaxWidth(),
                    state = weekScroll,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(7) { index ->
                        val day = monday.plusDays(index.toLong())
                        val dayString = day.toString()
                        val count = snapshot.tasks.count { TaskSchedule.occursOn(it.raw, dayString, context) }
                        val isSelected = dayString == selected
                        val isToday = dayString == today.toString()
                        val dayDescription = buildString {
                            append(plannerDateLabel(day))
                            append(", ")
                            append(taskCountLabel(count))
                            if (isToday) append(", сьогодні")
                            if (isSelected) append(", вибрано")
                        }
                        Surface(
                            Modifier.width(48.dp).heightIn(min = 52.dp)
                                .semantics { contentDescription = dayDescription; this.selected = isSelected }
                                .clickable { selected = dayString }
                                .testTag("native-planner-day-$dayString"),
                            shape = MaterialTheme.shapes.small,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    isToday -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")[index],
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium)
                                Text(if (count == 0) "" else count.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val dateText = plannerDateLabel(date).removeSuffix(" ${date.year}")
                    Text(if (date == today) "Сьогодні, " + dateText.substringAfter(", ") else dateText,
                        style = MaterialTheme.typography.titleSmall)
                    Text("${taskCountLabel(dayTasks.size + completed.size + planItems.size)}" + if (rest) " · Вихідний" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = { showQuickAdd = true },
                    enabled = !busy,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Додати до плану" }.testTag("native-planner-add")
                ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
                Box {
                    IconButton(onClick = { showDayActions = true },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Дії дня" }.testTag("native-planner-day-actions")) {
                        Text("⋯", style = MaterialTheme.typography.headlineSmall)
                    }
                    DropdownMenu(expanded = showDayActions, onDismissRequest = { showDayActions = false }) {
                        DropdownMenuItem(
                            text = { Text(if (rest) "Зняти вихідний" else "Позначити вихідним") },
                            onClick = {
                                val next = !rest
                                showDayActions = false
                                send({ id, at -> FlowCommand.SetPlanRestDay(selected, next, id, at) })
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("native-planner-rest-toggle")
                        )
                        DropdownMenuItem(
                            text = { Text("Змінити розклад зон") },
                            onClick = {
                                scheduleJson = JsonArray(ranges.map {
                                    buildJsonObject {
                                        put("zoneId", it.zoneId)
                                        put("s", minuteLabel(it.start))
                                        put("e", minuteLabel(it.end))
                                    }
                                }).toString()
                                showDayActions = false
                                editSchedule = true
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("native-planner-schedule")
                        )
                        DropdownMenuItem(
                            text = { Text("Повернути розклад до правила") },
                            onClick = { showDayActions = false; resetRequested = true },
                            enabled = !busy,
                            modifier = Modifier.testTag("native-planner-reset-schedule")
                        )
                        DropdownMenuItem(
                            text = { Text(if (showNote) "Сховати примітку" else "Відкрити примітку") },
                            onClick = { showDayActions = false; showNote = !showNote; noteSaved = false },
                            modifier = Modifier.testTag("native-planner-note-action")
                        )
                        if (selected != today.toString()) {
                            DropdownMenuItem(
                                text = { Text("Перейти до сьогодні") },
                                onClick = { showDayActions = false; selected = today.toString() },
                                modifier = Modifier.testTag("native-planner-today")
                            )
                        }
                    }
                }
            }
        }

        if (dayTasks.isEmpty() && completed.isEmpty() && planItems.isEmpty()) {
            item {
                Text(
                    "План поки порожній. Натисніть +, щоб додати задачу чи подію.",
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        items(dayTasks, key = { it.id }) { task ->
            val done = TaskSchedule.doneOn(task.raw, selected, context, history)
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PotikCompletionTarget(done,
                        !busy && selected >= today.toString() && TaskSchedule.canToggle(task.raw, selected, context),
                        "Виконати: ${task.title}", Modifier.testTag("native-planner-complete-${task.id}")) { checked ->
                        send({ id, at -> if (task.type == "negative") FlowCommand.RecordNegative(task.id, 1.0, id, at)
                            else FlowCommand.SetTaskDone(task.id, checked, if (checked) at else null, id, at) })
                    }
                    Text(task.title, Modifier.weight(1f).heightIn(min = 48.dp)
                        .clickable(enabled = !busy) { onEditTask(task.id) }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    val time = task.raw.text("schedTime").ifEmpty { task.raw.text("alarmTime") }.ifEmpty {
                        if (task.type == "simple") (task.raw["quickReminderAt"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { at ->
                            runCatching { java.time.Instant.ofEpochMilli(at).atZone(context.zone)
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
                        }.orEmpty() else ""
                    }
                    if (time.isNotEmpty()) Text(time, Modifier.padding(end = 8.dp).testTag("native-planner-time-${task.id}"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        items(completed, key = { "history:${it.text("id")}" }) { event ->
            Text(
                "✓  ${event.text("title")} · ${event.text("time")}",
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(planItems.indices.toList(), key = { "item:$selected:$it" }) { index ->
            val row = planItems[index]
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!row.flag("isPrompt")) {
                        Checkbox(
                            row.flag("done"),
                            { checked ->
                                send({ id, at ->
                                    FlowCommand.UpdatePlanItem(selected, index, buildJsonObject { put("done", checked) }, id, at)
                                })
                            },
                            enabled = !busy
                        )
                    }
                    Text(
                        listOf(row.text("time"), row.text("title")).filter { it.isNotEmpty() }.joinToString(" · "),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = { send({ id, at -> FlowCommand.DeletePlanItem(selected, index, id, at) }) },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Прибрати") }
                }
            }
        }

        item {
            Column {
                TextButton(
                    onClick = { showZones = !showZones },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("native-planner-zones-disclosure")
                ) {
                    Text(if (showZones) "⌄  Зони дня" else "›  Зони дня · ${ranges.size}", Modifier.weight(1f))
                    if (!showZones && ranges.isNotEmpty()) {
                        Text(ranges.take(2).joinToString(" · ") { "${minuteLabel(it.start)}–${minuteLabel(it.end)}" }, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (showZones) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (ranges.isEmpty()) {
                            Text("Для цього дня немає активних зон", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        } else {
                            ranges.forEach { range ->
                                val name = snapshot.zones.firstOrNull { it.number("id")?.toLong() == range.zoneId }?.text("nm").orEmpty()
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${minuteLabel(range.start)}–${minuteLabel(range.end)}", Modifier.widthIn(min = 92.dp), style = MaterialTheme.typography.bodySmall)
                                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    TextButton(
                                        onClick = { send({ id, at -> FlowCommand.SetPlanZoneOff(selected, range.zoneId, true, id, at) }) },
                                        enabled = !busy,
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) { Text("Вимкнути") }
                                }
                            }
                        }
                        val off = ((snapshot.data["planDayOff"] as? JsonObject)?.get(selected) as? JsonArray).orEmpty()
                        off.forEach { id ->
                            val number = (id as? JsonPrimitive)?.longOrNull ?: return@forEach
                            val name = snapshot.zones.firstOrNull { it.number("id")?.toLong() == number }?.text("nm") ?: "Зона"
                            TextButton(
                                onClick = { send({ commandId, at -> FlowCommand.SetPlanZoneOff(selected, number, false, commandId, at) }) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("native-planner-restore-zone-$number")
                            ) { Text("Повернути «$name»") }
                        }
                    }
                }
            }
        }

        item {
            Column {
                TextButton(
                    onClick = { showNote = !showNote; noteSaved = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("native-planner-note-disclosure")
                ) {
                    Text(if (showNote) "⌄  Примітка дня" else "›  Примітка дня", Modifier.weight(1f))
                    if (savedNote.isNotBlank()) Text("Є примітка", style = MaterialTheme.typography.labelSmall)
                }
                if (showNote) {
                    OutlinedTextField(
                        dayNote,
                        { dayNote = it; noteSaved = false },
                        label = { Text("Примітка дня") },
                        modifier = Modifier.fillMaxWidth().testTag("native-planner-note"),
                        minLines = 2,
                        enabled = !busy
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (noteSaved) Text("Збережено", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        else Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                send({ id, at -> FlowCommand.SetPlanDayNote(selected, dayNote, id, at) }) { noteSaved = true }
                            },
                            enabled = !busy && dayNote != savedNote,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("native-planner-save-note")
                        ) { Text("Зберегти") }
                    }
                }
            }
        }
    }

    if (resetRequested) AlertDialog(
        onDismissRequest = { resetRequested = false },
        title = { Text("Повернути розклад до правила?") },
        text = { Text("Ручні зміни зон і вихідного на цю дату буде скасовано. Задачі та примітка залишаться.") },
        confirmButton = {
            TextButton(onClick = {
                send({ id, at -> FlowCommand.ResetPlanDay(selected, id, at) }) { resetRequested = false }
            }, enabled = !busy) { Text("Повернути") }
        },
        dismissButton = { TextButton(onClick = { resetRequested = false }) { Text("Скасувати") } }
    )
}

private fun weekLabel(start: LocalDate, end: LocalDate): String {
    val locale = Locale.forLanguageTag("uk")
    val startMonth = start.month == end.month
    val startText = start.format(DateTimeFormatter.ofPattern(if (startMonth) "d" else "d MMM", locale))
    val endText = end.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    return "$startText–$endText"
}

// API 28's locale data does not reliably inflect Ukrainian month names in full dates.
private fun plannerDateLabel(date: LocalDate): String {
    val days = listOf("Понеділок", "Вівторок", "Середа", "Четвер", "П’ятниця", "Субота", "Неділя")
    val months = listOf("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня")
    return "${days[date.dayOfWeek.value - 1]}, ${date.dayOfMonth} ${months[date.monthValue - 1]} ${date.year}"
}

private fun taskCountLabel(count: Int): String {
    val remainder100 = count % 100
    val remainder10 = count % 10
    val noun = when {
        remainder100 in 11..14 -> "задач"
        remainder10 == 1 -> "задача"
        remainder10 in 2..4 -> "задачі"
        else -> "задач"
    }
    return "$count $noun"
}

internal fun minuteLabel(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
