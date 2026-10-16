package com.flow.adhd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flow.adhd.ui.FlowTheme
import com.flow.adhd.ui.SnapshotScreen
import com.flow.adhd.ui.SnapshotViewModel

/** Debug only: prove the native stack before switching the production launcher. */
class NativePreviewActivity : ComponentActivity() {
    private lateinit var model: SnapshotViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, SnapshotViewModel.Factory(AppContainer.get(this).flowStateReader))[SnapshotViewModel::class.java]
        setContent {
            FlowTheme {
                val state = model.snapshots.collectAsStateWithLifecycle()
                SnapshotScreen(state.value, onRefresh = model::refresh, onClose = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}
