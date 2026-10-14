package app.qthrone.mobile.bg.test

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreparedTransportProbeTest {
    @Test fun coldTransportStartupDoesNotConsumeUrlTimeout() = runBlocking {
        var closed=false
        val result=withPreparedTransports(listOf(1), {
            delay(100) // A working carrier takes longer to connect than the URL timeout.
            AutoCloseable { closed=true }
        }) { withTimeout(50) { 123 } }
        assertEquals(123,result)
        assertTrue(closed)
    }
    @Test fun partialStartupFailureReleasesEarlierLease() = runBlocking {
        var closed=false
        var measured=false
        try {
            withPreparedTransports(listOf(1,2), {
                if(it==2) error("startup failed")
                AutoCloseable { closed=true }
            }) { measured=true }
            fail("Expected startup failure")
        } catch(_: IllegalStateException) { }
        assertTrue(closed)
        assertFalse(measured)
    }
    @Test fun cancellingPreparationPreservesActiveVpnLease() = runBlocking {
        var references=1 // The live VPN already owns one reference.
        val preparing=CompletableDeferred<Unit>()
        val job=launch {
            withPreparedTransports(listOf(1,2), {
                if(it==2) { preparing.complete(Unit);awaitCancellation() }
                references++
                AutoCloseable { references-- }
            }) { fail("Cancelled preparation must not start a URL test") }
        }
        preparing.await()
        job.cancelAndJoin()
        assertEquals(1,references)
    }
}
