package com.flow.adhd

import android.content.Context
import android.content.Intent
import android.os.Build
import com.flow.adhd.data.CommandCommit
import com.flow.adhd.data.NativeCommandRepository
import com.flow.adhd.domain.FlowCommand
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned lane for UI commands and queued background actions; never waits on flow-storage. */
class NativeRuntime internal constructor(
    private val context: Context,
    val repository: NativeCommandRepository,
    private val inbox: NativeNotificationInbox,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val editorDraftLock = Any()
    private var pendingEditorDraft: String? = null
    private var draftJob: Job? = null

    init {
        scope.launch {
            for (ignored in wakeups) refresh().await()
        }
    }

    /** The application scope finishes a submitted save even if its Activity is destroyed. */
    fun submit(command: FlowCommand): Deferred<CommandCommit> {
        if (command is FlowCommand.SaveTaskEditor) flushEditorDraft()
        return submitInternal(command)
    }

    private fun submitInternal(command: FlowCommand): Deferred<CommandCommit> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        operations.withLock {
            val pendingFailure = drainPending()
            finish(pendingFailure ?: repository.dispatch(command))
        }
    }

    /** Coalesced draft writes survive Activity destruction; submit/flush orders them before a task save. */
    fun scheduleEditorDraft(text: String) {
        synchronized(editorDraftLock) {
            if (pendingEditorDraft == text) return
            pendingEditorDraft = text
            draftJob?.cancel()
            draftJob = scope.launch { delay(250); flushEditorDraft() }
        }
    }

    fun flushEditorDraft() {
        synchronized(editorDraftLock) {
            draftJob?.cancel(); draftJob = null
            val text = pendingEditorDraft ?: return
            pendingEditorDraft = null
            submitInternal(FlowCommand.SetTaskEditorDraft(text, UUID.randomUUID().toString(), System.currentTimeMillis()))
        }
    }

    fun refresh(): Deferred<CommandCommit> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        operations.withLock {
            val pendingFailure = drainPending()
            if (repository.state.value is com.flow.adhd.domain.SnapshotRead.Loading) repository.read()
            finish(pendingFailure ?: repository.maintain())
        }
    }

    fun flushBackup(): Job {
      flushEditorDraft()
      return scope.launch(start = CoroutineStart.UNDISPATCHED) {
        operations.withLock { FlowBackupStore.get(context).flush() }
      }
    }

    private suspend fun drainPending(): CommandCommit? {
        while (true) {
            when (val batch = inbox.drain()) {
                null -> return null
                is CommandCommit.Committed -> Unit
                else -> return batch
            }
        }
    }

    private fun finish(result: CommandCommit): CommandCommit {
        mutableError.value = when (result) {
            is CommandCommit.Committed -> null
            is CommandCommit.Rejected -> "Дію не збережено (${result.code}). Записи залишилися на місці."
            is CommandCommit.Failed -> "Не вдалося зберегти дані (${result.code}). Спробуй ще раз."
        }
        if (result is CommandCommit.Committed) {
            // Derived Android effects follow the durable transaction and cannot roll it back.
            try { FlowAlarmReceiver.scheduleAll(context) } catch (error: Exception) {
                android.util.Log.w("NativeRuntime", "Unable to schedule reminders", error)
            }
            try {
                val intent = Intent(context, FlowNotifService::class.java).setAction(ACTION_PROJECTION_UPDATED)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (error: Exception) {
                android.util.Log.w("NativeRuntime", "Unable to refresh notifications", error)
            }
        }
        return result
    }

    companion object {
        const val ACTION_PROJECTION_UPDATED = "com.flow.adhd.NATIVE_PROJECTION_UPDATED"

        /** Safe from receivers/services on the FIFO lane: enqueue work, do not block that lane. */
        @JvmStatic fun requestRefresh(context: Context) {
            if (BuildConfig.NATIVE_RUNTIME) AppContainer.get(context).nativeRuntime.wakeups.trySend(Unit)
        }

        @JvmStatic fun entryClass(): Class<*> = if (BuildConfig.NATIVE_RUNTIME) NativeFlowActivity::class.java else MainActivity::class.java

        @JvmStatic fun requireLegacyWriter() {
            check(!BuildConfig.NATIVE_RUNTIME) { "Native runtime owns the state in this build" }
        }
    }
}
