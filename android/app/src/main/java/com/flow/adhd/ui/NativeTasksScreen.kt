package com.flow.adhd.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.FlowTask
import com.flow.adhd.domain.ProjectionContext
import com.flow.adhd.domain.QuestDeferralKind
import com.flow.adhd.domain.QuestPolicy
import com.flow.adhd.domain.TaskSchedule
import com.flow.adhd.domain.ZoneCalendar
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Native task presentation. State changes arrive only through the durable command owner.
 * The owner refreshes [projection] on its clock tick, so quest eligibility uses that same tick.
 */
@Composable
fun NativeTasksScreen(
    snapshot: FlowSnapshot,
    projection: JsonObject,
    busy: Boolean,
    onCommand: (FlowCommand, () -> Unit) -> Unit,
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    initialFilter: String = "now",
    header: @Composable () -> Unit = {},
) {
    var filter by rememberSaveable(initialFilter) { mutableStateOf(initialFilter) }
    var filterMenu by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val typeLabels = remember(context) { taskTypeLabels(context) }
    val tasks = snapshot.tasks
    val questContext = remember(snapshot, projection) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val priorities = (snapshot.data["priorities"] as? JsonArray).orEmpty().map {
            FlowSnapshot.safeId(it, "priorities")
        }
        QuestPolicy.Context(now, ZoneCalendar.timeline(snapshot.data, now, zone), snapshot.zones, priorities, zone)
    }
    val questTasks = remember(snapshot, questContext) {
        // Match NotificationProjection's default type handling, then use the shared domain policy.
        QuestPolicy.select(tasks.map { JsonObject(mapOf("type" to JsonPrimitive(it.type)) + it.raw) }, questContext, 5)
    }
    val questIds = questTasks.mapNotNull { it.numberId("id") }.toSet()
    val currentTasks = questTasks.mapNotNull { selected -> tasks.firstOrNull { it.id == selected.numberId("id") } }
    val candidates = when (filter) {
        "all" -> tasks.filterNot { it.trashed }
        "someday" -> tasks.filter { it.someday && !it.trashed }
        "journal" -> tasks.filter { it.type == "negative" && !it.trashed && !it.someday }
        "daily" -> tasks.filter { !it.trashed && !it.someday && (it.type == "counter" || it.type == "negative" || it.raw.booleanValue("counterEnabled") == true || it.raw.stringValue("repeat")?.let { value -> value != "none" } == true) }
        "trash" -> tasks.filter { it.trashed }
        else -> currentTasks
    }
    val query = search.trim().lowercase(Locale.forLanguageTag("uk"))
    val visible = if (filter == "now" || query.isEmpty()) candidates else candidates.filter { task ->
        buildList {
            add(task.title)
            add(task.note.orEmpty())
            addAll((task.raw["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content })
            addAll((task.raw["ctxTags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content })
        }.joinToString(" ").lowercase(Locale.forLanguageTag("uk")).contains(query)
    }
    val zoneNames = snapshot.zones.associate { it.numberId("id") to it.stringValue("nm").orEmpty() }
    val visibleIds = visible.map { it.id }
    val scheduleContext = ProjectionContext(questContext.now, questContext.timeline, questContext.zone)
    val showGamification = (snapshot.data["preferences"] as? JsonObject)?.booleanValue("showGamification") != false
    // No app-owned animation runs here; expansion and saved-state changes also respect reduceMotion.
    val deleteTask = deleteId?.let { id -> tasks.firstOrNull { it.id == id && it.trashed } }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("native-tasks"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { header() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showGamification) {
                    Text(
                        "Рів ${snapshot.level} · ${taskNumber(snapshot.xpTotal)} од",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.testTag("native-task-progress"),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        taskFilterLabel(filter),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        OutlinedButton(
                            onClick = { filterMenu = true },
                            modifier = Modifier.testTag("native-task-filter"),
                        ) { Text("Показати") }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            for (key in listOf("now", "all", "daily", "someday", "journal", "trash")) {
                                DropdownMenuItem(
                                    text = { Text(taskFilterLabel(key)) },
                                    onClick = { filter = key; search = ""; filterMenu = false },
                                    modifier = Modifier.testTag("native-task-filter-$key"),
                                )
                            }
                        }
                    }
                }
                Text(
                    when (filter) {
                        "now" -> "До п’яти доречних справ · ${projection.stringValue("zone") ?: "Вільний час"}"
                        "all" -> "Усі задачі, включно з майбутніми, відкладеними й виконаними."
                        "someday" -> "Поверни задачу, коли знову захочеш до неї взятися."
                        "journal" -> "Фіксація події збільшує журнал. Завершення задачі тут немає."
                        "daily" -> "Лічильники та повторювані справи. Нове входження з’являється за розкладом."
                        else -> "Віднови потрібну задачу або видали її назавжди."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (filter != "trash") {
                    Button(
                        onClick = onCreate,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("native-task-create"),
                    ) { Text("+ Нова задача") }
                }
                if (filter != "now") {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Знайти задачу") },
                        placeholder = { Text("Назва, нотатка чи мітка") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("native-task-search"),
                    )
                }
                if (busy) {
                    Text(
                        "Зберігаю…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().testTag("native-tasks-empty")) {
                    Text(
                        when {
                            query.isNotEmpty() && filter != "now" -> "За цими умовами задач немає."
                            filter == "now" -> "Зараз немає доречних справ. Решта задач доступна через «Показати» та в Плані."
                            filter == "trash" -> "Кошик порожній."
                            filter == "someday" -> "Немає задач, відкладених на колись."
                            filter == "journal" -> "Ще немає журналів подій."
                            else -> "Поки немає задач. Додай першу справу."
                        },
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(visible, key = { it.id }) { task ->
            val priorityIndex = questContext.priorities.indexOf(task.id)
            NativeTaskCard(
                task = task,
                typeLabel = typeLabels[task.type] ?: "Задача",
                zoneName = task.zoneId?.takeIf { it != 0L }?.let { zoneNames[it]?.takeIf(String::isNotBlank) ?: "Зона недоступна" },
                priorityIndex = priorityIndex,
                canPrioritize = TaskSchedule.canPrioritize(task.raw, scheduleContext),
                visibleIds = visibleIds,
                allowMove = filter != "now" && filter != "trash",
                availability = QuestPolicy.availability(task.raw, questContext),
                canDefer = questIds.contains(task.id) || QuestPolicy.availability(task.raw, questContext).eligible,
                busy = busy,
                showGamification = showGamification,
                onCommand = onCommand,
                onEdit = { onEdit(task.id) },
                onDelete = { deleteId = task.id },
            )
        }
        item { Spacer(Modifier.size(8.dp)) }
    }
    if (deleteTask != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) deleteId = null },
            title = { Text("Видалити назавжди?") },
            text = { Text("Задачу «${deleteTask.title}» буде видалено з кошика. Відновити її звідси вже не вдасться.") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val now = System.currentTimeMillis()
                        onCommand(FlowCommand.HardDeleteTask(deleteTask.id, UUID.randomUUID().toString(), now)) { deleteId = null }
                    },
                    modifier = Modifier.testTag("native-task-delete-confirm"),
                ) { Text("Видалити назавжди", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }, enabled = !busy) { Text("Залишити") }
            },
        )
    }
}

@Composable
private fun NativeTaskCard(
    task: FlowTask,
    typeLabel: String,
    zoneName: String?,
    priorityIndex: Int,
    canPrioritize: Boolean,
    visibleIds: List<Long>,
    allowMove: Boolean,
    availability: QuestPolicy.Availability,
    canDefer: Boolean,
    busy: Boolean,
    showGamification: Boolean,
    onCommand: (FlowCommand, () -> Unit) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(task.id) { mutableStateOf(false) }
    var noteExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var itemEditor by rememberSaveable(task.id) { mutableStateOf<Int?>(null) }
    var itemText by rememberSaveable(task.id) { mutableStateOf("") }
    val isJournal = task.type == "negative"
    val progressEnabled = !busy && !task.done && !task.trashed && !task.someday
    val counter = task.raw.numberValue("counter") ?: 0.0
    val step = task.raw.numberValue("counterStep")?.takeIf { it > 0 } ?: 1.0
    val unit = when (task.raw.stringValue("counterUnit")) {
        "ml" -> "мл"
        "min" -> "хв"
        "kcal" -> "ккал"
        else -> "разів"
    }
    val hasCounter = !isJournal && (task.type == "counter" || task.raw.booleanValue("counterEnabled") == true)
    val target = (task.raw.numberValue("counterTarget") ?: if (task.type == "counter") 10.0 else 0.0).coerceAtLeast(0.0)
    val checklist = (task.raw["items"] as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
        (value as? JsonObject)?.let { index to it }
    }
    fun send(factory: (String, Long) -> FlowCommand) {
        val now = System.currentTimeMillis()
        onCommand(factory(UUID.randomUUID().toString(), now)) {}
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("native-task-${task.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (!task.trashed && !task.someday && !isJournal) {
                    TaskCompletionTarget(
                        task = task,
                        enabled = !busy,
                        onClick = {
                            send { id, now -> FlowCommand.SetTaskDone(task.id, !task.done, if (task.done) null else now, id, now) }
                        },
                    )
                }
                Column(Modifier.weight(1f).padding(start = if (!task.trashed && !task.someday && !isJournal) 8.dp else 0.dp)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (task.done && !isJournal) TextDecoration.LineThrough else TextDecoration.None,
                    )
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(modifier = Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Text(typeLabel, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        if (priorityIndex >= 0 && !task.trashed) {
                            Text("Пріоритет ${priorityIndex + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (!task.trashed) {
                    Box {
                        TextButton(
                            onClick = { menuOpen = true },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.testTag("native-task-actions-${task.id}"),
                        ) { Text("Дії") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Редагувати") },
                                onClick = { menuOpen = false; onEdit() },
                                enabled = !busy,
                                modifier = Modifier.testTag("native-task-edit-${task.id}"),
                            )
                            DropdownMenuItem(text = { Text("Створити копію") }, enabled = !busy,
                                onClick = { menuOpen = false; send { id, now -> FlowCommand.DuplicateTask(task.id, id, now) } })
                            if (canPrioritize) {
                                for (slot in 0..2) {
                                    DropdownMenuItem(
                                        text = { Text("Пріоритет ${slot + 1}" + if (priorityIndex == slot) " · обрано" else "") },
                                        enabled = !busy && priorityIndex != slot,
                                        onClick = {
                                            menuOpen = false
                                            send { id, now -> FlowCommand.SetPriority(slot, task.id, id, now) }
                                        },
                                        modifier = Modifier.testTag("native-task-priority-${task.id}-$slot"),
                                    )
                                }
                            }
                            if (priorityIndex in 0..2) {
                                DropdownMenuItem(
                                    text = { Text("Прибрати пріоритет") },
                                    enabled = !busy,
                                    onClick = {
                                        menuOpen = false
                                        send { id, now -> FlowCommand.SetPriority(priorityIndex, null, id, now) }
                                    },
                                    modifier = Modifier.testTag("native-task-priority-clear-${task.id}"),
                                )
                            }
                            if (allowMove && visibleIds.size > 1) {
                                val position = visibleIds.indexOf(task.id)
                                DropdownMenuItem(
                                    text = { Text("Вище у списку") },
                                    enabled = !busy && position > 0,
                                    onClick = {
                                        menuOpen = false
                                        send { id, now -> FlowCommand.MoveTask(task.id, -1, visibleIds, id, now) }
                                    },
                                    modifier = Modifier.testTag("native-task-up-${task.id}"),
                                )
                                DropdownMenuItem(
                                    text = { Text("Нижче у списку") },
                                    enabled = !busy && position >= 0 && position < visibleIds.lastIndex,
                                    onClick = {
                                        menuOpen = false
                                        send { id, now -> FlowCommand.MoveTask(task.id, 1, visibleIds, id, now) }
                                    },
                                    modifier = Modifier.testTag("native-task-down-${task.id}"),
                                )
                            }
                            if (canDefer && !isJournal && !task.done && !task.someday) {
                                DropdownMenuItem(
                                    text = { Text("Не зараз") },
                                    enabled = !busy,
                                    onClick = {
                                        menuOpen = false
                                        send { id, now -> FlowCommand.DeferTask(task.id, QuestDeferralKind.NEXT_ZONE, now, null, id, now) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Не сьогодні") },
                                    enabled = !busy,
                                    onClick = {
                                        menuOpen = false
                                        send { id, now -> FlowCommand.DeferTask(task.id, QuestDeferralKind.NOT_TODAY, now, null, id, now) }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (task.someday) "Повернути з «Колись»" else "Відкласти на колись") },
                                enabled = !busy,
                                onClick = {
                                    menuOpen = false
                                    send { id, now ->
                                        if (task.someday) FlowCommand.RestoreTaskFromSomeday(task.id, id, now)
                                        else FlowCommand.MoveTaskToSomeday(task.id, id, now)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("У кошик") },
                                enabled = !busy,
                                onClick = { menuOpen = false; send { id, now -> FlowCommand.TrashTask(task.id, id, now) } },
                                modifier = Modifier.testTag("native-task-trash-${task.id}"),
                            )
                        }
                    }
                }
            }
            val date = TaskSchedule.planDate(task.raw)
            val time = when (task.type) {
                "sched" -> task.raw.stringValue("schedTime")
                "alarm" -> task.raw.stringValue("alarmTime")
                "timewin" -> listOfNotNull(task.raw.stringValue("windowStart"), task.raw.stringValue("windowEnd")).joinToString("–").takeIf { it.isNotBlank() }
                else -> null
            }
            val metadata = listOfNotNull(zoneName, date, time, if (task.someday && !task.trashed) "Колись" else null)
            if (metadata.isNotEmpty()) {
                Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!task.trashed && !task.someday && !isJournal) {
                taskAvailabilityLabel(availability.reason)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.type == "check" && checklist.isNotEmpty() && !task.trashed) {
                Column {
                    checklist.forEach { (index, item) ->
                        val checked = item.booleanValue("done") == true
                        val label = item.stringValue("text").orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .toggleable(value = checked, enabled = progressEnabled, role = Role.Checkbox) { value ->
                                    send { id, now -> FlowCommand.SetChecklistItem(task.id, index, value, id, now) }
                                }
                                .testTag("native-task-check-${task.id}-$index"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = progressEnabled, modifier = Modifier.size(48.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            TextButton(onClick = { itemEditor = index; itemText = label }, enabled = !busy) { Text("Змінити") }
                            TextButton(onClick = { send { id, now -> FlowCommand.DeleteChecklistItem(task.id, index, id, now) } }, enabled = !busy) { Text("×") }
                        }
                    }
                }
                Text("Пункти зберігають поступ. Завершення всієї справи — окрема дія.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (task.type == "check" && !task.trashed) OutlinedButton(onClick = { itemEditor = -1; itemText = "" }, enabled = !busy) { Text("+ Пункт") }
            if (task.type == "ctx" && !task.trashed) {
                val current = (task.raw["ctxTags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                for (row in (listOf("Вдома", "Телефон", "Робота", "Тихо", "З дитиною") + current).distinct().chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tag -> androidx.compose.material3.FilterChip(tag in current, onClick = {
                        send { id, now -> FlowCommand.SetContextTags(task.id, if (tag in current) current - tag else current + tag, id, now) }
                    }, label = { Text(tag) }, enabled = !busy) }
                }
            }
            if (hasCounter && !task.trashed) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = progressEnabled && counter > 0,
                        onClick = { send { id, now -> FlowCommand.ChangeCounter(task.id, -step, id, now) } },
                        modifier = Modifier.testTag("native-task-counter-minus-${task.id}").semantics { contentDescription = "Зменшити: ${task.title}" },
                    ) { Text("−${taskNumber(step)}") }
                    Text(
                        "${taskNumber(counter)} $unit",
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(
                        enabled = progressEnabled,
                        onClick = { send { id, now -> FlowCommand.ChangeCounter(task.id, step, id, now) } },
                        modifier = Modifier.testTag("native-task-counter-plus-${task.id}").semantics { contentDescription = "Збільшити: ${task.title}" },
                    ) { Text("+${taskNumber(step)}") }
                }
                if (target > 0) Text("Ціль: ${taskNumber(target)} $unit · завершення вручну", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isJournal && !task.trashed) {
                Text("Зафіксовано сьогодні: ${taskNumber(counter)} $unit", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { send { id, now -> FlowCommand.RecordNegative(task.id, 1.0, id, now) } },
                    enabled = progressEnabled,
                    modifier = Modifier.fillMaxWidth().testTag("native-task-negative-${task.id}"),
                ) { Text("Зафіксувати подію") }
                if (showGamification) {
                    val cost = (task.raw.numberValue("negXp") ?: 5.0).coerceAtLeast(0.0)
                    Text("−${taskNumber(cost)} од за подію", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            task.note?.takeIf { it.isNotBlank() }?.let { note ->
                TextButton(
                    onClick = { noteExpanded = !noteExpanded },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.testTag("native-task-note-toggle-${task.id}"),
                ) { Text(if (noteExpanded) "Сховати нотатку" else "Нотатка до задачі") }
                if (noteExpanded) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("native-task-note-${task.id}"))
                }
            }
            if (task.trashed) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { send { id, now -> FlowCommand.RestoreTrashedTask(task.id, id, now) } },
                        modifier = Modifier.testTag("native-task-restore-${task.id}"),
                    ) { Text("Відновити") }
                    TextButton(
                        enabled = !busy,
                        onClick = onDelete,
                        modifier = Modifier.testTag("native-task-delete-${task.id}"),
                    ) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (itemEditor != null) AlertDialog(onDismissRequest = { itemEditor = null }, title = { Text(if (itemEditor == -1) "Новий пункт" else "Змінити пункт") },
        text = { OutlinedTextField(itemText, { itemText = it }, label = { Text("Що зробити?") }, enabled = !busy) },
        confirmButton = { TextButton(onClick = {
            val index = itemEditor!!.takeIf { it >= 0 }
            onCommand(FlowCommand.EditChecklistItem(task.id, index, itemText, UUID.randomUUID().toString(), System.currentTimeMillis())) { itemEditor = null }
        }, enabled = !busy && itemText.isNotBlank()) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = { itemEditor = null }, enabled = !busy) { Text("Скасувати") } })
}

@Composable
private fun TaskCompletionTarget(task: FlowTask, enabled: Boolean, onClick: () -> Unit) {
    // The touch target and inner mark both have fixed square bounds before CircleShape.
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .toggleable(value = task.done, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics {
                contentDescription = if (task.done) "Повернути у невиконані: ${task.title}" else "Виконати: ${task.title}"
                stateDescription = if (task.done) "Виконано" else "Не виконано"
            }
            .testTag("native-task-complete-${task.id}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(28.dp).border(2.dp, if (task.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (task.done) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun taskTypeLabels(context: Context): Map<String, String> = runCatching {
    val source = com.flow.adhd.data.NativeContent.read(context, "task-types.json")
    val catalog = Json.parseToJsonElement(source) as JsonObject
    catalog.mapNotNull { (key, value) -> (value as? JsonObject)?.stringValue("label")?.let { key to it } }.toMap()
}.getOrDefault(emptyMap())

private fun taskFilterLabel(filter: String) = when (filter) {
    "daily" -> "Повсякденні задачі"
    "all" -> "Усі задачі"
    "someday" -> "Колись"
    "journal" -> "Журнал подій"
    "trash" -> "Кошик"
    else -> "Зараз"
}

private fun taskAvailabilityLabel(reason: String?) = when (reason) {
    "done", "timewin-completed" -> "Виконано"
    "future-date", "not-due" -> "Ще не настав час"
    "wrong-zone" -> "Буде доречно в іншій зоні"
    "outside-time-window" -> "Поза вікном часу"
    "missing-zone" -> "Обери доступну зону в редакторі"
    "unassigned-zone" -> "Додай зону в редакторі"
    "snoozed", "deferred-until" -> "Відкладено"
    else -> null
}

private fun taskNumber(value: Double): String = if (value % 1.0 == 0.0 && value in -9_007_199_254_740_991.0..9_007_199_254_740_991.0) value.toLong().toString() else value.toString()
private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.numberValue(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
private fun JsonObject.numberId(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
