package com.flow.adhd.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowStateReaderTest {
    @Test fun startsLoadingAndPublishesReadResults() = runBlocking {
        val reader = FlowStateReader(SnapshotSource { SnapshotRead.Empty })
        assertEquals(SnapshotRead.Loading, reader.state.value)

        reader.refresh()
        assertEquals(SnapshotRead.Empty, reader.state.value)
    }

    @Test fun serializesConcurrentRefreshes() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val calls = AtomicInteger()
        val reader = FlowStateReader(SnapshotSource {
            val nowActive = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, nowActive) }
            delay(20)
            active.decrementAndGet()
            if (calls.incrementAndGet() == 1) SnapshotRead.Empty else SnapshotRead.Unavailable("storage_error")
        })

        coroutineScope {
            val first = async { reader.refresh() }
            val second = async { reader.refresh() }
            first.await()
            second.await()
        }

        assertEquals(1, maximumActive.get())
        assertEquals(SnapshotRead.Unavailable("storage_error"), reader.state.value)
    }

    @Test fun refreshRethrowsCancellation() = runBlocking {
        val reader = FlowStateReader(SnapshotSource { throw CancellationException("cancelled") })
        var rethrown = false

        try {
            reader.refresh()
        } catch (_: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        assertEquals(SnapshotRead.Loading, reader.state.value)
    }
}
