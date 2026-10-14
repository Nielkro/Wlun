package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.types.OpenConnect
import app.qthrone.mobile.outbound.types.Socks
import app.qthrone.mobile.outbound.types.Qwdtt
import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.bg.carrier.VpnListenerPolicy
import org.junit.Assert.*
import org.junit.Test

class RemoteLanAndSharingTest {
    @Test fun openConnectCapturesRemotePrivateLanBeforeGenericRouting() {
        val profile=OpenConnect().apply { server="vpn.example.org" }
        fun config(enabled: Boolean): JsonObject {
            val result=ConfigGenerator(ProfileProvider { profile },GeneratorSettings(vpnMode=true,openConnectPrivateNetworks=enabled)).build(1)
            assertNull(result.error)
            return JsonInput.parseObject(result.coreConfig)
        }
        val enabled=config(true)
        val tun=enabled.array("inbounds").filterIsInstance<JsonObject>().first { it.string("type")=="tun" }
        assertFalse(tun.array("route_exclude_address").contains("192.168.0.0/16"))
        val rules=enabled.obj("route").array("rules").filterIsInstance<JsonObject>()
        assertTrue(rules.any { it.bool("ip_is_private") && it.string("outbound")=="proxy" })
        assertFalse(config(false).obj("route").array("rules").filterIsInstance<JsonObject>().any { it.bool("ip_is_private") })
    }
    @Test fun sharingIsExplicitAndAlwaysAuthenticated() {
        val profile=Socks().apply { server="192.0.2.1";serverPort=1080 }
        val result=ConfigGenerator(ProfileProvider { profile },GeneratorSettings(vpnMode=true,
            mixedInboundEnabled=false,lanSharingEnabled=true,mixedAuth=false,
            mixedUsername="sharing-user",mixedPassword="01234567890123456789012345678901")).build(1)
        assertNull(result.error)
        VpnListenerPolicy.check(result.coreConfig,true)
        try { VpnListenerPolicy.check(result.coreConfig);fail("LAN opened without explicit permission") } catch(_:IllegalStateException) {}
        val mixed=JsonInput.parseObject(result.coreConfig).array("inbounds").filterIsInstance<JsonObject>().first { it.string("type")=="mixed" }
        assertEquals("0.0.0.0",mixed.string("listen"))
        assertEquals(1,mixed.array("users").size)
    }
    @Test fun qwdttUsesRuntimeIpv4WithoutChangingGlobalPreferences() {
        val original=GeneratorSettings(vpnMode=true,ipv6Enabled=true,remoteDnsDisableIpv6=false)
        assertTrue(TransportIpPolicy.ipv4Only(Qwdtt(),ProfileProvider { null }))
        assertFalse(TransportIpPolicy.ipv4Only(Socks(),ProfileProvider { null }))
        val effective=TransportIpPolicy.settings(original)
        assertFalse(effective.ipv6Enabled)
        assertTrue(effective.remoteDnsDisableIpv6)
        assertEquals("ipv4_only",TransportIpPolicy.context(BuildContext()).directDomainStrategy())
        val proxy=Socks().apply { server="192.0.2.1";serverPort=1080 }
        val generated=ConfigGenerator(ProfileProvider { proxy },effective,TransportIpPolicy.context(BuildContext())).build(1)
        assertNull(generated.error)
        val config=JsonInput.parseObject(generated.coreConfig)
        val tun=config.array("inbounds").filterIsInstance<JsonObject>().first { it.string("type")=="tun" }
        assertEquals(1,tun.array("address").size)
        assertTrue(config.obj("dns").array("rules").filterIsInstance<JsonObject>().any {
            it.string("action")=="predefined" && it.array("query_type").contains("AAAA")
        })
        assertTrue(original.ipv6Enabled)
        assertFalse(original.remoteDnsDisableIpv6)
    }
}
