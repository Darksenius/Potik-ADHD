package com.flow.adhd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.*
import com.flow.adhd.ui.FlowTheme
import com.flow.adhd.ui.NativeFlowScreen
import com.flow.adhd.ui.NativeFlowViewModel

/** Production entry point; UI and background commands share the application-owned runtime. */
class NativeFlowActivity : ComponentActivity() {
    private lateinit var model: NativeFlowViewModel
    private var incomingTaskId: Long? = null
    private var incomingInsight: String? = null
    private var incomingNote: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.NATIVE_RUNTIME) { finish(); return }
        val container = AppContainer.get(this)
        model = ViewModelProvider(this, NativeFlowViewModel.Factory(container.nativeRuntime, container.nativeContent))[NativeFlowViewModel::class.java]
        handleIncoming(intent, savedInstanceState?.getBoolean("shared") == true)
        render()
    }

    private fun render() = setContent {
        val read by model.snapshots.collectAsStateWithLifecycle()
        val prefs = ((read as? com.flow.adhd.domain.SnapshotRead.Ready)?.snapshot?.data?.get("preferences")) as? JsonObject
        FlowTheme((prefs?.get("themeMode") as? JsonPrimitive)?.content ?: "system") {
            NativeFlowScreen(model, incomingTaskId, incomingInsight, AppContainer.get(this).nativeContent, incomingNote)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent, false)
        render()
    }

    private fun handleIncoming(intent: Intent?, shared: Boolean) {
        incomingTaskId = intent?.getStringExtra("openTaskId")?.toLongOrNull()
        incomingInsight = intent?.getStringExtra("openInsightDay")
        incomingNote = intent?.getStringExtra("openNoteId")?.toLongOrNull()
        if (!shared && intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" && !intent.getBooleanExtra("shared", false)) {
            val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.trim().orEmpty().take(500)
            val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val combined = listOf(subject, body).filter { it.isNotEmpty() }.joinToString("\n\n")
            val suffix = "\n\n[Довгий текст скорочено…]"
            val limit = if (combined.length > 12_000) 12_000 - suffix.length else combined.length
            val safeLimit = if (limit > 0 && limit < combined.length && combined[limit - 1].isHighSurrogate()) limit - 1 else limit
            val text = combined.take(safeLimit) + if (combined.length > 12_000) suffix else ""
            if (text.isNotBlank()) {
                FlowBridge(this).pushEvent("share_text:$text")
                intent.putExtra("shared", true)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("shared", intent?.getBooleanExtra("shared", false) == true)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); if (::model.isInitialized) { model.refresh(); AppContainer.get(this).storageTools.refresh() } }
    override fun onPause() {
        if (::model.isInitialized) model.flushDraft()
        if (BuildConfig.NATIVE_RUNTIME) AppContainer.get(this).nativeRuntime.flushBackup()
        super.onPause()
    }
}
