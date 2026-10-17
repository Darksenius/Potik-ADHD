package com.flow.adhd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flow.adhd.NativeRuntime
import com.flow.adhd.data.CommandCommit
import com.flow.adhd.data.NativeContent
import com.flow.adhd.domain.*
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class NativeFeedback(val serial: Long, val amount: Double)

/** UI drafts are local; only committed Room snapshots supply task, note and reward state. */
class NativeFlowViewModel(private val runtime: NativeRuntime, private val content: NativeContent) : ViewModel() {
    val snapshots = runtime.repository.state
    val error = runtime.error
    private val mutableDraft = MutableStateFlow("")
    val notepad = mutableDraft.asStateFlow()
    private val mutableStatus = MutableStateFlow("Відкриваю…")
    val saveStatus = mutableStatus.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableProjection = MutableStateFlow(JsonObject(emptyMap()))
    val projection = mutableProjection.asStateFlow()
    private val mutableFeedback = MutableStateFlow<NativeFeedback?>(null)
    val feedback = mutableFeedback.asStateFlow()
    private var draftDirty = false
    private var revision = 0L
    private var autosave: Job? = null

    init {
        viewModelScope.launch {
            snapshots.collectLatest { read ->
                if (read is SnapshotRead.Ready) {
                    if (!draftDirty) { mutableDraft.value = read.snapshot.notepad; mutableStatus.value = "Збережено" }
                    mutableProjection.value = withContext(Dispatchers.Default) {
                        NotificationProjection.build(read.snapshot, System.currentTimeMillis(), ZoneId.systemDefault(), content.insights)
                    }
                }
            }
        }
    }

    fun refresh() { runtime.refresh() }

    fun editNotepad(text: String) {
        mutableDraft.value = text
        revision++
        draftDirty = true
        mutableStatus.value = "Зберігаю…"
        autosave?.cancel()
        autosave = viewModelScope.launch { delay(250); persistDraft() }
    }

    /** Called before backgrounding; the application owns the submitted write after this returns. */
    fun flushDraft() {
        autosave?.cancel()
        if (draftDirty) {
            val text = mutableDraft.value
            val atRevision = revision
            val saved = runtime.submit(draftCommand(text))
            viewModelScope.launch { finishDraft(saved.await(), atRevision) }
        }
    }

    private suspend fun persistDraft(): Boolean {
        if (!draftDirty) return true
        val atRevision = revision
        val result = runtime.submit(draftCommand(mutableDraft.value)).await()
        finishDraft(result, atRevision)
        return result is CommandCommit.Committed
    }

    private fun finishDraft(result: CommandCommit, atRevision: Long) {
        if (atRevision != revision) return
        draftDirty = result !is CommandCommit.Committed
        mutableStatus.value = if (draftDirty) "Не збережено — повтори" else "Збережено"
    }

    private fun draftCommand(text: String) = FlowCommand.SetNotepad(buildJsonObject { put("notepad", text) }, id(), now())

    fun command(command: FlowCommand, onCommitted: () -> Unit = {}) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        autosave?.cancel()
        viewModelScope.launch {
            try {
                if (!persistDraft()) return@launch
                val result = runtime.submit(command).await()
                if (result is CommandCommit.Committed) {
                    val amount = result.events.filter { it.type == "xp_awarded" }.sumOf {
                        (it.payload["amount"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                    }
                    if (amount != 0.0) mutableFeedback.value = NativeFeedback(now(), amount)
                    if (command is FlowCommand.FileNotepadAsNote || command is FlowCommand.ConvertNotepadToTask) {
                        mutableDraft.value = result.snapshot.notepad
                        draftDirty = false
                        mutableStatus.value = "Збережено"
                    }
                    onCommitted()
                }
            } finally { mutableBusy.value = false }
        }
    }

    fun fileNotepad(folder: String?, onCommitted: () -> Unit) = command(FlowCommand.FileNotepadAsNote(folder, id(), now()), onCommitted)
    fun makeTask(onCreated: (Long) -> Unit) {
        val existing = ((snapshots.value as? SnapshotRead.Ready)?.snapshot?.tasks ?: emptyList()).map { it.id }.toSet()
        command(FlowCommand.ConvertNotepadToTask(id(), now())) {
            (snapshots.value as? SnapshotRead.Ready)?.snapshot?.tasks?.firstOrNull { it.id !in existing }?.let { onCreated(it.id) }
        }
    }

    class Factory(private val runtime: NativeRuntime, private val content: NativeContent) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativeFlowViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return NativeFlowViewModel(runtime, content) as T
        }
    }

    private fun id() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()
}
