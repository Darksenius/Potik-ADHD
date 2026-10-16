package com.flow.adhd

import com.flow.adhd.data.CommandStore
import java.util.concurrent.Callable

/** Uses the existing process-wide FIFO and Room v1; no second database or schema. */
class RoomCommandStore(private val database: FlowDatabase) : CommandStore {
    override fun <T> transaction(block: (CommandStore.Transaction) -> T): T = FlowDatabase.callStorage(Callable {
        database.runInTransaction(Callable {
            val dao = database.flowDao()
            block(object : CommandStore.Transaction {
                override fun readSnapshot(): String? = dao.stateJson
                override fun readJournal(): String? = dao.commandJournalJson
                override fun writeSnapshot(json: String) = dao.writeState(json)
                override fun writeNotification(json: String) = dao.writeNotif(json)
                override fun writeJournal(json: String) {
                    val row = AppState(json, "")
                    row.id = 2
                    dao.saveState(row)
                }
                override fun acknowledge(eventIds: List<Long>) { if (eventIds.isNotEmpty()) dao.acknowledgeEvents(eventIds) }
                override fun hasOtherEvents(eventIds: List<Long>) = dao.hasOtherPendingEvents(eventIds)
            })
        })
    })
}
