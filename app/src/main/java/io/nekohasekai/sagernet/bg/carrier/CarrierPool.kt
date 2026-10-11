package app.qthrone.mobile.bg.carrier

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/** VPN and tests lease the same native session instead of connecting the same device twice. */
object CarrierPool {
    private class Entry(val plan:CarrierPlan) {
        val ready=CompletableDeferred<CarrierProcess>()
        var references=0
        var process:CarrierProcess?=null
    }
    private val entries=mutableMapOf<String,Entry>()
    fun activePlan(key:String):CarrierPlan?=synchronized(entries) { entries[key]?.plan }
    fun isActive(plan:CarrierPlan)=synchronized(entries) { entries[plan.key]?.plan === plan }
    class Lease internal constructor(private val release:()->Unit):AutoCloseable {
        private val closed=AtomicBoolean()
        override fun close() { if(closed.compareAndSet(false,true)) release() }
    }
    suspend fun acquire(plan:CarrierPlan):Lease {
        var owner=false
        val entry=synchronized(entries) {
            val existing=entries[plan.key]
            check(existing==null || existing.plan===plan) { "Native session changed while configuration was being built; retry the test" }
            (existing ?: Entry(plan).also { entries[plan.key]=it;owner=true }).also { it.references++ }
        }
        fun release() {
            val process=synchronized(entries) {
                entry.references--
                if(entry.references==0 && entries[plan.key]===entry) {
                    entries.remove(plan.key)
                    entry.process
                } else null
            }
            process?.close()
        }
        try {
            if(owner) {
                val context=app.qthrone.mobile.ktx.app
                try {
                    val started=CarrierProcess.start(plan.kind,java.io.File(context.applicationInfo.nativeLibraryDir),
                        java.io.File(context.cacheDir,"carriers"),android.os.Build.VERSION.SDK_INT,
                        plan.privateJson,plan.port,plan.credentials)
                    synchronized(entries) { entry.process=started }
                    entry.ready.complete(started)
                } catch(error:Throwable) { entry.ready.completeExceptionally(error);throw error }
            }
            entry.ready.await()
            return Lease(::release)
        } catch(error:Throwable) { release();throw error }
    }
}
