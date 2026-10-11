package app.qthrone.mobile.bg.proto

import app.qthrone.mobile.bg.AbstractInstance
import app.qthrone.mobile.bg.CoreRuntime
import app.qthrone.mobile.bg.SingBoxDashboard
import app.qthrone.mobile.bg.XrayGeoAssets
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.isLoopbackPortFree
import app.qthrone.mobile.ktx.mkPort
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.outbound.config.AutoSelectorBuild
import app.qthrone.mobile.outbound.config.GeneratedConfig
import app.qthrone.mobile.outbound.types.Custom
import app.qthrone.core.mobile.Instance
import app.qthrone.core.mobile.Mobile
import app.qthrone.core.mobile.StartOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: GeneratedConfig
    lateinit var core: CoreConfig
    lateinit var box: Instance

    private val carrierProcesses = mutableListOf<app.qthrone.mobile.bg.carrier.CarrierPool.Lease>()

    val boxOrNull: Instance? get() = if (::box.isInitialized) box else null

    /** This start does not wait for remote rule-sets the core has no copy of ([DeferredRuleSets]). */
    var deferRuleSets = false

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected open fun buildConfig() {
        // random_inbound_port: a fresh port per start, saved so the app's own requests reach it (mainwindow_setup.cpp:193-196).
        if (DataStore.randomInboundPort) DataStore.inboundSocksPort = mkPort()
        // The dashboard is on by default, so another app holding its port must not fail the start: it moves, and the
        // dashboard screen follows the saved port.
        if (DataStore.apiDashboardEnabled && !isLoopbackPortFree(DataStore.coreBoxApiPort)) {
            val port = mkPort()
            Logs.w("sing-box API port ${DataStore.coreBoxApiPort} is in use, the dashboard moves to $port")
            DataStore.coreBoxApiPort = port
        }
        // A custom full config keeps its own rule-sets as written.
        if (isCustomFullConfig()) deferRuleSets = false
        config = CoreConfigs.buildMain(profile, deferRuleSets)
    }

    protected open suspend fun loadConfig() {
        box = Mobile.newInstance(CoreRuntime.platform, core.toStartOptions(config.autoSelector))
    }

    open suspend fun init() {
        buildConfig()
        core = CoreConfig.from(config, listOf(CoreConfig.TAG_PROXY))
        CoreRuntime.applyLogLevel(config.coreConfig)
        ensureXrayAssets()
        // Before the start: the core downloads the dashboard itself when it finds the dir empty.
        if (DataStore.apiDashboardEnabled) withContext(Dispatchers.IO) { SingBoxDashboard.install() }
        if (deferRuleSets) {
            // The sets' initial_path; without the file the core falls back to fetching during the start.
            withContext(Dispatchers.IO) {
                runCatching { DeferredRuleSets.writeEmptySet() }.onFailure { Logs.w("rule-sets: ${it.readableMessage}") }
            }
        }
        try {
            for(plan in config.carrierPlans) {
                carrierProcesses.add(app.qthrone.mobile.bg.carrier.CarrierPool.acquire(plan))
            }
            loadConfig()
        } catch(error:Throwable) {
            carrierProcesses.asReversed().forEach { it.close() };carrierProcesses.clear()
            throw error
        }
    }

    /** The geo asset downloads the start waits for; null once they are over. */
    protected open suspend fun onAssetProgress(progress: XrayGeoAssets.Progress?) {}

    /**
     * Fetches the data files the Xray configs about to start read (geoip: / geosite: rules) and that are missing;
     * throws [XrayGeoAssets.DownloadException] naming what could not be had.
     */
    private suspend fun ensureXrayAssets() {
        val configs = listOfNotNull(config.xrayConfig) + config.xrayFullConfigs
        if (configs.isEmpty()) return
        val missing = withContext(Dispatchers.IO) { XrayGeoAssets.missing(XrayGeoAssets.needed(configs)) }
        if (missing.isEmpty()) return
        val failures = try {
            XrayGeoAssets.ensure(missing) { onAssetProgress(it) }
        } finally {
            withContext(NonCancellable) { runCatching { onAssetProgress(null) } }
        }
        if (failures.isNotEmpty()) {
            throw XrayGeoAssets.DownloadException(
                failures.values.joinToString("\n"),
                fetchable = failures.keys.any { it in XrayGeoAssets.FILES },
            )
        }
    }

    override fun launch() {
        try {
            box.start()
        } catch (error: Throwable) {
            Logs.w("box start failed for profile ${profile.id}: ${error.message}")
            box.localDNSFailure()?.let { throw LocalDnsFailedException(it.servers, error) }
            // Not offered again by a start that went without them, nor for a custom full config.
            if (!deferRuleSets && DeferredRuleSets.isStartFailure(error.message) && !isCustomFullConfig()) {
                throw RuleSetDownloadFailedException(error)
            }
            throw error
        }
        CoreRuntime.attachRunning(box, profile.id)
        if (deferRuleSets) DeferredRuleSets.afterStart(box, config.coreConfig)
    }

    private fun isCustomFullConfig(): Boolean = (profile.outbound as? Custom)?.isFullConfig() == true

    override fun close() {
        boxOrNull?.let(CoreRuntime::detachRunning)
        try { boxOrNull?.close() } finally {
            carrierProcesses.asReversed().forEach { it.close() };carrierProcesses.clear()
        }
    }

}

/** A start that failed on the local DNS server; [servers] are the network's DNS servers it asked, empty for Android's resolver. */
class LocalDnsFailedException(val servers: String, cause: Throwable) : Exception(cause.message, cause)

/** A start that failed on a remote rule-set without a cached copy that could not be downloaded. */
class RuleSetDownloadFailedException(cause: Throwable) : Exception(cause.message, cause)

internal fun CoreConfig.toStartOptions(autoSelector: AutoSelectorBuild? = null): StartOptions = StartOptions().apply {
    coreConfig = this@toStartOptions.coreConfig
    needXray = this@toStartOptions.needXray
    xrayConfig = this@toStartOptions.xrayConfig ?: ""
    xrayOutboundDNSStrategy = xrayDnsStrategy
    this@toStartOptions.xrayFullConfigs.forEach(::addXrayFullConfig)
    // mainwindow_profile_lifecycle.cpp:231-239: the idle window must outlast the probe interval, or the sidecar
    // restarts every round; full configs stay resident (0).
    if (autoSelector != null && (needXray || this@toStartOptions.xrayFullConfigs.isNotEmpty())) {
        xrayLazyStart = true
        xrayIdleSeconds = maxOf(120, autoSelector.intervalSec * 2)
        xrayFullIdleSeconds = 0
    }
}
