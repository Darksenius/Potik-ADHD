package com.flow.adhd

import android.content.Context
import com.flow.adhd.data.LegacyRoomSnapshotRepository
import com.flow.adhd.domain.FlowStateReader

/** Small manual composition root for the current read-only native data path. */
class AppContainer private constructor(context: Context) {
    val flowStateReader = FlowStateReader(LegacyRoomSnapshotRepository(context.applicationContext))

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context.applicationContext).also { instance = it }
        }
    }
}
