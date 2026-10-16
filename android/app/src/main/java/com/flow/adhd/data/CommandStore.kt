package com.flow.adhd.data

/** State, replay receipts and exact queue acknowledgements share one transaction. */
interface CommandStore {
    fun <T> transaction(block: (Transaction) -> T): T

    interface Transaction {
        fun readSnapshot(): String?
        fun readJournal(): String?
        fun writeSnapshot(json: String)
        fun writeJournal(json: String)
        fun acknowledge(eventIds: List<Long>)
    }
}
