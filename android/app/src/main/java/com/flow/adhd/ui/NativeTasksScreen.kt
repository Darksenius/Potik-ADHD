package com.flow.adhd.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.FlowTask
import com.flow.adhd.domain.ProjectionContext
import com.flow.adhd.domain.QuestDeferralKind
import com.flow.adhd.domain.QuestPolicy
import com.flow.adhd.domain.TaskSchedule
import com.flow.adhd.domain.TaskOrder
import com.flow.adhd.domain.ZoneCalendar
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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
    initialFilter: String = "all",
    header: @Composable () -> Unit = {},
    onOpenPlan: (() -> Unit)? = null,
) {
    var filter by rememberSaveable(initialFilter) { mutableStateOf(initialFilter) }
    var filterMenu by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var requestSearchFocus by remember { mutableStateOf(searchOpen) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        requestSearchFocus = false
        searchOpen = false
        search = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val compactDayCard = LocalConfiguration.current.screenHeightDp < 600
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
    val orderedTasks = remember(snapshot, questContext) {
        val byId = tasks.associateBy { it.id }
        TaskOrder.rank(tasks.map { JsonObject(mapOf("type" to JsonPrimitive(it.type)) + it.raw) }, questContext)
            .mapNotNull { ranked -> ranked.numberId("id")?.let(byId::get) }
    }
    val candidates = when (filter) {
        "all" -> orderedTasks.filterNot { it.trashed }
        "someday" -> orderedTasks.filter { it.someday && !it.trashed }
        "journal" -> orderedTasks.filter { it.type == "negative" && !it.trashed && !it.someday }
        "daily" -> orderedTasks.filter { !it.trashed && !it.someday && (it.type == "counter" || it.type == "negative" || it.raw.booleanValue("counterEnabled") == true || it.raw.stringValue("repeat")?.let { value -> value != "none" } == true) }
        "trash" -> orderedTasks.filter { it.trashed }
        else -> orderedTasks.filter { QuestPolicy.availability(it.raw, questContext).eligible }
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
    val nearest = if (filter == "all") visible.filter {
        if (it.trashed || it.someday || it.type == "negative") false
        else if (it.type == "simple" && (it.raw.numberValue("quickReminderAt") ?: 0.0) > questContext.now) false
        else QuestPolicy.availability(it.raw, questContext).reason !in setOf("done", "missing-zone", "unassigned-zone", "journal")
    }.take(5) else emptyList()
    val nearestIds = nearest.mapTo(mutableSetOf()) { it.id }
    val displayTasks = if (filter == "all") nearest + visible.filterNot { it.id in nearestIds } else visible
    val zoneNames = snapshot.zones.associate { it.numberId("id") to it.stringValue("nm").orEmpty() }
    val visibleIds = displayTasks.map { it.id }
    val scheduleContext = ProjectionContext(questContext.now, questContext.timeline, questContext.zone)
    val showGamification = (snapshot.data["preferences"] as? JsonObject)?.booleanValue("showGamification") != false
    // No app-owned animation runs here; expansion and saved-state changes also respect reduceMotion.
    val deleteTask = deleteId?.let { id -> tasks.firstOrNull { it.id == id && it.trashed } }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("native-tasks"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (initialFilter == "daily") item { header() }
        if (filter == "all" || filter == "now") item {
            NativeDayZoneCard(snapshot, questContext, onOpenPlan, compact = compactDayCard)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (filter == "all") "Задачі" else taskFilterLabel(filter),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (filter != "trash") {
                        Button(onClick = onCreate, enabled = !busy, shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "Нова задача" }.testTag("native-task-create")) {
                            Text("+", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    if (filter != "now") IconButton(onClick = {
                        if (searchOpen) closeSearch()
                        else { searchOpen = true; requestSearchFocus = true }
                    },
                        modifier = Modifier.size(48.dp).testTag("native-task-search-toggle").semantics {
                            contentDescription = if (searchOpen) "Закрити пошук" else "Пошук задач"
                        }) {
                        val ink = LocalContentColor.current
                        Canvas(Modifier.size(22.dp)) {
                            val stroke = 2.dp.toPx()
                            if (searchOpen) {
                                drawLine(ink, Offset(size.width * .2f, size.height * .2f), Offset(size.width * .8f, size.height * .8f), stroke)
                                drawLine(ink, Offset(size.width * .8f, size.height * .2f), Offset(size.width * .2f, size.height * .8f), stroke)
                            } else {
                                drawCircle(ink, size.width * .3f, Offset(size.width * .4f, size.height * .4f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                                drawLine(ink, Offset(size.width * .63f, size.height * .63f), Offset(size.width * .92f, size.height * .92f), stroke)
                            }
                        }
                    }
                    Box {
                        OutlinedButton(
                            onClick = { filterMenu = true },
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(12.dp),
                            modifier = Modifier.size(48.dp).semantics {
                                contentDescription = "Фільтр задач"
                                stateDescription = if (filter == "all") "Показано всі задачі" else "Активний фільтр: ${taskFilterLabel(filter)}"
                            }.testTag("native-task-filter"),
                        ) {
                            val ink = LocalContentColor.current
                            val activeInk = MaterialTheme.colorScheme.primary
                            Canvas(Modifier.size(22.dp)) {
                                val stroke = 2.dp.toPx()
                                for ((index, fraction) in listOf(1f, .7f, .4f).withIndex()) {
                                    val y = size.height * (.25f + index * .25f)
                                    drawLine(ink, Offset(size.width * (1f - fraction) / 2f, y), Offset(size.width * (1f + fraction) / 2f, y), stroke)
                                }
                                if (filter != "all") drawCircle(
                                    color = activeInk,
                                    radius = 3.dp.toPx(),
                                    center = Offset(size.width * .86f, size.height * .16f),
                                )
                            }
                        }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            for (key in listOf("now", "all", "daily", "someday", "journal", "trash")) {
                                DropdownMenuItem(
                                    text = { Text(taskFilterLabel(key)) },
                                    onClick = { closeSearch(); filter = key; filterMenu = false },
                                    modifier = Modifier.testTag("native-task-filter-$key"),
                                )
                            }
                        }
                    }
                }
                if (filter != "now" && filter != "all") Text(
                    when (filter) {
                        "someday" -> "Поверни задачу, коли знову захочеш до неї взятися."
                        "journal" -> "Фіксація події збільшує журнал. Завершення задачі тут немає."
                        "daily" -> "Лічильники та повторювані справи. Нове входження з’являється за розкладом."
                        else -> "Віднови потрібну задачу або видали її назавжди."
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
                if (filter != "now" && searchOpen) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Пошук") },
                        placeholder = { Text("Назва, нотатка чи мітка") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).focusRequester(searchFocus).testTag("native-task-search"),
                    )
                    // This item is lazy: request focus only after its field is actually attached.
                    LaunchedEffect(requestSearchFocus) {
                        if (requestSearchFocus) {
                            searchFocus.requestFocus()
                            keyboard?.show()
                            requestSearchFocus = false
                        }
                    }
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
                            filter == "now" -> "Зараз немає доречних справ. Створи задачу — майбутні й відкладені залишаються у списку та в Плані."
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
        itemsIndexed(displayTasks, key = { _, task -> task.id }) { index, task ->
            if (filter == "all" && nearest.isNotEmpty() && index == 0) {
                Text("Найближчі", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (filter == "all" && index == nearest.size && displayTasks.size > nearest.size) {
                if (nearest.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text("Решта задач", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.weight(1f))
                    }
                } else {
                    Text("Решта задач", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
                canDefer = QuestPolicy.availability(task.raw, questContext).eligible,
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
    var menuSection by remember(task.id) { mutableStateOf<String?>(null) }
    var deferOpen by rememberSaveable(task.id) { mutableStateOf(false) }
    var deferMinutes by rememberSaveable(task.id) { mutableStateOf("60") }
    var deferError by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    var noteExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
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
    val currentContextTags = (task.raw["ctxTags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    val hasExpandableDetails = !task.trashed && (task.type == "check" || task.type == "ctx")
    fun send(factory: (String, Long) -> FlowCommand) {
        val now = System.currentTimeMillis()
        onCommand(factory(UUID.randomUUID().toString(), now)) {}
    }
    fun sendTimedDeferral(minutes: Long) {
        val now = System.currentTimeMillis()
        val details = buildJsonObject {
            put("kind", "time")
            put("requestedAt", now)
            put("sourceZoneId", availability.currentZoneId)
            put("targetZoneId", task.zoneId ?: 0L)
            put("startAt", now + minutes * 60_000L)
        }
        onCommand(FlowCommand.DeferTask(task.id, QuestDeferralKind.NEXT_ZONE, now, details, UUID.randomUUID().toString(), now)) {}
    }

    val date = TaskSchedule.planDate(task.raw)?.let { raw ->
        runCatching { java.time.LocalDate.parse(raw) }.getOrNull()?.let { date ->
            when (date) {
                java.time.LocalDate.now() -> "Сьогодні"
                java.time.LocalDate.now().plusDays(1) -> "Завтра"
                else -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("uk")))
            }
        } ?: raw
    }
    val time = when (task.type) {
        "sched" -> task.raw.stringValue("schedTime")
        "alarm" -> task.raw.stringValue("alarmTime")
        "timewin" -> listOfNotNull(task.raw.stringValue("windowStart"), task.raw.stringValue("windowEnd")).joinToString("–").takeIf { it.isNotBlank() }
        else -> null
    }
    val quickReminderAt = if (task.type == "simple") task.raw.numberValue("quickReminderAt")?.toLong() else null
    val quickReminderFiredAt = task.raw.numberValue("quickReminderFiredAt")?.toLong()
    val quickReminderLabel = when {
        quickReminderAt == null || quickReminderAt <= 0L -> null
        quickReminderFiredAt == quickReminderAt -> "Нагадування надіслано"
        else -> runCatching {
            val due = java.time.Instant.ofEpochMilli(quickReminderAt).atZone(ZoneId.systemDefault())
            val time = due.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val dateLabel = if (due.toLocalDate() == java.time.LocalDate.now(ZoneId.systemDefault())) "" else
                due.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("uk"))) + " "
            "Нагадування о $dateLabel$time"
        }.getOrNull()
    }
    val visibleDate = date.takeUnless { quickReminderAt != null && quickReminderFiredAt != quickReminderAt }
    val metadata = listOfNotNull(zoneName, visibleDate, time, quickReminderLabel, if (task.someday && !task.trashed) "Колись" else null)

    Card(
        modifier = Modifier.fillMaxWidth().testTag("native-task-${task.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (priorityIndex >= 0) MaterialTheme.colorScheme.primary.copy(alpha = .55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!task.trashed && !task.someday && !isJournal) {
                    TaskCompletionTarget(
                        task = task,
                        enabled = !busy,
                        onClick = {
                            send { id, now -> FlowCommand.SetTaskDone(task.id, !task.done, if (task.done) null else now, id, now) }
                        },
                    )
                }
                Column(Modifier.weight(1f).padding(start = if (!task.trashed && !task.someday && !isJournal) 4.dp else 0.dp)
                    .clickable(enabled = !busy && !task.trashed, role = Role.Button, onClick = onEdit)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (task.done && !isJournal) TextDecoration.LineThrough else TextDecoration.None,
                    )
                    val rowMetadata = buildList {
                        if (task.type !in setOf("simple", "counter") && typeLabel.isNotBlank()) add(typeLabel)
                        addAll(metadata)
                        if (priorityIndex >= 0 && !task.trashed) add("Пріоритет ${priorityIndex + 1}")
                    }
                    if (rowMetadata.isNotEmpty()) Text(
                        rowMetadata.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (!task.trashed) {
                    Box {
                        IconButton(
                            onClick = { menuSection = null; menuOpen = true }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "Дії: ${task.title}" }.testTag("native-task-actions-${task.id}"),
                        ) { Text("⋯", style = MaterialTheme.typography.headlineSmall) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (menuSection != null) DropdownMenuItem(text = { Text("‹  Назад") },
                                onClick = { menuSection = if (menuSection in setOf("priority", "reorder")) "more" else null })
                            when (menuSection) {
                                "priority" -> {
                                    if (canPrioritize) for (slot in 0..2) DropdownMenuItem(
                                        text = { Text("Пріоритет ${slot + 1}" + if (priorityIndex == slot) " · обрано" else "") },
                                        enabled = !busy && priorityIndex != slot,
                                        onClick = { menuOpen = false; send { id, now -> FlowCommand.SetPriority(slot, task.id, id, now) } },
                                        modifier = Modifier.testTag("native-task-priority-${task.id}-$slot"))
                                    if (priorityIndex in 0..2) DropdownMenuItem(text = { Text("Прибрати пріоритет") }, enabled = !busy,
                                        onClick = { menuOpen = false; send { id, now -> FlowCommand.SetPriority(priorityIndex, null, id, now) } },
                                        modifier = Modifier.testTag("native-task-priority-clear-${task.id}"))
                                }
                                "reorder" -> {
                                    val position = visibleIds.indexOf(task.id)
                                    DropdownMenuItem(text = { Text("Вище у списку") }, enabled = !busy && position > 0,
                                        onClick = { menuOpen = false; send { id, now -> FlowCommand.MoveTask(task.id, -1, visibleIds, id, now) } },
                                        modifier = Modifier.testTag("native-task-up-${task.id}"))
                                    DropdownMenuItem(text = { Text("Нижче у списку") }, enabled = !busy && position >= 0 && position < visibleIds.lastIndex,
                                        onClick = { menuOpen = false; send { id, now -> FlowCommand.MoveTask(task.id, 1, visibleIds, id, now) } },
                                        modifier = Modifier.testTag("native-task-down-${task.id}"))
                                }
                                "more" -> {
                                    if (canPrioritize || priorityIndex in 0..2) DropdownMenuItem(text = { Text("Пріоритет  ›") }, enabled = !busy,
                                        onClick = { menuSection = "priority" }, modifier = Modifier.testTag("native-task-priority-menu-${task.id}"))
                                    if (allowMove && visibleIds.size > 1) DropdownMenuItem(text = { Text("Порядок  ›") }, enabled = !busy,
                                        onClick = { menuSection = "reorder" }, modifier = Modifier.testTag("native-task-reorder-menu-${task.id}"))
                                    DropdownMenuItem(text = { Text("Створити копію") }, enabled = !busy,
                                        onClick = { menuOpen = false; send { id, now -> FlowCommand.DuplicateTask(task.id, id, now) } })
                                    DropdownMenuItem(text = { Text(if (task.someday) "Повернути з «Колись»" else "Відкласти на колись") }, enabled = !busy,
                                        onClick = { menuOpen = false; send { id, now -> if (task.someday) FlowCommand.RestoreTaskFromSomeday(task.id, id, now) else FlowCommand.MoveTaskToSomeday(task.id, id, now) } })
                                    DropdownMenuItem(text = { Text("У кошик", color = MaterialTheme.colorScheme.error) }, enabled = !busy,
                                        onClick = { menuOpen = false; send { id, now -> FlowCommand.TrashTask(task.id, id, now) } },
                                        modifier = Modifier.testTag("native-task-trash-${task.id}"))
                                }
                                else -> {
                                    DropdownMenuItem(text = { Text("Редагувати") }, enabled = !busy,
                                        onClick = { menuOpen = false; onEdit() }, modifier = Modifier.testTag("native-task-edit-${task.id}"))
                                    if (!task.note.isNullOrBlank()) DropdownMenuItem(text = { Text(if (noteExpanded) "Сховати нотатку" else "Переглянути нотатку") },
                                        onClick = { menuOpen = false; noteExpanded = !noteExpanded }, modifier = Modifier.testTag("native-task-note-toggle-${task.id}"))
                                    if (canDefer && !task.done && !task.someday && !isJournal) {
                                        DropdownMenuItem(text = { Text(if (task.type == "sched") "На 1 годину" else "Не зараз") }, enabled = !busy,
                                            onClick = { menuOpen = false; if (task.type == "sched") sendTimedDeferral(60L) else send { id, now -> FlowCommand.DeferTask(task.id, QuestDeferralKind.NEXT_ZONE, now, null, id, now) } },
                                            modifier = Modifier.testTag("native-task-defer-${task.id}"))
                                        DropdownMenuItem(text = { Text("Не сьогодні") }, enabled = !busy,
                                            onClick = { menuOpen = false; send { id, now -> FlowCommand.DeferTask(task.id, QuestDeferralKind.NOT_TODAY, now, null, id, now) } },
                                            modifier = Modifier.testTag("native-task-tomorrow-${task.id}"))
                                        if (task.type == "sched") DropdownMenuItem(text = { Text("Інший час…") }, enabled = !busy,
                                            onClick = { menuOpen = false; deferMinutes = "60"; deferError = null; deferOpen = true },
                                            modifier = Modifier.testTag("native-task-defer-custom-${task.id}"))
                                    }
                                    DropdownMenuItem(text = { Text("Інші дії  ›") }, onClick = { menuSection = "more" }, modifier = Modifier.testTag("native-task-more-${task.id}"))
                                }
                            }
                        }
                    }
                }
            }
            if (!task.trashed && !task.someday && !isJournal && availability.reason !in setOf("wrong-zone", "future-date", "not-due", "outside-time-window", "done", "timewin-completed")) {
                taskAvailabilityLabel(availability.reason)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (hasExpandableDetails) {
                TextButton(
                    onClick = { detailsExpanded = !detailsExpanded },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (detailsExpanded) "Розгорнуто" else "Згорнуто" }
                        .testTag("native-task-details-toggle-${task.id}"),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    val label = when {
                        task.type == "check" -> "Пункти · ${checklist.count { it.second.booleanValue("done") == true }}/${checklist.size}"
                        currentContextTags.isEmpty() -> "Контекст"
                        else -> "Контекст · ${currentContextTags.joinToString(", ")}"
                    }
                    Text((if (detailsExpanded) "⌃  " else "⌄  ") + label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (detailsExpanded && task.type == "check") {
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
                        Text("Пункти зберігають поступ. Завершення всієї справи — окрема дія.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (detailsExpanded && task.type == "ctx") {
                    for (row in (listOf("Вдома", "Телефон", "Робота", "Тихо", "З дитиною") + currentContextTags).distinct().chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { tag -> androidx.compose.material3.FilterChip(tag in currentContextTags, onClick = {
                            send { id, now -> FlowCommand.SetContextTags(task.id, if (tag in currentContextTags) currentContextTags - tag else currentContextTags + tag, id, now) }
                        }, label = { Text(tag) }, enabled = !busy) }
                    }
                }
                if (detailsExpanded && task.type == "check") {
                    OutlinedButton(onClick = { itemEditor = -1; itemText = "" }, enabled = !busy) { Text("+ Пункт") }
                }
            }
            if (hasCounter && !task.trashed) {
                val currentText = taskNumber(counter)
                val targetText = taskNumber(target)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    CounterStepButton("−", progressEnabled && counter > 0, "Зменшити: ${task.title}",
                        "native-task-counter-minus-${task.id}") { send { id, now -> FlowCommand.ChangeCounter(task.id, -step, id, now) } }
                    Column(Modifier.weight(1f, fill = false).widthIn(min = 72.dp).padding(horizontal = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (target > 0) "$currentText / $targetText" else currentText,
                            style = MaterialTheme.typography.bodyLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CounterStepButton("+", progressEnabled, "Збільшити: ${task.title}",
                        "native-task-counter-plus-${task.id}") { send { id, now -> FlowCommand.ChangeCounter(task.id, step, id, now) } }
                }
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
            val note = task.note?.takeIf { it.isNotBlank() }
            if (note != null && noteExpanded) Text(note, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("native-task-note-${task.id}"))
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
    if (deferOpen) AlertDialog(
        onDismissRequest = { if (!busy) { deferOpen = false; deferError = null } },
        title = { Text("Відкласти задачу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("На скільки хвилин відкласти нагадування?")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60).forEach { preset ->
                        OutlinedButton(
                            onClick = { deferMinutes = preset.toString(); deferError = null },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                        ) { Text("$preset") }
                    }
                }
                OutlinedTextField(
                    value = deferMinutes,
                    onValueChange = { deferMinutes = it.filter(Char::isDigit); deferError = null },
                    label = { Text("Хвилини") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("native-task-defer-minutes-${task.id}"),
                )
                if (deferError != null) Text(deferError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Від 1 до 10080 хвилин", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val minutes = deferMinutes.toLongOrNull()
                if (minutes == null || minutes !in 1L..10_080L) {
                    deferError = "Укажи ціле число хвилин від 1 до 10080."
                } else {
                    sendTimedDeferral(minutes)
                    deferOpen = false
                    deferError = null
                }
            }, modifier = Modifier.testTag("native-task-defer-confirm-${task.id}")) { Text("Відкласти") }
        },
        dismissButton = { TextButton(onClick = { deferOpen = false; deferError = null }, enabled = !busy) { Text("Скасувати") } },
    )
}

@Composable
private fun TaskCompletionTarget(task: FlowTask, enabled: Boolean, onClick: () -> Unit) {
    PotikCompletionTarget(task.done, enabled,
        if (task.done) "Повернути у невиконані: ${task.title}" else "Виконати: ${task.title}",
        Modifier.testTag("native-task-complete-${task.id}")) { onClick() }
}

@Composable
private fun CounterStepButton(
    symbol: String,
    enabled: Boolean,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { this.contentDescription = contentDescription }.testTag(testTag),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .35f))
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
    else -> "Доречні зараз"
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
