package app.qthrone.mobile.bg.carrier

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bound cold test authorizations; the latency sweep also holds its permit through HTTP measurement. */
internal class NativeProbeStartupGate(concurrency:Int=3) {
    private val permits=Semaphore(concurrency)
    suspend fun <T> prepare(start:suspend ()->T):T = permits.withPermit { start() }
}
