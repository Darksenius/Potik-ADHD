package com.flow.adhd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

internal fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
internal fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
internal fun JsonObject.flag(key: String) = this[key] == JsonPrimitive(true)
internal fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
internal fun newCommandId() = java.util.UUID.randomUUID().toString()
internal fun commandTime() = System.currentTimeMillis()

@Composable internal fun NativeChoice(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                Text(choices.firstOrNull { it.first == value }?.second ?: "Вибери значення", Modifier.weight(1f)); Text("⌄")
            }
            DropdownMenu(open, { open = false }, Modifier.heightIn(max = 340.dp)) {
                choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onChange(id) }) }
            }
        }
    }
}

@Composable internal fun NativeForm(
    title: String, busy: Boolean, onClose: () -> Unit, onSave: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = onClose, enabled = !busy) { Text("Назад") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
        }
        Button(onClick = onSave, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 48.dp).testTag("native-form-save")) {
            Text(if (busy) "Зберігаю…" else "Зберегти")
        }
    }
}

/** Authored schedule blocks only; callers preserve unrelated metadata when replacing one row. */
@Composable internal fun NativeScheduleRows(zones: List<JsonObject>, rows: List<JsonObject>, busy: Boolean, onChange: (List<JsonObject>) -> Unit) {
    rows.forEachIndexed { index, row ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeChoice("Зона", row.number("zoneId")?.toLong()?.toString().orEmpty(), zones.map { it.number("id")!!.toLong().toString() to it.text("nm") }, !busy) { id ->
                    onChange(rows.mapIndexed { i, entry -> if (i == index) JsonObject(entry + ("zoneId" to JsonPrimitive(id.toLong()))) else entry })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((field, label) in listOf("s" to "З", "e" to "До")) OutlinedTextField(row.text(field), { value ->
                        onChange(rows.mapIndexed { i, entry -> if (i == index) JsonObject(entry + (field to JsonPrimitive(value))) else entry })
                    }, label = { Text(label) }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onChange(rows.filterIndexed { i, _ -> i != index }) }, enabled = !busy) { Text("Прибрати") }
                }
            }
        }
    }
    OutlinedButton(onClick = {
        zones.firstOrNull()?.get("id")?.let { id -> onChange(rows + buildJsonObject { put("zoneId", id); put("s", "09:00"); put("e", "10:00") }) }
    }, enabled = !busy && zones.isNotEmpty()) { Text("+ Часовий блок") }
    Text("Час: ГГ:ХХ. Для кінця дня — 24:00. Перехід через північ розділи на два блоки.", style = MaterialTheme.typography.bodySmall)
}
