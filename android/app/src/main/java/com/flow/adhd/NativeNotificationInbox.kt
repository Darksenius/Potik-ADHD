package com.flow.adhd

import com.flow.adhd.data.CommandCommit
import com.flow.adhd.data.NativeCommandRepository
import com.flow.adhd.data.QueuedNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prepared native inbox; activate only together with the single-writer ownership handover. */
class NativeNotificationInbox(private val database: FlowDatabase, private val repository: NativeCommandRepository,
    private val archiveRejected: ((QueuedNotification, String) -> Boolean)? = null) {
    suspend fun drain(limit: Int = 500): CommandCommit? {
        require(limit in 1..500)
        val envelopes = withContext(Dispatchers.IO) {
            FlowDatabase.callStorage {
                database.flowDao().getPendingEventsBatch(limit).map { QueuedNotification(it.id, it.event, it.createdAt) }
            }
        }
        return if (envelopes.isEmpty()) null else repository.dispatchPending(envelopes, archiveRejected)
    }
}
