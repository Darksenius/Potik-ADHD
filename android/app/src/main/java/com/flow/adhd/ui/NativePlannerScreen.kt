package com.flow.adhd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

@Composable fun NativePlannerScreen(snapshot: FlowSnapshot, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit, onEditTask: (Long) -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    var editSchedule by rememberSaveable { mutableStateOf(false) }
    var scheduleJson by rememberSaveable { mutableStateOf("[]") }
    var resetRequested by remember { mutableStateOf(false) }
    var showScheduleControls by rememberSaveable(selected) { mutableStateOf(false) }
    val date = LocalDate.parse(selected)
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    val context = remember(snapshot) { ProjectionContext(commandTime(), ZoneCalendar.timeline(snapshot.data, commandTime(), zone), zone) }
    val history = snapshot.data.objects("taskHistory")
    val completed = remember(snapshot, selected) { TaskHistory.completedOccurrences(JsonArray(history), JsonArray(snapshot.tasks.map { it.raw }), zone).filter { it.text("date") == selected } }
    val completedIdentities = completed.map { it["taskId"] to it["taskCreated"] }.toSet()
    val dayTasks = snapshot.tasks.filter { TaskSchedule.occursOn(it.raw, selected, context) && (selected >= today.toString() || (it.raw["id"] to it.raw["created"]) !in completedIdentities) }
        .sortedBy { it.raw.text("schedTime").ifEmpty { it.raw.text("alarmTime") } }
    val planItems = (snapshot.data["planItems"] as? JsonObject)?.objects(selected).orEmpty()
    val ranges = ZoneCalendar.dayRanges(snapshot.data, selected).sortedBy { it.start }
    val rest = PlannerProjection.isRest(snapshot.data, selected)
    val savedNote = ((snapshot.data["planDayLog"] as? JsonObject)?.get(selected) as? JsonObject)?.text("note").orEmpty()
    var dayNote by rememberSaveable(selected) { mutableStateOf(savedNote) }
    var entry by rememberSaveable(selected) { mutableStateOf("") }
    var isEvent by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun send(factory: (String, Long) -> FlowCommand, done: () -> Unit = {}) = onCommand(factory(newCommandId(), commandTime()), done)
    fun addEntry() {
        if (busy || entry.isBlank()) return
        val submittedText = entry.trim()
        val submittedDate = selected
        val submittedEvent = isEvent
        send({ id, at -> FlowCommand.AddPlanItem(submittedDate, buildJsonObject { put("text", submittedText); put("isEvent", submittedEvent) }, id, at) }) {
            if (selected == submittedDate && entry.trim() == submittedText) entry = ""
        }
    }

    if (editSchedule) {
        NativeForm("Зони на $selected", busy, { editSchedule = false }, {
            send({ id, at -> FlowCommand.SetPlanDay(selected, buildJsonObject { put("blocks", Json.parseToJsonElement(scheduleJson)) }, id, at) }) { editSchedule = false }
        }, modifier) {
            NativeScheduleRows(snapshot.zones, (Json.parseToJsonElement(scheduleJson) as JsonArray).filterIsInstance<JsonObject>(), busy) { scheduleJson = JsonArray(it).toString() }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize().imePadding().testTag("native-planner"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selected = date.minusWeeks(1).toString() }) { Text("←") }
                        Text(weekLabel(monday, monday.plusDays(6)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { selected = date.plusWeeks(1).toString() }) { Text("→") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(7) { index ->
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
                            Card(
                                Modifier.weight(1f).heightIn(min = 48.dp)
                                    .border(1.dp, if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                    .semantics { contentDescription = dayDescription; this.selected = isSelected }
                                    .clickable { selected = dayString }
                                    .testTag("native-planner-day-$dayString"),
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")[index], style = MaterialTheme.typography.labelSmall)
                                    Text(day.dayOfMonth.toString(), style = if (isSelected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                                    Text(if (count == 0) "·" else count.toString(), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (selected != today.toString()) TextButton(onClick = { selected = today.toString() }, modifier = Modifier.align(Alignment.End)) { Text("До сьогодні") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plannerDateLabel(date), style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rest, { value -> send({ id, at -> FlowCommand.SetPlanRestDay(selected, value, id, at) }) }, enabled = !busy)
                        Text("Вихідний день")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Додати до плану", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        entry,
                        { entry = it },
                        label = { Text(if (isEvent) "Подія дня" else "Задача: можна почати з 15:30") },
                        modifier = Modifier.fillMaxWidth().testTag("native-planner-entry"),
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addEntry(); keyboard?.hide() })
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(isEvent, { isEvent = it }, enabled = !busy)
                        Text("Лише запис календаря")
                    }
                    Button(onClick = { addEntry() }, enabled = !busy && entry.isNotBlank()) { Text("Додати на цей день") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Зони дня", style = MaterialTheme.typography.titleMedium)
                    if (ranges.isEmpty()) {
                        Text("Для цього дня немає активних зон", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                            items(ranges.size) { index ->
                                val range = ranges[index]
                                val name = snapshot.zones.firstOrNull { it.number("id")?.toLong() == range.zoneId }?.text("nm").orEmpty()
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.widthIn(min = 112.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text("${minuteLabel(range.start)}–${minuteLabel(range.end)}", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                                        Text(name, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showScheduleControls = !showScheduleControls }, enabled = !busy) {
                        Text(if (showScheduleControls) "Приховати керування зонами" else "Керувати зонами")
                    }
                    if (showScheduleControls) {
                        for (range in ranges) {
                            val name = snapshot.zones.firstOrNull { it.number("id")?.toLong() == range.zoneId }?.text("nm").orEmpty()
                            TextButton(onClick = { send({ id, at -> FlowCommand.SetPlanZoneOff(selected, range.zoneId, true, id, at) }) }, enabled = !busy) { Text("Вимкнути $name") }
                        }
                        val off = ((snapshot.data["planDayOff"] as? JsonObject)?.get(selected) as? JsonArray).orEmpty()
                        for (id in off) {
                            val number = (id as? JsonPrimitive)?.longOrNull ?: continue
                            TextButton(onClick = { send({ commandId, at -> FlowCommand.SetPlanZoneOff(selected, number, false, commandId, at) }) }, enabled = !busy) {
                                Text("Повернути «${snapshot.zones.firstOrNull { it.number("id")?.toLong() == number }?.text("nm") ?: "Зона"}»")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scheduleJson = JsonArray(ranges.map { buildJsonObject { put("zoneId", it.zoneId); put("s", minuteLabel(it.start)); put("e", minuteLabel(it.end)) } }).toString()
                                editSchedule = true
                            }, enabled = !busy, modifier = Modifier.testTag("native-planner-schedule")) { Text("Змінити день") }
                            TextButton(onClick = { resetRequested = true }, enabled = !busy) { Text("За правилом") }
                        }
                    }
                }
            }
        }
        item { Text("Задачі дня · ${dayTasks.size + completed.size}", style = MaterialTheme.typography.titleMedium) }
        if (dayTasks.isEmpty() && completed.isEmpty() && planItems.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Text("План поки порожній. Додайте задачу чи подію вище.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(dayTasks, key = { it.id }) { task ->
            val done = TaskSchedule.doneOn(task.raw, selected, context, history)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(done, { checked -> send({ id, at -> if (task.type == "negative") FlowCommand.RecordNegative(task.id, 1.0, id, at) else FlowCommand.SetTaskDone(task.id, checked, if (checked) at else null, id, at) }) },
                        enabled = !busy && selected >= today.toString() && TaskSchedule.canToggle(task.raw, selected, context))
                    TextButton(onClick = { onEditTask(task.id) }, modifier = Modifier.weight(1f)) { Text(task.title) }
                    Text(task.raw.text("schedTime"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        items(completed, key = { "history:${it.text("id")}" }) { event ->
            Text("Виконано: ${event.text("title")} · ${event.text("time")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(planItems.indices.toList(), key = { "item:$selected:$it" }) { index ->
            val row = planItems[index]
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!row.flag("isPrompt")) Checkbox(row.flag("done"), { checked -> send({ id, at -> FlowCommand.UpdatePlanItem(selected, index, buildJsonObject { put("done", checked) }, id, at) }) }, enabled = !busy)
                Text(listOf(row.text("time"), row.text("title")).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.weight(1f))
                TextButton(onClick = { send({ id, at -> FlowCommand.DeletePlanItem(selected, index, id, at) }) }, enabled = !busy) { Text("Прибрати") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Примітка дня", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(dayNote, { dayNote = it }, label = { Text("Примітка дня") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !busy)
                    TextButton(onClick = { send({ id, at -> FlowCommand.SetPlanDayNote(selected, dayNote, id, at) }) }, enabled = !busy && dayNote != savedNote) { Text("Зберегти примітку") }
                }
            }
        }
    }
    if (resetRequested) AlertDialog(onDismissRequest = { resetRequested = false }, title = { Text("Повернути розклад до правила?") },
        text = { Text("Ручні зміни зон і вихідного на цю дату буде скасовано. Задачі та примітка залишаться.") },
        confirmButton = { TextButton(onClick = { send({ id, at -> FlowCommand.ResetPlanDay(selected, id, at) }) { resetRequested = false } }, enabled = !busy) { Text("Повернути") } },
        dismissButton = { TextButton(onClick = { resetRequested = false }) { Text("Скасувати") } })
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
