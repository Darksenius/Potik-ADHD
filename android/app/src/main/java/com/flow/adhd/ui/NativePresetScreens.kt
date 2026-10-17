package com.flow.adhd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.*
import java.time.LocalDate
import kotlinx.serialization.json.*

@Composable fun NativePresetsScreen(snapshot: FlowSnapshot, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf("") }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var applyKind by rememberSaveable { mutableStateOf("") }
    var applyId by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var span by rememberSaveable { mutableStateOf("1") }
    var dialogError by rememberSaveable { mutableStateOf("") }
    var deleteKind by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf("") }
    val dayTemplates = snapshot.data.objects("dayTemplates")
    val weekTemplates = snapshot.data.objects("weekTemplates")
    if (kind.isNotEmpty()) {
        BackHandler(!busy) { kind = "" }
        val source = (if (kind == "day") dayTemplates else weekTemplates).firstOrNull { it.text("id") == editorId }
        val save: (JsonObject) -> Unit = { payload ->
            val id = newCommandId(); val at = commandTime()
            val command = when {
                kind == "day" && editorId != null -> FlowCommand.UpdateTemplate(editorId!!, payload, id, at)
                kind == "day" -> FlowCommand.CreateTemplate(payload, id, at)
                editorId != null -> FlowCommand.UpdateWeekTemplate(editorId!!, payload, id, at)
                else -> FlowCommand.CreateWeekTemplate(payload, id, at)
            }
            onCommand(command) { kind = "" }
        }
        if (kind == "day") NativeDayPresetEditor(snapshot, source, busy, save, { kind = "" }, modifier)
        else NativeCycleEditor(dayTemplates, source, busy, save, { kind = "" }, modifier)
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("native-presets"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Пресети дня", style = MaterialTheme.typography.headlineSmall)
            Text("Типові зони й справи створюють окремі виконання на кожну дату. Повторне застосування не створює дублів і не скидає галочки.")
            Button(onClick = { editorId = null; kind = "day" }, enabled = !busy, modifier = Modifier.testTag("native-preset-create")) { Text("+ Пресет дня") }
        }
        items(dayTemplates, key = { "day:${it.text("id")}" }) { template ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(template.text("name"), style = MaterialTheme.typography.titleMedium)
                Text("Блоків: ${template.objects("blocks").size} · типових справ: ${template.objects("tasks").size}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { editorId = template.text("id"); kind = "day" }, enabled = !busy) { Text("Змінити") }
                    TextButton(onClick = { applyKind = "day"; applyId = template.text("id"); span = "1" }, enabled = !busy, modifier = Modifier.testTag("native-preset-apply-${template.text("id")}")) { Text("Застосувати") }
                    TextButton(onClick = { deleteKind = "day"; deleteId = template.text("id") }, enabled = !busy) { Text("Видалити") }
                }
            } }
        }
        item {
            Text("Тижні й довільні цикли", style = MaterialTheme.typography.titleLarge)
            Text("Сім днів — розклад із понеділка до неділі. Цикл іншої довжини починається з дати застосування.")
            Button(onClick = { editorId = null; kind = "week" }, enabled = !busy) { Text("+ Тиждень або цикл") }
        }
        items(weekTemplates, key = { "week:${it.text("id")}" }) { template ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(template.text("name"), style = MaterialTheme.typography.titleMedium)
                Text("Днів у циклі: ${template.objects("days").size}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { editorId = template.text("id"); kind = "week" }, enabled = !busy) { Text("Змінити") }
                    TextButton(onClick = { applyKind = "week"; applyId = template.text("id"); span = "28" }, enabled = !busy) { Text("Застосувати") }
                    TextButton(onClick = { deleteKind = "week"; deleteId = template.text("id") }, enabled = !busy) { Text("Видалити") }
                }
            } }
        }
        item { Text("Призначені правила", style = MaterialTheme.typography.titleLarge) }
        items(snapshot.data.objects("planRules").indices.toList(), key = { "rule:$it" }) { index ->
            val rule = snapshot.data.objects("planRules")[index]
            Row(Modifier.fillMaxWidth()) {
                Text("${rule.text("start")} — ${rule.text("end")} · цикл ${rule.objects("days").size} днів", Modifier.weight(1f))
                TextButton(onClick = { deleteKind = "rule"; deleteId = index.toString() }, enabled = !busy) { Text("Прибрати") }
            }
        }
    }
    if (applyKind.isNotEmpty()) AlertDialog(onDismissRequest = { applyKind = "" }, title = { Text("Призначити розклад") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(start, { start = it; dialogError = "" }, label = { Text("Початок · РРРР-ММ-ДД") }, singleLine = true)
            OutlinedTextField(span, { span = it; dialogError = "" }, label = { Text("На скільки днів?") }, singleLine = true, modifier = Modifier.testTag("native-preset-span"))
            Text("Зони будуть призначені на дати. Уже створені типові задачі й виконання залишаться.")
            if (dialogError.isNotEmpty()) Text(dialogError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val count = span.toIntOrNull()
        if (runCatching { LocalDate.parse(start).toString() == start }.getOrDefault(false) && count != null && count in 1..3660) {
            val command = if (applyKind == "day") FlowCommand.ApplyTemplate(applyId, start, count, newCommandId(), commandTime()) else FlowCommand.ApplyWeekTemplate(applyId, start, count, newCommandId(), commandTime())
            onCommand(command) { applyKind = "" }
        } else dialogError = "Перевір дату та кількість днів від 1 до 3660."
    }, enabled = !busy, modifier = Modifier.testTag("native-preset-apply-confirm")) { Text("Застосувати") } }, dismissButton = { TextButton(onClick = { applyKind = "" }, enabled = !busy) { Text("Скасувати") } })
    if (deleteKind.isNotEmpty()) AlertDialog(onDismissRequest = { deleteKind = "" }, title = { Text("Прибрати визначення?") }, text = { Text("Уже створені задачі й виконання залишаться. Для пресету раніше призначені зони збережуть свій розклад.") },
        confirmButton = { TextButton(onClick = {
            val command = when (deleteKind) {
                "day" -> FlowCommand.DeleteTemplate(deleteId, newCommandId(), commandTime())
                "week" -> FlowCommand.DeleteWeekTemplate(deleteId, newCommandId(), commandTime())
                else -> FlowCommand.DeletePlanRule(deleteId.toInt(), newCommandId(), commandTime())
            }
            onCommand(command) { deleteKind = "" }
        }, enabled = !busy) { Text("Прибрати") } }, dismissButton = { TextButton(onClick = { deleteKind = "" }) { Text("Скасувати") } })
}

