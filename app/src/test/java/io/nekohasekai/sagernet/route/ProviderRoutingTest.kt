package app.qthrone.mobile.route

import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.Base64Strict
import app.qthrone.mobile.outbound.config.GeneratorSettings
import org.junit.Assert.*
import org.junit.Test

class ProviderRoutingTest {
    @Test fun embeddedRoutesAreSeparatedFromServers() {
        val document = jsonObjectOf("Name" to "Provider", "GlobalProxy" to "false", "DirectSites" to JsonArray.of("geosite:ru", "full:example.org"))
        val route = "incy://routing/onadd/" + Base64Strict.encode(document.toCompact())
        val body = "vless://id@example.org:443\n$route"
        val payload = ProviderRouting.subscription(Base64Strict.encode(body))
        assertEquals("vless://id@example.org:443",payload.body)
        assertEquals("Incy",payload.commands.single().provider)
        assertTrue(payload.commands.single().activate)
        val profile = ProviderRouting.profile(payload.commands.single().document!!,"Incy")
        assertEquals(OutboundIds.DIRECT,profile.default_outbound_id)
        assertTrue(profile.rules.last().domain.contains("example.org"))
        assertTrue(profile.rules.last().rule_set.contains("geosite:ru"))
    }
    @Test fun dnsAndBlockedAddressesArePreserved() {
        val document = jsonObjectOf("GlobalProxy" to true,"RemoteDNSType" to "DoH","RemoteDNSDomain" to "https://dns.example/dns-query","BlockIp" to JsonArray.of("192.0.2.1"),"FakeDNS" to "true")
        val settings = ProviderRouting.settings(document,GeneratorSettings())
        assertEquals("https://dns.example/dns-query",settings.remoteDns)
        assertTrue(settings.fakeDns)
        val blocked = ProviderRouting.profile(document,"Happ").rules.last()
        assertEquals(OutboundIds.BLOCK,blocked.outbound_id)
        assertEquals(listOf("192.0.2.1/32"),blocked.ip_cidr)
    }
    @Test fun headerOffDoesNotRemoveServers() {
        val payload = ProviderRouting.subscription("vless://id@example.org:443","off","Incy")
        assertTrue(payload.commands.single().off)
        assertTrue(payload.body.startsWith("vless://"))
    }
}
