package com.flow.adhd.data

import android.content.Context
import com.flow.adhd.FlowBridge
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.SnapshotFormatException
import com.flow.adhd.domain.SnapshotRead
import com.flow.adhd.domain.SnapshotSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only adapter over the existing Room-backed FlowBridge state document. */
class LegacyRoomSnapshotRepository(context: Context) : SnapshotSource {
    private val applicationContext = context.applicationContext

    override suspend fun read(): SnapshotRead = withContext(Dispatchers.IO) {
        try {
            val raw = FlowBridge(applicationContext).load()
            if (raw.isBlank()) SnapshotRead.Empty
            else SnapshotRead.Ready(FlowSnapshot.parse(raw))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SnapshotFormatException) {
            SnapshotRead.Unavailable("invalid_snapshot")
        } catch (_: Exception) {
            SnapshotRead.Unavailable("storage_error")
        }
    }
}
