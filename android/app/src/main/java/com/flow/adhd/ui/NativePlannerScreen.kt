package com.flow.adhd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    val date = LocalDate.parse(selected)
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    val context = remember(snapshot) { ProjectionContext(commandTime(), ZoneCalendar.timeline(snapshot.data, commandTime(), zone), zone) }
    val history = snapshot.data.objects("taskHistory")
    val completed = remember(snapshot, selected) { TaskHistory.completedOccurrences(JsonArray(history), JsonArray(snapshot.tasks.map { it.raw }), zone).filter { it.text("date") == selected } }
    val completedIdentities = completed.map { it["taskId"] to it["taskCreated"] }.toSet()
    val dayTasks = snapshot.tasks.filter { TaskSchedule.occursOn(it.raw, selected, context) && (selected >= today.toString() || (it.raw["id"] to it.raw["created"]) !in completedIdentities) }
        .sortedBy { it.raw.text("schedTime").ifEmpty { it.raw.text("alarmTime") } }
    val items = (snapshot.data["planItems"] as? JsonObject)?.objects(selected).orEmpty()
    val ranges = ZoneCalendar.dayRanges(snapshot.data, selected).sortedBy { it.start }
    val rest = PlannerProjection.isRest(snapshot.data, selected)
    val savedNote = ((snapshot.data["planDayLog"] as? JsonObject)?.get(selected) as? JsonObject)?.text("note").orEmpty()
    var dayNote by rememberSaveable(selected) { mutableStateOf(savedNote) }
    var entry by rememberSaveable(selected) { mutableStateOf("") }
    var isEvent by rememberSaveable { mutableStateOf(false) }
    fun send(factory: (String, Long) -> FlowCommand, done: () -> Unit = {}) = onCommand(factory(newCommandId(), commandTime()), done)

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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = date.minusWeeks(1).toString() }) { Text("←") }
                Text(monday.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("uk"))), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { selected = date.plusWeeks(1).toString() }) { Text("→") }
                TextButton(onClick = { selected = today.toString() }) { Text("Сьогодні") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { index ->
                    val day = monday.plusDays(index.toLong())
                    val ds = day.toString()
                    val count = snapshot.tasks.count { TaskSchedule.occursOn(it.raw, ds, context) }
                    Card(Modifier.weight(1f).clickable { selected = ds }.testTag("native-planner-day-$ds"), colors = CardDefaults.cardColors(containerColor = if (ds == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")[index], style = MaterialTheme.typography.labelSmall)
                            Text(day.dayOfMonth.toString())
                            Text(if (count == 0) "·" else count.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("uk"))), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rest, { value -> send({ id, at -> FlowCommand.SetPlanRestDay(selected, value, id, at) }) }, enabled = !busy)
                Text("Вихідний день")
            }
        }
        item {
            Text("Зони дня", style = MaterialTheme.typography.titleMedium)
            for (range in ranges) {
                val name = snapshot.zones.firstOrNull { it.number("id")?.toLong() == range.zoneId }?.text("nm").orEmpty()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${minuteLabel(range.start)}–${minuteLabel(range.end)} · $name", Modifier.weight(1f))
                    TextButton(onClick = { send({ id, at -> FlowCommand.SetPlanZoneOff(selected, range.zoneId, true, id, at) }) }, enabled = !busy) { Text("Вимкнути") }
                }
            }
            val off = ((snapshot.data["planDayOff"] as? JsonObject)?.get(selected) as? JsonArray).orEmpty()
            for (id in off) {
                val number = (id as? JsonPrimitive)?.longOrNull ?: continue
                TextButton(onClick = { send({ cid, at -> FlowCommand.SetPlanZoneOff(selected, number, false, cid, at) }) }, enabled = !busy) {
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
        item { Text("Задачі дня · ${dayTasks.size + completed.size}", style = MaterialTheme.typography.titleMedium) }
        items(dayTasks, key = { it.id }) { task ->
            val done = TaskSchedule.doneOn(task.raw, selected, context, history)
            Card(Modifier.fillMaxWidth()) {
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
        items(items.indices.toList(), key = { "item:$selected:$it" }) { index ->
            val row = items[index]
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!row.flag("isPrompt")) Checkbox(row.flag("done"), { checked -> send({ id, at -> FlowCommand.UpdatePlanItem(selected, index, buildJsonObject { put("done", checked) }, id, at) }) }, enabled = !busy)
                Text(listOf(row.text("time"), row.text("title")).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.weight(1f))
                TextButton(onClick = { send({ id, at -> FlowCommand.DeletePlanItem(selected, index, id, at) }) }, enabled = !busy) { Text("Прибрати") }
            }
        }
        item {
            OutlinedTextField(entry, { entry = it }, label = { Text(if (isEvent) "Подія дня" else "Задача: можна почати з 15:30") }, modifier = Modifier.fillMaxWidth().testTag("native-planner-entry"), enabled = !busy)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(isEvent, { isEvent = it }); Text("Лише запис календаря")
            }
            Button(onClick = { send({ id, at -> FlowCommand.AddPlanItem(selected, buildJsonObject { put("text", entry.trim()); put("isEvent", isEvent) }, id, at) }) { entry = "" } }, enabled = !busy && entry.isNotBlank()) { Text("Додати на цей день") }
        }
        item {
            OutlinedTextField(dayNote, { dayNote = it }, label = { Text("Примітка дня") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !busy)
            TextButton(onClick = { send({ id, at -> FlowCommand.SetPlanDayNote(selected, dayNote, id, at) }) }, enabled = !busy && dayNote != savedNote) { Text("Зберегти примітку") }
        }
    }
    if (resetRequested) AlertDialog(onDismissRequest = { resetRequested = false }, title = { Text("Повернути розклад до правила?") },
        text = { Text("Ручні зміни зон і вихідного на цю дату буде скасовано. Задачі та примітка залишаться.") },
        confirmButton = { TextButton(onClick = { send({ id, at -> FlowCommand.ResetPlanDay(selected, id, at) }) { resetRequested = false } }, enabled = !busy) { Text("Повернути") } },
        dismissButton = { TextButton(onClick = { resetRequested = false }) { Text("Скасувати") } })
}

internal fun minuteLabel(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
