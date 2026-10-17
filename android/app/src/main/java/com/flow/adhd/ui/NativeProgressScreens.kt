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
import com.flow.adhd.data.NativeContent
import com.flow.adhd.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

@Composable fun NativeStateControls(snapshot: FlowSnapshot, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit) {
    var custom by rememberSaveable { mutableStateOf("") }
    var allHistory by rememberSaveable { mutableStateOf(false) }
    val focus = snapshot.data.text("currentFocus")
    val chips = (snapshot.data["focusChips"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    val priorities = (snapshot.data["priorities"] as? JsonArray).orEmpty()
    val scheduleContext = remember(snapshot) {
        val now = commandTime()
        val zone = java.time.ZoneId.systemDefault()
        ProjectionContext(now, ZoneCalendar.timeline(snapshot.data, now, zone), zone)
    }
    fun send(command: FlowCommand, done: () -> Unit = {}) = onCommand(command, done)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Стан", style = MaterialTheme.typography.headlineSmall)
        Text("Енергія", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (value in 1..5) FilterChip(snapshot.data.number("energy")?.toInt() == value,
                onClick = { send(FlowCommand.SetEnergy(value, newCommandId(), commandTime())) }, enabled = !busy,
                label = { Text(value.toString()) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-energy-$value"))
        }
        Text("1 — майже немає сил; 5 — повний заряд.", style = MaterialTheme.typography.bodySmall)
        Text("Пріоритети дня", style = MaterialTheme.typography.titleMedium)
        repeat(3) { index ->
            NativeChoice("${index + 1}", (priorities.getOrNull(index) as? JsonPrimitive)?.longOrNull?.toString().orEmpty(),
                listOf("" to "Не обрано") + snapshot.tasks.filter { TaskSchedule.canPrioritize(it.raw, scheduleContext) }.map { it.id.toString() to it.title }, !busy) { id ->
                send(FlowCommand.SetPriority(index, id.toLongOrNull(), newCommandId(), commandTime()))
            }
        }
        Text("Чим зайнята увага", style = MaterialTheme.typography.titleMedium)
        Text(focus.ifBlank { "Фокус не обрано" })
        chips.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { chip ->
                Column(Modifier.weight(1f)) {
                    FilterChip(focus == chip, { send(FlowCommand.SetFocus(chip, newCommandId(), commandTime())) }, label = { Text(chip) }, enabled = !busy)
                    TextButton(onClick = { send(FlowCommand.RemoveFocusChip(chip, newCommandId(), commandTime())) }, enabled = !busy) { Text("Прибрати") }
                }
            }
        } }
        OutlinedTextField(custom, { custom = it }, label = { Text("Інший фокус") }, modifier = Modifier.fillMaxWidth().testTag("native-focus-input"), enabled = !busy)
        Row {
            TextButton(onClick = { send(FlowCommand.AddFocusChip(custom.trim(), newCommandId(), commandTime())) { custom = "" } }, enabled = !busy && custom.isNotBlank(), modifier = Modifier.testTag("native-focus-start")) { Text("Почати") }
            TextButton(onClick = { send(FlowCommand.ClearFocus(newCommandId(), commandTime())) }, enabled = !busy && focus.isNotEmpty()) { Text("Завершити фокус") }
        }
        val log = snapshot.data.objects("focusLog")
        for (entry in (if (allHistory) log else log.takeLast(5)).reversed()) {
            Text("${entry.text("date")} ${entry.text("time")} · ${entry.text("val")} · ${entry.number("dur")?.toLong()?.let { "$it хв" } ?: "триває"}", style = MaterialTheme.typography.bodySmall)
        }
        if (log.size > 5) TextButton(onClick = { allHistory = !allHistory }) { Text(if (allHistory) "Згорнути історію" else "Вся історія (${log.size})") }
        HorizontalDivider()
    }
}

@Composable fun NativeStatsScreen(snapshot: FlowSnapshot, content: NativeContent, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit, onInsight: (String) -> Unit, modifier: Modifier = Modifier) {
    val days = (snapshot.data["progressDays"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    val claims = snapshot.data.objects("dailyInsightClaims").associateBy { it.text("day") }
    val unlocked = (snapshot.data["unlocked"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthKey)
    var showAll by rememberSaveable { mutableStateOf(false) }
    var eventName by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf("up") }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val levelTitle = content.progression.objects("levelTitles").lastOrNull { (it.number("level") ?: 1.0) <= snapshot.level }?.text("title").orEmpty()
    val metrics = mapOf("done" to (snapshot.data.number("done") ?: 0.0), "xpTotal" to snapshot.xpTotal, "level" to snapshot.level.toDouble(), "zonesCount" to snapshot.zones.size.toDouble(), "activeDays" to days.size.toDouble())
    val achievements = content.progression.objects("achievements")
    val tokens = content.progression.objects("tokens")
    val xp = snapshot.data.number("xp") ?: 0.0
    val xpNeeded = (Progression.totalXpToReachLevel(snapshot.level.toDouble() + 1) - Progression.totalXpToReachLevel(snapshot.level.toDouble())).coerceAtLeast(1.0)
    LazyColumn(modifier.fillMaxSize().imePadding().testTag("native-stats"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Рівень ${snapshot.level} · $levelTitle", style = MaterialTheme.typography.headlineSmall)
            Text("${xp.toLong()} / ${xpNeeded.toLong()} од. до наступного рівня · всього ${snapshot.xpTotal.toLong()} од.")
            LinearProgressIndicator(progress = { (xp / xpNeeded).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${days.size} активних днів · шлях зі 100 днів", style = MaterialTheme.typography.titleMedium)
            Text("День зараховується за дію. Саме відкриття застосунку не рахується; пропуски не віднімають поступ.")
            Text("Виконано: ${(snapshot.data.number("done") ?: 0.0).toLong()} · задач: ${snapshot.tasks.count { !it.trashed && !it.someday }} · подій у журналі: ${(snapshot.data.number("negCount") ?: 0.0).toLong()}")
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { monthKey = month.minusMonths(1).toString() }) { Text("←") }
                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("uk"))), Modifier.weight(1f))
                TextButton(onClick = { monthKey = month.plusMonths(1).toString() }) { Text("→") }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(7) { column ->
                        val number = week * 7 + column - offset + 1
                        if (number in 1..month.lengthOfMonth()) {
                            val date = month.atDay(number).toString()
                            val earned = date in claims
                            Card(Modifier.weight(1f).heightIn(min = 50.dp).clickable { onInsight(date) }, colors = CardDefaults.cardColors(containerColor = if (earned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(number.toString()); Text(if (earned) "●" else if (date in days) "·" else "", style = MaterialTheme.typography.labelSmall) }
                            }
                        } else Spacer(Modifier.weight(1f))
                    }
                }
            }
            Text("Торкнися дня, щоб перечитати відкриту думку. Порожній день не скидає поступ.", style = MaterialTheme.typography.bodySmall)
        }
        item { Text("Колекція знаків · ${tokens.count { (it.number("day") ?: Double.MAX_VALUE) <= days.size }} / 20", style = MaterialTheme.typography.titleMedium) }
        items(tokens.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { token ->
                    val day = token.number("day")!!.toInt()
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text(if (days.size >= day) token.text("title") else "Ще закрито"); Text("$day активних днів", style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
        item { Text("Відзнаки · ${unlocked.size} / ${achievements.size}", style = MaterialTheme.typography.titleMedium); TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Згорнути" else "Показати всі") } }
        items(if (showAll) achievements else achievements.sortedByDescending { (metrics[it.text("metric")] ?: 0.0) / (it.number("target") ?: 1.0) }.take(8), key = { it.text("id") }) { achievement ->
            val earned = achievement.text("id") in unlocked
            val target = achievement.number("target") ?: 1.0
            val value = (metrics[achievement.text("metric")] ?: 0.0).coerceAtMost(target)
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(achievement.text("name"), style = MaterialTheme.typography.titleMedium)
                Text(achievement.text("description"))
                Text(if (earned) "Здобуто" else "${value.toLong()} / ${target.toLong()}")
                LinearProgressIndicator(progress = { if (earned) 1f else (value / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } }
        }
        item { Text("Рідкісні події", style = MaterialTheme.typography.titleMedium) }
        items(snapshot.data.objects("rareEvents"), key = { it["id"].toString() }) { event ->
            val id = event.number("id")!!.toLong()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${event.text("nm")} · ${event.number("val")?.toLong() ?: 0}", Modifier.weight(1f))
                TextButton(onClick = { onCommand(FlowCommand.ChangeRareEvent(id, -1.0, newCommandId(), commandTime())) {} }, enabled = !busy) { Text("−") }
                TextButton(onClick = { onCommand(FlowCommand.ChangeRareEvent(id, 1.0, newCommandId(), commandTime())) {} }, enabled = !busy) { Text("+") }
                TextButton(onClick = { deleteId = id }, enabled = !busy) { Text("Видалити") }
            }
        }
        item {
            OutlinedTextField(eventName, { eventName = it }, label = { Text("Назва події") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            NativeChoice("Напрямок", direction, listOf("up" to "Хочу частіше", "down" to "Хочу рідше"), !busy) { direction = it }
            Button(onClick = { onCommand(FlowCommand.CreateRareEvent(eventName, direction, newCommandId(), commandTime())) { eventName = "" } }, enabled = !busy && eventName.isNotBlank()) { Text("Додати подію") }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Видалити лічильник події?") },
        confirmButton = { TextButton(onClick = { onCommand(FlowCommand.DeleteRareEvent(deleteId!!, newCommandId(), commandTime())) { deleteId = null } }, enabled = !busy) { Text("Видалити") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Скасувати") } })
}
