package com.flow.adhd.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flow.adhd.BuildConfig
import com.flow.adhd.AppContainer
import com.flow.adhd.FlowAlarmReceiver
import com.flow.adhd.NativeStorageTools
import com.flow.adhd.data.NativeContent
import com.flow.adhd.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

@Composable
fun NativeSettingsScreen(snapshot: FlowSnapshot, busy: Boolean, onCommand: (FlowCommand, () -> Unit) -> Unit,
    tools: NativeStorageTools, modifier: Modifier = Modifier, onDocumentOpenChanged: (Boolean) -> Unit = {}) {
    val state by tools.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appUpdates = remember(context) { AppContainer.get(context).appUpdates }
    val updateState by appUpdates.state.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf("") }
    var document by rememberSaveable { mutableStateOf("") }
    var pickerPurpose by rememberSaveable { mutableStateOf("backup") }
    var confirmReplacement by remember { mutableStateOf(false) }
    var confirmClearDemo by remember { mutableStateOf(false) }
    var demoMessage by remember { mutableStateOf("") }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val folderPicker = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: android.net.Uri?): Intent = super.createIntent(context, input)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri -> if (uri != null) tools.acceptFolder(uri, pickerPurpose) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) tools.readImport(uri) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
    val enabled = !busy && !state.busy
    val documentOpenChanged by rememberUpdatedState(onDocumentOpenChanged)
    LaunchedEffect(Unit) { tools.refresh() }
    DisposableEffect(document) {
        documentOpenChanged(document.isNotEmpty())
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { documentOpenChanged(false) } }
    if (document.isNotEmpty()) {
        BackHandler { document = "" }
        NativeLocalDocument(document, { document = "" }, modifier)
        return
    }
    val prefs = snapshot.data["preferences"] as? JsonObject ?: JsonObject(emptyMap())
    fun preference(key: String, value: JsonElement) = onCommand(FlowCommand.SetPreferences(buildJsonObject { put(key, value) }, newCommandId(), commandTime()), {})
    fun pickFolder(purpose: String) { pickerPurpose = purpose; folderPicker.launch(null) }
    LazyColumn(modifier.fillMaxSize().testTag("native-settings"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Налаштування", style = MaterialTheme.typography.headlineSmall) }
        item { FilledTonalButton(onClick = { document = "whatsnew" }, modifier = Modifier.fillMaxWidth()) { Text("Що нового · ${BuildConfig.VERSION_NAME}") } }
        item { SettingsGroup("updates", "Оновлення", "Перевірити версію та завантажити APK", group, { group = it }) {
            NativeUpdateSection(updateState, { appUpdates.check() }, tools::openUrl)
        } }
        state.message?.let { message -> item { Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f)); TextButton(onClick = tools::clearMessage) { Text("Гаразд") }
        } } } }
        item { SettingsGroup("demo", "Ознайомчий пресет завдань", "Приклади всіх типів на два дні", group, { group = it }) {
            Text("18 прикладів на сьогодні й завтра: 10 типів задач, 8 варіантів повторення, нотатки й дві зони. Сигнали вимкнені, додавання не дає досвіду. Повтори тривають до видалення задач.")
            Button(enabled = enabled, modifier = Modifier.testTag("native-demo-install"), onClick = {
                val payload = Json.parseToJsonElement(NativeContent.read(context, "demo-preset.json")).jsonObject
                onCommand(FlowCommand.InstallDemoPreset(payload, newCommandId(), commandTime())) { demoMessage = "Приклади додано на сьогодні й завтра. Повторне додавання не скидає виконання й не створює дублів." }
            }) { Text("Додати ознайомчі приклади") }
            val count = snapshot.tasks.count { it.raw.text("dayPresetKey").startsWith("potik-intro-v1:") }
            if (count > 0) TextButton(enabled = enabled, onClick = { confirmClearDemo = true }) { Text("Прибрати ознайомчі задачі ($count)") }
            if (demoMessage.isNotEmpty()) Text(demoMessage)
        } }
        item { SettingsGroup("appearance", "Вигляд", "Тема, анімації та досвід", group, { group = it }) {
            NativeChoice("Тема", prefs.text("themeMode").ifBlank { "system" }, listOf("system" to "Як у системі", "dark" to "Темна", "light" to "Світла"), enabled) { preference("themeMode", JsonPrimitive(it)) }
            SettingsToggle("Зменшити анімації", prefs.flag("reduceMotion"), enabled) { preference("reduceMotion", JsonPrimitive(it)) }
            SettingsToggle("Показувати досвід і рівень", prefs["showGamification"] != JsonPrimitive(false), enabled) { preference("showGamification", JsonPrimitive(it)) }
            Text("Мова: українська", style = MaterialTheme.typography.bodyMedium)
        } }
        item { SettingsGroup("notifications", "Сповіщення", "Дозволи й перевірка сигналів", group, { group = it }) {
            val allowed = remember(permissionRevision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            Text(if (allowed) "Сповіщення дозволені" else "Сповіщення вимкнені в Android")
            if (Build.VERSION.SDK_INT >= 33 && !allowed) OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Дозволити сповіщення") }
            OutlinedButton(onClick = { tools.openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS) }) { Text("Налаштування Android") }
            Text("У постійній панелі можна швидко записати задачу чи нотатку. Розгорни її, щоб гортати п’ять доречних задач, виконувати й відкладати їх.")
            if (Build.VERSION.SDK_INT >= 31) {
                val alarm = context.getSystemService(AlarmManager::class.java)
                Text(if (alarm.canScheduleExactAlarms()) "Точні сигнали дозволені" else "Точні сигнали потребують дозволу Android")
                OutlinedButton(onClick = { tools.openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }) { Text("Доступ до точних сигналів") }
            }
            val power = context.getSystemService(PowerManager::class.java)
            Text(if (power.isIgnoringBatteryOptimizations(context.packageName)) "Обмеження батареї зняті" else "Android може обмежувати фонову роботу")
            OutlinedButton(onClick = { tools.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("Батарея та дозволи застосунку") }
            var diagnostic by remember { mutableStateOf("") }
            OutlinedButton(enabled = enabled, onClick = {
                val at = FlowAlarmReceiver.scheduleDiagnosticReminder(context)
                diagnostic = if (at > 0) "Контрольний сигнал заплановано приблизно через хвилину. Згорни застосунок." else "Android не дозволив запланувати сигнал. Перевір дозволи вище."
            }) { Text("Контрольне сповіщення") }
            if (diagnostic.isNotEmpty()) Text(diagnostic)
        } }
        item { SettingsGroup("data", "Дані та резервні копії", "Папка, імпорт, експорт і архіви", group, { group = it }) {
            val backup = state.backup
            Text(if (backup.flag("configured")) "Папка: ${backup.text("folderName").ifBlank { "обрана" }}" else "Папку ще не обрано. Дані зберігаються всередині застосунку.")
            Text("Остання копія: ${formatStorageDate(backup.number("lastSuccessfulWrite")?.toLong() ?: 0)}")
            val error = backup.text("lastError")
            if (error.isNotEmpty()) Text(backupError(error), color = MaterialTheme.colorScheme.error)
            OutlinedButton(enabled = enabled, onClick = { pickFolder("backup") }, modifier = Modifier.testTag("native-backup-choose")) { Text("Обрати папку резервних копій") }
            OutlinedButton(enabled = enabled, onClick = { pickFolder("restore") }) { Text("Відновити з папки резервних копій") }
            if (backup.flag("configured")) OutlinedButton(enabled = enabled, onClick = { tools.flushBackup() }) { Text("Оновити копію зараз") }
            if (error == "replacement_confirmation_required") {
                OutlinedButton(enabled = enabled, onClick = { tools.readChosenBackup() }) { Text("Переглянути наявну копію") }
                TextButton(enabled = enabled, onClick = { confirmReplacement = true }) { Text("Замінити копію поточними даними…") }
            }
            Text("Копія в обраній папці залишається після видалення застосунку. Після встановлення обери цю папку знову.")
            HorizontalDivider()
            OutlinedButton(enabled = enabled, onClick = { tools.exportSnapshot() }) { Text("Експорт усіх даних") }
            OutlinedButton(enabled = enabled, onClick = { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Імпорт із файла") }
            OutlinedButton(enabled = enabled, onClick = { tools.exportSnapshot(markdown = true) }) { Text("Звіт у файл") }
            OutlinedButton(enabled = enabled, onClick = { tools.exportSnapshot(analysis = true) }) { Text("Скопіювати дані для аналізу ШІ") }
            Text("Архіви відновлення", style = MaterialTheme.typography.titleMedium)
            Text("Оригінали несумісних даних і стани перед імпортом. Автоматично не видаляються.")
            if (state.archives.isEmpty()) Text("Архівів поки немає.")
            state.archives.forEach { archive ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(formatStorageDate(archive.number("createdAt")?.toLong() ?: 0))
                    Text("Індекс: ${archive.number("size")?.toLong() ?: 0} Б", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = enabled, onClick = { tools.exportArchive(archive.text("name")) }) { Text("Експортувати архів") }
                    TextButton(enabled = enabled, onClick = { tools.restoreArchive(archive.text("name")) }) { Text("Переглянути для відновлення") }
                } }
            }
        } }
        item { SettingsGroup("about", "Про застосунок і допомога", "Довідка, ліцензія й вихідний код", group, { group = it }) {
            Text("Потік ${BuildConfig.VERSION_NAME}")
            Text("Ревізія: ${BuildConfig.SOURCE_REVISION}", style = MaterialTheme.typography.bodySmall)
            Text("Задачі, нотатки, календар, 100 думок і довідка працюють офлайн. Мережа потрібна лише для перевірки оновлень за твоїм запитом, переходу на GitHub або надсилання експортованого файла.")
            OutlinedButton(onClick = { tools.openUrl("https://github.com/Darksenius/Potik-ADHD") }) { Text("Репозиторій на GitHub") }
            OutlinedButton(onClick = { document = "help" }) { Text("Довідка") }
            OutlinedButton(onClick = { document = "license" }) { Text("Ліцензія") }
            OutlinedButton(onClick = { document = "source" }) { Text("Огляд вихідного коду офлайн") }
        } }
    }
    if (confirmReplacement) AlertDialog(onDismissRequest = { confirmReplacement = false }, title = { Text("Замінити копію в папці?") },
        text = { Text("Наявний файл буде замінено поточними даними. Спочатку віднови або експортуй його, якщо він потрібний.") },
        confirmButton = { TextButton(onClick = { confirmReplacement = false; tools.replaceExistingBackup() }) { Text("Замінити") } },
        dismissButton = { TextButton(onClick = { confirmReplacement = false }) { Text("Скасувати") } })
    if (confirmClearDemo) AlertDialog(onDismissRequest = { confirmClearDemo = false }, title = { Text("Прибрати ознайомчі задачі?") },
        text = { Text("Буде видалено лише задачі ознайомчого пресету. Твої задачі, історія виконання та досвід залишаться.") },
        confirmButton = { TextButton(onClick = { confirmClearDemo = false; onCommand(FlowCommand.ClearDemoTasks(newCommandId(), commandTime())) { demoMessage = "Ознайомчі задачі прибрано." } }) { Text("Прибрати") } },
        dismissButton = { TextButton(onClick = { confirmClearDemo = false }) { Text("Скасувати") } })
    state.importCandidate?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!state.busy) tools.cancelImport() }, title = { Text("Дані для імпорту") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Задач: ${candidate.objects("tasks").size}; нотаток: ${candidate.objects("qnotes").size}; зон: ${candidate.objects("zones").size}.")
                SettingsToggle("Повністю відновити стан", state.replace, !state.busy, tools::setReplace)
                Text(if (state.replace) "Поточний стан спочатку потрапить в архів. Потім усі дані буде замінено копією." else "Записи об’єднаються з поточними; досвід і статистика залишаться поточними.")
            } }, confirmButton = { TextButton(enabled = !state.busy, onClick = { tools.applyImport() }) { Text(if (state.busy) "Зберігаю…" else if (state.replace) "Відновити" else "Додати записи") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = tools::cancelImport) { Text("Скасувати") } })
    }
}

