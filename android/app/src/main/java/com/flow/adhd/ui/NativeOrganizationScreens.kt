package com.flow.adhd.ui

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
import kotlinx.serialization.json.*

@Composable fun NativeZonesScreen(snapshot: FlowSnapshot, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    fun send(command: FlowCommand, done: () -> Unit = {}) = onCommand(command, done)
    val editing = snapshot.zones.firstOrNull { it.number("id")?.toLong() == editId }
    if (editing != null) {
        NativeZoneEditor(editing, busy, onSave = { payload -> send(FlowCommand.UpdateZone(editId!!, payload, newCommandId(), commandTime())) { editId = null } }, onClose = { editId = null }, modifier)
        return
    }
    LazyColumn(modifier.fillMaxSize().imePadding().testTag("native-zones"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Зони", style = MaterialTheme.typography.headlineSmall); Text("Часова зона — напрямок справ, доречних у цей час. 5 — найвищий пріоритет.") }
        items(snapshot.zones, key = { it["id"].toString() }) { zone ->
            val id = zone.number("id")!!.toLong()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(zone.text("nm"), style = MaterialTheme.typography.titleMedium)
                    Text(zone.text("desc"))
                    Text("Пріоритет ${zone.number("prio")?.toInt() ?: 1} · " + if (zone.flag("off")) "вимкнено" else "увімкнено")
                    Text(zone.objects("slots").joinToString(" · ") { "${it.text("s")}–${it.text("e")}" }.ifEmpty { "Без постійних годин — можна додати у План" })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editId = id }, enabled = !busy, modifier = Modifier.testTag("native-zone-edit-$id")) { Text("Редагувати") }
                        TextButton(onClick = { send(FlowCommand.SetZoneOff(id, !zone.flag("off"), newCommandId(), commandTime())) }, enabled = !busy) { Text(if (zone.flag("off")) "Увімкнути" else "Вимкнути") }
                        TextButton(onClick = { deleteId = id }, enabled = !busy) { Text("Видалити") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(name, { name = it }, label = { Text("Нова зона") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Button(onClick = { send(FlowCommand.CreateZone(buildJsonObject { put("nm", name) }, newCommandId(), commandTime())) { name = "" } }, enabled = !busy && name.isNotBlank()) { Text("Додати зону") }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Видалити зону?") }, text = { Text("Задачі залишаться. Їхні прив’язки до цієї зони треба буде перевірити в редакторі.") },
        confirmButton = { TextButton(onClick = { send(FlowCommand.DeleteZone(deleteId!!, newCommandId(), commandTime())) { deleteId = null } }, enabled = !busy) { Text("Видалити") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Скасувати") } })
}

@Composable private fun NativeZoneEditor(zone: JsonObject, busy: Boolean, onSave: (JsonObject) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val id = zone["id"].toString()
    var name by rememberSaveable(id) { mutableStateOf(zone.text("nm")) }
    var description by rememberSaveable(id) { mutableStateOf(zone.text("desc")) }
    var color by rememberSaveable(id) { mutableStateOf(zone.text("color")) }
    var priority by rememberSaveable(id) { mutableStateOf((zone.number("prio")?.toInt() ?: 1).toString()) }
    var slotsJson by rememberSaveable(id) { mutableStateOf(JsonArray(zone.objects("slots")).toString()) }
    var error by rememberSaveable(id) { mutableStateOf("") }
    val slots = (Json.parseToJsonElement(slotsJson) as JsonArray).filterIsInstance<JsonObject>()
    NativeForm("Редагування зони", busy, onClose, {
        if (name.isBlank()) error = "Напиши назву зони."
        else if (!color.matches(Regex("#[a-fA-F0-9]{6}"))) error = "Колір: # і шість цифр, наприклад #ff6b35."
        else onSave(buildJsonObject { put("nm", name); put("desc", description); put("color", color); put("prio", priority.toInt()); put("slots", JsonArray(slots)) })
    }, modifier.testTag("native-zone-editor")) {
        OutlinedTextField(name, { name = it }, label = { Text("Назва") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(description, { description = it }, label = { Text("Що доречно робити в цій зоні?") }, modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !busy)
        NativeChoice("Пріоритет · 5 найвищий", priority, (1..5).map { it.toString() to it.toString() }, !busy) { priority = it }
        OutlinedTextField(color, { color = it }, label = { Text("Колір #RRGGBB") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
        Text("Постійні години", style = MaterialTheme.typography.titleMedium)
        slots.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((field, label) in listOf("s" to "З", "e" to "До")) OutlinedTextField(row.text(field), { value ->
                    slotsJson = JsonArray(slots.mapIndexed { i, item -> if (i == index) JsonObject(item + (field to JsonPrimitive(value))) else item }).toString()
                }, label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true, enabled = !busy)
                TextButton(onClick = { slotsJson = JsonArray(slots.filterIndexed { i, _ -> i != index }).toString() }, enabled = !busy) { Text("Прибрати") }
            }
        }
        OutlinedButton(onClick = { slotsJson = JsonArray(slots + buildJsonObject { put("s", "09:00"); put("e", "10:00") }).toString() }, enabled = !busy) { Text("+ Години") }
        Text("ГГ:ХХ; кінець дня — 24:00. Для нічної зони створи два проміжки.", style = MaterialTheme.typography.bodySmall)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable fun NativeFoldersScreen(snapshot: FlowSnapshot, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    var create by rememberSaveable { mutableStateOf("") }
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var editName by rememberSaveable { mutableStateOf("") }
    var delete by rememberSaveable { mutableStateOf<String?>(null) }
    val folders = snapshot.data.objects("folders")
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Папки нотаток", style = MaterialTheme.typography.headlineSmall) }
        items(folders, key = { it.text("id") }) { folder ->
            val id = folder.text("id")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(folder.text("nm"), style = MaterialTheme.typography.titleMedium)
                    Text("Нотаток: ${snapshot.qnotes.count { it.text("folder") == id }}")
                    if (edit == id) {
                        OutlinedTextField(editName, { editName = it }, label = { Text("Назва папки") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        TextButton(onClick = { onCommand(FlowCommand.UpdateNoteFolder(id, buildJsonObject { put("nm", editName) }, newCommandId(), commandTime())) { edit = null } }, enabled = !busy && editName.isNotBlank()) { Text("Зберегти") }
                    }
                    Row {
                        TextButton(onClick = { edit = id; editName = folder.text("nm") }, enabled = !busy) { Text("Перейменувати") }
                        TextButton(onClick = { delete = id }, enabled = !busy) { Text("Видалити") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(create, { create = it }, label = { Text("Нова папка") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Button(onClick = { onCommand(FlowCommand.CreateNoteFolder(buildJsonObject { put("nm", create) }, newCommandId(), commandTime())) { create = "" } }, enabled = !busy && create.isNotBlank()) { Text("Додати папку") }
        }
    }
    if (delete != null) AlertDialog(onDismissRequest = { delete = null }, title = { Text("Видалити папку?") }, text = { Text("Нотатки перейдуть у доступну папку. Самі записи не видаляються.") },
        confirmButton = { TextButton(onClick = { onCommand(FlowCommand.DeleteNoteFolder(delete!!, newCommandId(), commandTime())) { delete = null } }, enabled = !busy) { Text("Видалити") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Скасувати") } })
}
