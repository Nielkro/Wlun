package app.qthrone.mobile.bg.proto

import app.qthrone.mobile.bg.CoreRuntime
import app.qthrone.mobile.bg.test.TestTags
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.ktx.completeWith
import app.qthrone.mobile.outbound.types.Chain
import app.qthrone.core.mobile.Instance
import app.qthrone.core.mobile.Mobile
import app.qthrone.core.mobile.TestRequest
import app.qthrone.core.mobile.URLTestHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The URL test through the running instance's `proxy` outbound (the stats bar's ISagerNetService.urlTest). With
 * [vpnExit] a failure whose tunnel is up is [ProxyEntity.LATENCY_CONNECT_ONLY] (url_test_current,
 * mainwindow_view.cpp:405-433).
 */
suspend fun urlTestCurrent(
    instance: Instance,
    core: CoreConfig,
    url: String,
    timeoutMs: Int,
    vpnExit: Boolean,
    isKeepAlive: Boolean = (app.qthrone.mobile.database.DataStore.urlTestMode == app.qthrone.mobile.database.SettingsRegistry.URL_TEST_MODE_KEEPALIVE)
): Int {
    val testUrl = if (isKeepAlive && !url.contains("#keepalive")) url + "#keepalive" else url
    val request = TestRequest().apply {
        testCurrent = true
        this.url = testUrl
        this.timeoutMs = ProbeTimeout.effective(timeoutMs,app.qthrone.mobile.database.DataStore.baseService?.data?.proxy?.config?.carrierPlans?.isNotEmpty()==true)
        maxConcurrency = 1
        core.outboundTags.forEach(::addOutboundTag)
        if (vpnExit) addVPNEndpointTag(CoreConfig.TAG_PROXY)
    }
    val latency = AtomicInteger(-1)
    val failure = AtomicReference<String?>(null)
    val tunnelUp = AtomicBoolean(false)
    val startTime = System.currentTimeMillis()
    TestTags.holding(listOf(TestTags.ofCurrent(core.coreConfig), TestTags.CURRENT)) {
        suspendCancellableCoroutine<Unit> { continuation ->
            Mobile.startURLTest(instance, CoreRuntime.platform, request, object : URLTestHandler {
                override fun onResult(tag: String?, latencyMs: Int, error: String?) {
                    if (error.isNullOrEmpty()) {
                        val elapsed = (System.currentTimeMillis() - startTime).toInt()
                        val realLatency = if (latencyMs > 0) {
                            if (isKeepAlive) latencyMs else maxOf(latencyMs * 3 + 25, minOf(elapsed, latencyMs * 3 + 60))
                        } else {
                            latencyMs
                        }
                        latency.set(realLatency)
                    } else failure.set(error)
                }

                override fun onVPNStatus(tag: String?, connected: Boolean, state: String?, err: String?) {
                    tunnelUp.set(connected)
                }

                override fun onDone() {
                    continuation.completeWith(Result.success(Unit))
                }
            })
        }
    }
    val error = failure.get()
    if (error != null && tunnelUp.get() && !ProxyEntity.isTestAborted(error)) return ProxyEntity.LATENCY_CONNECT_ONLY
    val result = latency.get()
    if (error != null || result < 0) throw IllegalStateException(error ?: "url test produced no result")
    return result
}

/**
 * vpn_exit_endpoint (mainwindow_view.cpp:360-371): [profile] leaves through an OpenVPN or OpenConnect endpoint, a
 * chain through its last hop, so a failed test of its running instance asks for the tunnel verdict of `proxy`.
 */
internal fun exitsThroughVpn(profile: ProxyEntity?): Boolean {
    val exit = if (profile?.isChain() == true) {
        (profile.outbound as? Chain)?.list?.lastOrNull()?.let(ProfileManager::getProfile)
    } else {
        profile
    }
    return exit?.isVpnProfile() == true
}

/** What a probe box is started with: the config, its Xray half and the tags to measure. */
internal fun CoreConfig.applyTo(request: TestRequest) {
    app.qthrone.mobile.bg.carrier.VpnListenerPolicy.check(coreConfig)
    xrayConfig?.let(app.qthrone.mobile.bg.carrier.VpnListenerPolicy::checkXray)
    xrayFullConfigs.forEach(app.qthrone.mobile.bg.carrier.VpnListenerPolicy::checkXray)
    request.coreConfig = coreConfig
    request.needXray = needXray
    request.xrayConfig = xrayConfig ?: ""
    request.xrayOutboundDNSStrategy = xrayDnsStrategy
    xrayFullConfigs.forEach(request::addXrayFullConfig)
    outboundTags.forEach(request::addOutboundTag)
}