@Composable private fun NativeDayPresetEditor(snapshot: FlowSnapshot, source: JsonObject?, busy: Boolean, onSave: (JsonObject) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    var name by rememberSaveable { mutableStateOf(source?.text("name").orEmpty()) }
    var blocksText by rememberSaveable { mutableStateOf(JsonArray(source?.objects("blocks").orEmpty()).toString()) }
    var tasksText by rememberSaveable { mutableStateOf(JsonArray(source?.objects("tasks").orEmpty()).toString()) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    val tasks = (Json.parseToJsonElement(tasksText) as JsonArray).filterIsInstance<JsonObject>()
    if (editor) {
        val previous = editingIndex?.let(tasks::getOrNull)
        val definition = previous?.get("task") as? JsonObject
        val tempId = 700L
        val virtual = FlowSnapshot.fromData(JsonObject(snapshot.data + mapOf(
            "taskEditorDraft" to JsonPrimitive(""),
            "tasks" to JsonArray(if (definition == null) emptyList() else listOf(JsonObject(TaskFactory.create(tempId, definition.text("title"), definition.text("type"), commandTime()) + definition + mapOf("id" to JsonPrimitive(tempId), "planDate" to JsonPrimitive(LocalDate.now().toString()), "schedDate" to JsonPrimitive(LocalDate.now().toString()))))),
        )))
        BackHandler { editor = false }
        NativeTaskEditor(virtual, if (definition == null) null else tempId, busy = false, onSave = { payload ->
            val entry = buildJsonObject { put("id", previous?.text("id") ?: newCommandId()); put("task", payload) }
            tasksText = JsonArray(if (editingIndex == null) tasks + entry else tasks.mapIndexed { index, task -> if (index == editingIndex) entry else task }).toString()
            editor = false
        }, onClose = { editor = false }, modifier)
        return
    }
    NativeForm("Пресет дня", busy, onClose, {
        if (name.isBlank()) error = "Напиши назву пресету."
        else onSave(buildJsonObject { put("name", name); put("blocks", Json.parseToJsonElement(blocksText)); put("tasks", Json.parseToJsonElement(tasksText)) })
    }, modifier.testTag("native-preset-editor")) {
        OutlinedTextField(name, { name = it }, label = { Text("Назва пресету") }, modifier = Modifier.fillMaxWidth().testTag("native-preset-name"), enabled = !busy)
        Text("Зони", style = MaterialTheme.typography.titleMedium)
        NativeScheduleRows(snapshot.zones, (Json.parseToJsonElement(blocksText) as JsonArray).filterIsInstance<JsonObject>(), busy) { blocksText = JsonArray(it).toString() }
        Text("Типові справи", style = MaterialTheme.typography.titleMedium)
        Text("Кожна дата отримає окрему задачу. Дату редактора замінить дата застосування; повторення для цих окремих виконань не потрібне.", style = MaterialTheme.typography.bodySmall)
        tasks.forEachIndexed { index, entry ->
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { editingIndex = index; editor = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text((entry["task"] as? JsonObject)?.text("title").orEmpty()) }
                TextButton(onClick = { tasksText = JsonArray(tasks.filterIndexed { i, _ -> i != index }).toString() }, enabled = !busy) { Text("Прибрати") }
            }
        }
        OutlinedButton(onClick = { editingIndex = null; editor = true }, enabled = !busy) { Text("+ Типова задача") }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun NativeCycleEditor(templates: List<JsonObject>, source: JsonObject?, busy: Boolean, onSave: (JsonObject) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    var name by rememberSaveable { mutableStateOf(source?.text("name").orEmpty()) }
    var daysText by rememberSaveable { mutableStateOf(JsonArray(source?.objects("days") ?: List(7) { buildJsonObject { put("tplId", ""); put("rest", false) } }).toString()) }
    var error by rememberSaveable { mutableStateOf("") }
    val days = (Json.parseToJsonElement(daysText) as JsonArray).filterIsInstance<JsonObject>()
    fun change(index: Int, value: JsonObject) { daysText = JsonArray(days.mapIndexed { i, entry -> if (i == index) value else entry }).toString() }
    NativeForm("Тиждень або цикл", busy, onClose, {
        if (name.isBlank()) error = "Напиши назву."
        else if (days.isEmpty()) error = "Додай хоча б один день."
        else onSave(buildJsonObject { put("name", name); put("days", JsonArray(days)) })
    }, modifier) {
        OutlinedTextField(name, { name = it }, label = { Text("Назва графіка") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Text(if (days.size == 7) "Розклад за днями тижня" else "Цикл із ${days.size} днів від дати застосування")
        days.forEachIndexed { index, entry ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(if (days.size == 7) listOf("Понеділок", "Вівторок", "Середа", "Четвер", "П’ятниця", "Субота", "Неділя")[index] else "День ${index + 1}")
                NativeChoice("Пресет дня", entry.text("tplId"), listOf("" to "Без пресету") + templates.map { it.text("id") to it.text("name") }, !busy) { id -> change(index, JsonObject(entry + ("tplId" to JsonPrimitive(id)))) }
                Row { Checkbox(entry.flag("rest"), { value -> change(index, JsonObject(entry + ("rest" to JsonPrimitive(value)))) }, enabled = !busy); Text("Вихідний") }
                TextButton(onClick = { daysText = JsonArray(days.filterIndexed { i, _ -> i != index }).toString() }, enabled = !busy && days.size > 1) { Text("Прибрати день") }
            } }
        }
        OutlinedButton(onClick = { daysText = JsonArray(days + buildJsonObject { put("tplId", ""); put("rest", false) }).toString() }, enabled = !busy && days.size < 366) { Text("+ День циклу") }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
