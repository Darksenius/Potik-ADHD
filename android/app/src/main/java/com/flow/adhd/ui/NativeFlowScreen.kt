package com.flow.adhd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.flow.adhd.R
import com.flow.adhd.AppContainer
import com.flow.adhd.NativeStorageTools
import com.flow.adhd.data.NativeContent
import com.flow.adhd.domain.*
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** Native application shell. It uses the same command owner as the background service. */
@Composable
fun NativeFlowScreen(model: NativeFlowViewModel, incomingTask: Long?, incomingInsight: String?, content: NativeContent, incomingNote: Long? = null) {
    val context = LocalContext.current
    val tools = remember(context) { AppContainer.get(context).storageTools }
    val recoveryPrefs = remember(context) { context.getSharedPreferences("native_recovery", android.content.Context.MODE_PRIVATE) }
    var recoveryNotice by remember { mutableStateOf(recoveryPrefs.getBoolean("notice", false)) }
    val read by model.snapshots.collectAsStateWithLifecycle()
    val projection by model.projection.collectAsStateWithLifecycle()
    val draft by model.notepad.collectAsStateWithLifecycle()
    val status by model.saveStatus.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val feedback by model.feedback.collectAsStateWithLifecycle()
    val undo by model.undo.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(undo?.commandId) {
        snackbar.currentSnackbarData?.dismiss()
        undo?.let { action ->
            if (snackbar.showSnackbar("Дію збережено", "Скасувати", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) model.undoAction(action)
            model.dismissUndo(action.commandId)
        }
    }
    var page by rememberSaveable { mutableStateOf("tasks") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var moreAuthoring by rememberSaveable { mutableStateOf(false) }
    var editorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var insightDay by rememberSaveable { mutableStateOf<String?>(null) }
    var openedNote by rememberSaveable { mutableStateOf<Long?>(null) }
    var touch by remember { mutableStateOf(Offset(140f, 300f)) }
    val snapshot = (read as? SnapshotRead.Ready)?.snapshot
    val edit: (Long) -> Unit = { editorId = it; editorOpen = true }
    LaunchedEffect(incomingTask, snapshot != null) { if (incomingTask != null && snapshot?.tasks?.any { it.id == incomingTask } == true) edit(incomingTask) }
    LaunchedEffect(incomingInsight) { if (incomingInsight != null) insightDay = incomingInsight }
    LaunchedEffect(incomingNote) { if (incomingNote != null) { openedNote = incomingNote; page = "notes" } }
    LaunchedEffect(snapshot != null) { if (snapshot != null) recoveryNotice = recoveryPrefs.getBoolean("notice", false) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.effects.collect { effect ->
                val payload = effect.event.payload
                when (effect.event.type) {
                    "navigate_note" -> { openedNote = (payload["noteId"] as? JsonPrimitive)?.longOrNull; editorOpen = false; page = "notes" }
                    "navigate_task" -> (payload["taskId"] as? JsonPrimitive)?.longOrNull?.let { edit(it) }
                    "navigate_insight" -> insightDay = (payload["day"] as? JsonPrimitive)?.content
                }
            }
        }
    }
    BackHandler(editorOpen && !busy) { editorOpen = false }
    val shellChromeVisible = !editorOpen && !(page == "more" && moreAuthoring)

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let { touch = it.position }
            }
        }
    }) {
        Scaffold(
            snackbarHost = { if (!busy) SnackbarHost(snackbar) },
            topBar = {
                if (shellChromeVisible) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.potik_mark), null, Modifier.size(32.dp))
                    Text("Потік", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if ((snapshot?.data?.get("preferences") as? JsonObject)?.get("showGamification") != JsonPrimitive(false))
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                            Text("Рів ${snapshot?.level ?: 1} · ${snapshot?.xpTotal?.toLong() ?: 0} од", Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                }
            },
            bottomBar = {
                if (shellChromeVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("tasks" to "Задачі", "plan" to "План", "notes" to "Нотатки", "more" to "Ще").forEachIndexed { index, (key, label) ->
                        NavigationBarItem(selected = page == key, onClick = { model.flushDraft(); page = key },
                            icon = { NativeNavigationIcon(index) }, label = { Text(label) }, modifier = Modifier.testTag("native-nav-$key"),
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (recoveryNotice) Surface(color = MaterialTheme.colorScheme.secondaryContainer) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Старі дані збережено в архів. Його можна експортувати в Налаштуваннях.", Modifier.weight(1f))
                    TextButton(onClick = { recoveryPrefs.edit().putBoolean("notice", false).apply(); recoveryNotice = false }) { Text("Гаразд") }
                } }
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
                        if (error != null) OutlinedButton(onClick = { tools.exportOriginal() }) { Text("Експортувати оригінальні дані") }
                    }
                    editorOpen -> NativeTaskEditor(snapshot, editorId, busy, onSave = { payload ->
                        val command = FlowCommand.SaveTaskEditor(editorId, payload, null, false, UUID.randomUUID().toString(), System.currentTimeMillis())
                        model.command(command) { editorOpen = false }
                    }, onClose = { model.flushDraft(); editorOpen = false }, modifier = Modifier.weight(1f),
                        onSnooze = { payload, minutes, tomorrow -> model.command(FlowCommand.SaveTaskEditor(editorId, payload, minutes, tomorrow, UUID.randomUUID().toString(), System.currentTimeMillis())) { editorOpen = false } },
                        onDraftChanged = model::editTaskDraft)
                    page == "notes" -> NativeNotesScreen(snapshot, draft, status, busy, model::editNotepad, model::command,
                        model::fileNotepad, onMakeTask = { model.makeTask(edit) }, modifier = Modifier.weight(1f), openNoteId = openedNote)
                    page == "plan" -> NativePlannerScreen(snapshot, busy, model::command, edit, Modifier.weight(1f))
                    page == "more" -> NativeMore(snapshot, projection, content, busy, model::command, onInsight = { insightDay = it },
                        onCreate = { editorId = null; editorOpen = true }, onEdit = edit, tools = tools, modifier = Modifier.weight(1f),
                        presetAuthoring = moreAuthoring,
                        onAuthoringChanged = { moreAuthoring = it })
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
        fun at(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        when (index) {
            3 -> for (x in listOf(.2f, .5f, .8f)) drawCircle(color, stroke, at(x, .5f))
            1 -> {
                drawRoundRect(color, at(.12f, .2f), Size(size.width * .76f, size.height * .7f), CornerRadius(stroke), style = Stroke(stroke))
                drawLine(color, at(.12f, .4f), at(.88f, .4f), stroke)
                for (x in listOf(.3f, .7f)) drawLine(color, at(x, .08f), at(x, .3f), stroke)
                for (x in listOf(.33f, .66f)) for (y in listOf(.57f, .75f)) drawCircle(color, stroke / 1.5f, at(x, y))
            }
            2 -> {
                drawRoundRect(color, at(.18f, .08f), Size(size.width * .68f, size.height * .84f), CornerRadius(stroke), style = Stroke(stroke))
                drawLine(color, at(.31f, .08f), at(.31f, .92f), stroke)
                for (y in listOf(.3f, .5f, .7f)) drawLine(color, at(.43f, y), at(.73f, y), stroke)
            }
            else -> for (y in listOf(.25f, .5f, .75f)) {
                drawCircle(color, stroke, at(.15f, y))
                drawLine(color, at(.35f, y), at(.9f, y), stroke)
            }
        }
    }
}

@Composable
private fun NativeMore(snapshot: FlowSnapshot, projection: JsonObject, content: NativeContent, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit,
    onInsight: (String) -> Unit, onCreate: () -> Unit, onEdit: (Long) -> Unit, tools: NativeStorageTools, modifier: Modifier,
    presetAuthoring: Boolean = false, onAuthoringChanged: (Boolean) -> Unit = {}) {
    var destination by rememberSaveable { mutableStateOf("") }
    var settingsDocumentOpen by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onAuthoringChanged(false) } }
    if (destination.isNotEmpty()) {
        BackHandler(destination.isNotEmpty() && !presetAuthoring) { destination = "" }
        Column(modifier) {
            if (!(destination == "presets" && presetAuthoring) && !(destination == "settings" && settingsDocumentOpen))
                TextButton(onClick = { destination = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Назад") }
            when (destination) {
                "zones" -> NativeZonesScreen(snapshot, busy, onCommand, Modifier.weight(1f))
                "folders" -> NativeFoldersScreen(snapshot, busy, onCommand, Modifier.weight(1f))
                "presets" -> NativePresetsScreen(snapshot, busy, onCommand, Modifier.weight(1f), onAuthoringChanged = { active ->
                    onAuthoringChanged(active)
                })
                "stats" -> NativeStatsScreen(snapshot, content, busy, onCommand, onInsight, Modifier.weight(1f))
                "settings" -> NativeSettingsScreen(snapshot, busy, onCommand, tools, Modifier.weight(1f),
                    onDocumentOpenChanged = { settingsDocumentOpen = it })
                "state" -> NativeTasksScreen(snapshot, projection, busy, onCommand, onCreate, onEdit, Modifier.weight(1f), initialFilter = "daily",
                    header = { NativeStateControls(snapshot, busy, onCommand) })
            }
        }
        return
    }
    LazyColumn(modifier.testTag("native-more"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PotikMenuRow("Налаштування", "Вигляд, сповіщення, дані та допомога", { destination = "settings" }, Modifier.padding(top = 8.dp).testTag("native-open-settings")) }
        item { Text("Організувати", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { PotikMenuRow("Зони", "Напрямки дня та їхній розклад", { destination = "zones" }) }
        item { PotikMenuRow("Пресети дня", "Типові дні, справи й тижневі правила", { destination = "presets" }) }
        item { PotikMenuRow("Папки нотаток", "Назви й порядок твоїх папок", { destination = "folders" }) }
        item { Text("Твій поступ", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { PotikMenuRow("Стан", "Енергія, фокус і пріоритети дня", { destination = "state" }) }
        item { PotikMenuRow("Статистика", "Активні дні, думки та відзнаки", { destination = "stats" }) }
        val insight = projection["dailyInsight"] as? JsonObject
        if (insight != null) item { PotikMenuRow("Думка дня", "Відкрити сьогоднішню картку", { onInsight((insight["day"] as? JsonPrimitive)?.content.orEmpty()) }) }
        if (com.flow.adhd.BuildConfig.APPLICATION_ID.endsWith(".native")) item {
            Text("Окрема тестова збірка", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