@Composable private fun SettingsGroup(id: String, title: String, subtitle: String, open: String, onOpen: (String) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        TextButton(onClick = { onOpen(if (open == id) "" else id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("native-settings-$id")) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (open == id) "−" else "+")
        }
        if (open == id) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun SettingsToggle(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, onChange, enabled = enabled)
    }
}
private fun formatStorageDate(at: Long): String = if (at <= 0) "ще немає" else Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
private fun backupError(code: String) = when (code) {
    "replacement_confirmation_required" -> "Наявну копію захищено від перезапису."
    "folder_access_failed", "saf_write_failed", "saf_read_failed" -> "Немає доступу до папки або запису. Обери папку знову."
    "internal_write_failed" -> "Не вдалося створити внутрішню копію. Перевір вільне місце."
    else -> "Останній запис копії не завершився. Обери папку або повтори запис."
}

@Composable private fun NativeLocalDocument(page: String, onClose: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val title = when (page) { "whatsnew" -> "Що нового"; "help" -> "Довідка"; "license" -> "Ліцензія"; else -> "Вихідний код" }
    val paragraphs = remember(page) { runCatching {
        when (page) {
            "whatsnew" -> Json.parseToJsonElement(NativeContent.read(context, "release-notes.json")).jsonArray.filterIsInstance<JsonObject>().flatMap { entry ->
                listOf("${entry.text("version")} · ${entry.text("title")}", entry.text("message")) +
                    listOf("changes" to "Зміни", "checks" to "Що перевірити", "verified" to "Перевірено", "unfinished" to "Ще не завершено").flatMap { (key, label) ->
                        val values = when (val value = entry[key]) {
                            is JsonArray -> value.map { item -> if (item is JsonObject) listOf(item.text("title")) +
                                (item["steps"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content } + listOf(item.text("expected")) else listOf(item.jsonPrimitive.content) }.flatten()
                            is JsonPrimitive -> listOf(value.content)
                            else -> emptyList()
                        }.filter { it.isNotBlank() }
                        if (values.isEmpty()) emptyList() else listOf(label) + values.map { "• $it" }
                    }
            }
            "help" -> Json.parseToJsonElement(NativeContent.read(context, "help.json")).jsonObject.objects("sections").flatMap {
                listOf(it.text("title")) + (it["text"] as? JsonArray).orEmpty().map { paragraph -> paragraph.jsonPrimitive.content }
            }
            "license" -> NativeContent.read(context, "license.txt").split("\n\n")
            else -> listOf("Повний архів джерел доступний поруч з APK у GitHub Releases. Нижче — локальний текстовий огляд.") + NativeContent.read(context, "source.txt").chunked(6000)
        }
    }.getOrElse { listOf("Не вдалося прочитати локальний документ. Він має входити до встановленого APK.") } }
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Назад") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(paragraphs.size) { index -> Text(paragraphs[index], style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
