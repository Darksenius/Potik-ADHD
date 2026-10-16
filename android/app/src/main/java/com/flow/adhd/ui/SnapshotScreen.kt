package com.flow.adhd.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flow.adhd.R
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.SnapshotRead
import kotlinx.serialization.json.JsonPrimitive

/** Read-only migration preview, launched only by a debug Activity. */
@Composable
fun SnapshotScreen(state: SnapshotRead, onRefresh: () -> Unit, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.potik_mark), contentDescription = null, Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text("Потік", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Нативний перегляд", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClose) { Text("Закрити") }
            }
        },
        bottomBar = {
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("snapshot-refresh")) {
                Text("Оновити")
            }
        },
    ) { padding ->
        when (state) {
            SnapshotRead.Loading -> Column(Modifier.padding(padding).padding(24.dp)) {
                CircularProgressIndicator()
                Text("Відкриваю дані…")
            }
            SnapshotRead.Empty -> Text("Ще немає збережених даних.", Modifier.padding(padding).padding(24.dp))
            is SnapshotRead.Unavailable -> Column(Modifier.padding(padding).padding(24.dp)) {
                Text("Не вдалося відкрити дані", style = MaterialTheme.typography.titleLarge)
                Text("Збережені записи залишилися на місці. Повернися до основного Потоку.")
            }
            is SnapshotRead.Ready -> SnapshotContent(state.snapshot, Modifier.padding(padding))
        }
    }
}

@Composable
private fun SnapshotContent(snapshot: FlowSnapshot, modifier: Modifier) {
    val active = snapshot.tasks.filter { !it.trashed && !it.someday && !it.done }
    LazyColumn(
        modifier.fillMaxSize().testTag("snapshot-content"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Рів ${snapshot.level} · ${snapshot.xpTotal.toLong()} од", color = MaterialTheme.colorScheme.primary)
            Text("Дані твого Потоку. Поки лише перегляд.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Text("Незавершені задачі · ${active.size}", style = MaterialTheme.typography.titleMedium) }
        items(active, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth().testTag("snapshot-task-${task.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.title, fontWeight = FontWeight.SemiBold)
                    task.note?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (snapshot.notepad.isNotBlank()) item {
            Text("Блокнот", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) { Text(snapshot.notepad, Modifier.padding(16.dp)) }
        }
        if (snapshot.qnotes.isNotEmpty()) {
            item { Text("Нотатки · ${snapshot.qnotes.size}", style = MaterialTheme.typography.titleMedium) }
            items(snapshot.qnotes) { note ->
                val text = (note["txt"] as? JsonPrimitive)?.content.orEmpty()
                Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp)) }
            }
        }
        item { Spacer(Modifier.size(8.dp)) }
    }
}
