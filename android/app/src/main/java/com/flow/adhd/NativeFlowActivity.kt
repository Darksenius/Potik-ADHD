package com.flow.adhd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.flow.adhd.ui.FlowTheme
import com.flow.adhd.ui.NativeFlowScreen
import com.flow.adhd.ui.NativeFlowViewModel

/** The native development variant disables MainActivity and uses its own application ID/data. */
class NativeFlowActivity : ComponentActivity() {
    private lateinit var model: NativeFlowViewModel
    private var incomingTaskId: Long? = null
    private var incomingInsight: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.NATIVE_RUNTIME) { finish(); return }
        val container = AppContainer.get(this)
        model = ViewModelProvider(this, NativeFlowViewModel.Factory(container.nativeRuntime, container.nativeContent))[NativeFlowViewModel::class.java]
        handleIncoming(intent, savedInstanceState?.getBoolean("shared") == true)
        render()
    }

    private fun render() = setContent {
        FlowTheme { NativeFlowScreen(model, incomingTaskId, incomingInsight, AppContainer.get(this).nativeContent) }
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
        if (!shared && intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" && !intent.getBooleanExtra("shared", false)) {
            val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.trim().orEmpty().take(500)
            val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val text = listOf(subject, body).filter { it.isNotEmpty() }.joinToString("\n\n").take(12_000)
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

    override fun onResume() { super.onResume(); if (::model.isInitialized) model.refresh() }
    override fun onPause() {
        if (::model.isInitialized) model.flushDraft()
        if (BuildConfig.NATIVE_RUNTIME) AppContainer.get(this).nativeRuntime.flushBackup()
        super.onPause()
    }
}
