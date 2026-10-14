package app.qthrone.mobile.bg.test

/** Carrier startup has its own deadline; the URL timeout starts only in [measure].
 * Leases keep an already running VPN alive and are released on stop or a partial startup failure. */
internal suspend fun <P, L : AutoCloseable, R> withPreparedTransports(
    plans: Iterable<P>, acquire: suspend (P) -> L, measure: suspend () -> R,
): R {
    val leases=mutableListOf<L>()
    try {
        for(plan in plans) leases.add(acquire(plan))
        return measure()
    } finally {
        leases.asReversed().forEach { it.close() }
    }
}
