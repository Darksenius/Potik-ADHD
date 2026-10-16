package com.flow.adhd.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SnapshotRead {
    data object Loading : SnapshotRead
    data object Empty : SnapshotRead
    data class Ready(val snapshot: FlowSnapshot) : SnapshotRead
    data class Unavailable(val code: String) : SnapshotRead
}

/** Supplies one read of the persisted snapshot without owning any write path. */
fun interface SnapshotSource {
    suspend fun read(): SnapshotRead
}

/** Serializes refreshes and exposes the last read state to lifecycle-aware UI. */
class FlowStateReader(private val source: SnapshotSource) {
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow<SnapshotRead>(SnapshotRead.Loading)
    val state: StateFlow<SnapshotRead> = mutableState.asStateFlow()

    suspend fun refresh() {
        refreshMutex.withLock {
            mutableState.value = SnapshotRead.Loading
            try {
                mutableState.value = source.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = SnapshotRead.Unavailable("read_failed")
            }
        }
    }
}
