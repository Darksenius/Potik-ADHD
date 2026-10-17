package com.flow.adhd

import android.content.Context
import com.flow.adhd.data.LegacyRoomSnapshotRepository
import com.flow.adhd.data.NativeContent
import com.flow.adhd.data.NativeCommandRepository
import com.flow.adhd.data.SnapshotPreparation
import kotlinx.serialization.json.*
import com.flow.adhd.domain.FlowEngine
import com.flow.adhd.domain.NotificationProjection
import com.flow.adhd.domain.FlowStateReader

/** Manual composition root. Preparing the writer does not activate it in legacy UI or services. */
class AppContainer private constructor(context: Context) {
    val flowStateReader = FlowStateReader(LegacyRoomSnapshotRepository(context.applicationContext))
    val nativeContent by lazy { NativeContent.load(context.applicationContext) }
    val commandRepository by lazy {
        NativeCommandRepository(
            RoomCommandStore(FlowDatabase.getInstance(context.applicationContext)), FlowEngine(nativeContent.taskXp),
            afterCommit = { FlowBackupStore.get(context.applicationContext).onRoomCommit(it) },
            notificationProjection = { snapshot, at, zone -> NotificationProjection.build(snapshot, at, zone, nativeContent.insights) },
            archiveBeforeReplacement = { snapshotPreparation.archiveReplacement(it) },
        )
    }
    val snapshotPreparation by lazy {
        SnapshotPreparation(nativeContent.taskXp.keys, archive = { source, raw ->
            val saved = FlowBridge(context).archiveRecovery(buildJsonObject {
                put("savedAt", System.currentTimeMillis())
                put("sources", JsonArray(listOf(buildJsonObject { put("source", source); put("raw", raw) })))
            }.toString())
            if (saved) context.getSharedPreferences("native_recovery", Context.MODE_PRIVATE).edit().putBoolean("notice", true).apply()
            saved
        })
    }
    val notificationInbox by lazy { NativeNotificationInbox(FlowDatabase.getInstance(context.applicationContext), commandRepository, snapshotPreparation::archivePending) }
    val nativeRuntime by lazy { NativeRuntime(context.applicationContext, commandRepository, notificationInbox, snapshotPreparation) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context.applicationContext).also { instance = it }
        }
    }
}
