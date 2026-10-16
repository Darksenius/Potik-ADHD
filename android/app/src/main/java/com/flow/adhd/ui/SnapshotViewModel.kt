package com.flow.adhd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flow.adhd.domain.FlowStateReader
import kotlinx.coroutines.launch

/** Observes Room without becoming a second state writer. */
class SnapshotViewModel(private val reader: FlowStateReader) : ViewModel() {
    val snapshots = reader.state
    fun refresh() { viewModelScope.launch { reader.refresh() } }

    class Factory(private val reader: FlowStateReader) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SnapshotViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return SnapshotViewModel(reader) as T
        }
    }
}
