package com.flow.adhd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flow.adhd.R
import com.flow.adhd.data.NativeContent
import com.flow.adhd.domain.*
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** Native development shell. It uses the same command owner as the background service. */
@Composable
fun NativeFlowScreen(model: NativeFlowViewModel, incomingTask: Long?, incomingInsight: String?, content: NativeContent) {
    val read by model.snapshots.collectAsStateWithLifecycle()
    val projection by model.projection.collectAsStateWithLifecycle()
    val draft by model.notepad.collectAsStateWithLifecycle()
    val status by model.saveStatus.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val feedback by model.feedback.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("tasks") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var insightDay by rememberSaveable { mutableStateOf<String?>(null) }
    var touch by remember { mutableStateOf(Offset(140f, 300f)) }
    val snapshot = (read as? SnapshotRead.Ready)?.snapshot
    val edit: (Long) -> Unit = { editorId = it; editorOpen = true }
    LaunchedEffect(incomingTask, snapshot != null) { if (incomingTask != null && snapshot?.tasks?.any { it.id == incomingTask } == true) edit(incomingTask) }
    LaunchedEffect(incomingInsight) { if (incomingInsight != null) insightDay = incomingInsight }
    BackHandler(editorOpen && !busy) { editorOpen = false }

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let { touch = it.position }
            }
        }
    }) {
        Scaffold(
            topBar = {
                if (!editorOpen) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.potik_mark), null, Modifier.size(32.dp))
                    Text("Потік", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Нативна розробка", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            bottomBar = {
                if (!editorOpen) NavigationBar {
                    listOf("tasks" to "Зараз", "plan" to "План", "notes" to "Нотатки", "more" to "Ще").forEachIndexed { index, (key, label) ->
                        NavigationBarItem(selected = page == key, onClick = { model.flushDraft(); page = key },
                            icon = { NativeNavigationIcon(index) }, label = { Text(label) }, modifier = Modifier.testTag("native-nav-$key"))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("native-save-error")) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { model.flushDraft(); model.refresh() }) { Text("Повторити") }
                    }
                }
                when {
                    snapshot == null -> Column(Modifier.padding(24.dp)) {
                        if (read is SnapshotRead.Unavailable) Text("Збережені дані не вдалося прочитати. Вони залишилися на місці.")
                        else { CircularProgressIndicator(); Text("Відкриваю Потік…") }
                    }
                    editorOpen -> NativeTaskEditor(snapshot, editorId, busy, onSave = { payload ->
                        val command = editorId?.let { FlowCommand.UpdateTask(it, payload, UUID.randomUUID().toString(), System.currentTimeMillis()) }
                            ?: FlowCommand.CreateTask(payload, UUID.randomUUID().toString(), System.currentTimeMillis())
                        model.command(command) { editorOpen = false }
                    }, onClose = { editorOpen = false }, modifier = Modifier.weight(1f))
                    page == "notes" -> NativeNotesScreen(snapshot, draft, status, busy, model::editNotepad, model::command,
                        model::fileNotepad, onMakeTask = { model.makeTask(edit) }, modifier = Modifier.weight(1f))
                    page == "plan" -> NativePlannerScreen(snapshot, busy, model::command, edit, Modifier.weight(1f))
                    page == "more" -> NativeDevelopmentMore(snapshot, projection, busy, model::command, onInsight = { insightDay = it }, modifier = Modifier.weight(1f))
                    else -> NativeTasksScreen(snapshot, projection, busy, model::command,
                        onCreate = { editorId = null; editorOpen = true }, onEdit = edit, modifier = Modifier.weight(1f))
                }
            }
        }

        val preferences = snapshot?.data?.get("preferences") as? JsonObject
        if (preferences?.get("showGamification") != JsonPrimitive(false)) feedback?.let { value ->
            val opacity = remember(value.serial) { Animatable(1f) }
            val move = preferences?.get("reduceMotion") != JsonPrimitive(true)
            LaunchedEffect(value.serial) { delay(500); opacity.animateTo(0f, tween(if (move) 1100 else 1)) }
            val density = LocalDensity.current
            val maxX = with(density) { maxWidth.toPx() } - 110f
            val maxY = with(density) { maxHeight.toPx() } - 60f
            val amount = if (value.amount % 1.0 == 0.0) value.amount.toLong().toString() else value.amount.toString()
            Text((if (value.amount > 0) "+" else "") + amount + "⚡", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset { IntOffset((touch.x - 32f).coerceIn(0f, maxX.coerceAtLeast(0f)).roundToInt(),
                    (touch.y - 80f - if (move) (1f - opacity.value) * 60f else 0f).coerceIn(0f, maxY.coerceAtLeast(0f)).roundToInt()) }
                    .graphicsLayer { alpha = opacity.value }.testTag("native-xp-feedback"))
        }
    }

    if (insightDay != null && snapshot != null) {
        val claim = (snapshot.data["dailyInsightClaims"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull {
            (it["day"] as? JsonPrimitive)?.content == insightDay
        }
        val id = (claim?.get("cardId") as? JsonPrimitive)?.intOrNull
        val card = content.insights[id]
        AlertDialog(onDismissRequest = { insightDay = null }, title = { Text((card?.get("title") as? JsonPrimitive)?.content ?: "Думка дня") },
            text = { Text((card?.get("text") as? JsonPrimitive)?.content ?: "За цей день нагороду ще не відкрито. Пропуск не скидає твій поступ.") },
            confirmButton = { TextButton(onClick = { insightDay = null }) { Text("Зрозуміло") } })
    }
}

@Composable
private fun NativeNavigationIcon(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        if (index == 3) for (x in listOf(.2f, .5f, .8f)) drawCircle(color, stroke, Offset(size.width * x, size.height * .5f))
        else for (y in listOf(.25f, .5f, .75f)) {
            if (index == 0) drawCircle(color, stroke, Offset(size.width * .15f, size.height * y))
            drawLine(color, Offset(size.width * .35f, size.height * y), Offset(size.width * .9f, size.height * y), stroke)
        }
    }
}

@Composable
private fun NativeDevelopmentMore(snapshot: FlowSnapshot, projection: JsonObject, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit, onInsight: (String) -> Unit, modifier: Modifier) {
    var destination by rememberSaveable { mutableStateOf("") }
    if (destination.isNotEmpty()) {
        BackHandler { destination = "" }
        Column(modifier) {
            TextButton(onClick = { destination = "" }) { Text("← Ще") }
            when (destination) {
                "zones" -> NativeZonesScreen(snapshot, busy, onCommand, Modifier.weight(1f))
                "folders" -> NativeFoldersScreen(snapshot, busy, onCommand, Modifier.weight(1f))
            }
        }
        return
    }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Нативний Потік", style = MaterialTheme.typography.headlineSmall)
        Text("Задачі, редактор, нотатки та План уже працюють через нативне ядро. Пресети, налаштування та інші екрани ще переносяться. Це окрема збірка для розробки.")
        Text("Рів ${snapshot.level} · ${snapshot.xpTotal.toLong()} од")
        OutlinedButton(onClick = { destination = "zones" }) { Text("Зони") }
        OutlinedButton(onClick = { destination = "folders" }) { Text("Папки нотаток") }
        val insight = projection["dailyInsight"] as? JsonObject
        if (insight != null) OutlinedButton(onClick = { onInsight((insight["day"] as? JsonPrimitive)?.content.orEmpty()) }) { Text("Думка дня") }
        Text("Тестові дані цієї збірки зберігаються окремо від основного застосунку.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
