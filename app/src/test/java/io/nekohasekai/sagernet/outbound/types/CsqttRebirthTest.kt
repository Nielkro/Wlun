package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import app.qthrone.mobile.bg.carrier.NativeHashPolicy
import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.link.encryptCsqttPayload
import app.qthrone.mobile.outbound.link.formatCsqttLink
import org.junit.Assert.*
import org.junit.Test

class CsqttRebirthTest {
    @Test fun importsOriginalQrSubscriptionOnceAndCreatesFreshRuntimeSessions() {
        val link=formatCsqttLink("192.0.2.1",32322,"examplePassword1",listOf("exampleHash123456789"))
        val html="""<!DOCTYPE html><html><body><input readonly value="$link"><a href="$link">Connect</a></body></html>"""
        val p=app.qthrone.mobile.outbound.`import`.ProfileImport.parse(html).outbounds.single() as Csqtt
        assertEquals(32322,p.serverPort)
        assertEquals(listOf("exampleHash123456789"),p.hashes)
        val first=p.privateConfig(10808,9000,CarrierBridgeCredentials.fresh())
        val second=p.privateConfig(10808,9000,CarrierBridgeCredentials.fresh())
        assertTrue(first.integer("generation")>0)
        assertEquals(32,first.string("salt").length)
        assertNotEquals(first.string("salt"),second.string("salt"))
        assertFalse(p.exportToJson().contains("salt"))
        assertFalse(p.exportToJson().contains("generation"))
    }
    @Test fun importsLegacyAndNewLinksThroughTheSameFactory() {
        val forms=listOf("csqtt://secret@192.0.2.1:46000", "csqtt://connect?v=2&host=192.0.2.1&peer=46000&password=secret",
            formatCsqttLink("192.0.2.1",46000,"secret"))
        for(link in forms) {
            val p=OutboundFactory.parseLink(link) as? Csqtt
            assertNotNull(link,p)
            assertEquals("192.0.2.1",p!!.server)
            assertEquals("secret",p.password)
        }
    }
    @Test fun tokenLinksUseOriginalAutoJsAndSurviveJsonAndExport() {
        val p=Csqtt()
        assertTrue(p.parseFromLink(formatCsqttLink("2001:db8::1",46000,"secret",token="vk1.private+token")))
        assertEquals("auto_js",p.hashSource)
        assertEquals("auto_js",p.vkAnonPath)
        assertEquals("vk1.private+token",p.vkAccessToken)
        assertSame(p,NativeHashPolicy.runtimeProfile(p) { error("Token link must not use another account") })
        p.turnTransport="tcp_tls";p.srtpMode="dtls";p.workers=100
        val restored=Csqtt()
        assertTrue(restored.parseFromJson(p.exportToJson()))
        assertEquals(p.exportToJson().toCompact(),restored.exportToJson().toCompact())
        val runtime=restored.privateConfig(10808,9000,CarrierBridgeCredentials.fresh())
        assertEquals("tcp_tls",runtime.string("turn_transport"))
        assertEquals("dtls",runtime.string("srtp_mode"))
        assertEquals(100,runtime.int("workers"))
        assertTrue(Csqtt().parseFromLink(restored.exportToLink()))
    }
    @Test fun malformedEncryptedLinksCannotBecomeLegacyProfiles() {
        for(payload in listOf("connect?v=2&host=192.0.2.1&peer=46000&token=one&token=two",
            "connect?v=2&host=192.0.2.1&peer=0&password=secret"))
            assertFalse(Csqtt().parseFromLink("csqtt://"+encryptCsqttPayload(payload)))
    }
    @Test fun workersFollowLegacy250PolicyWithoutDependingOnHashCount() {
        for((request,expected) in listOf(1 to 5,5 to 5,9 to 5,10 to 10,18 to 10,99 to 90,100 to 100,126 to 100))
            assertEquals(expected,Csqtt.normalizeWorkers(request))
        assertEquals(100,NativeHashPolicy.workerLimit(Csqtt()))
    }
    @Test fun oldTcpAndOriginalSrtpNamesAreNormalized() {
        val p=Csqtt()
        assertTrue(p.parseFromJson(app.qthrone.mobile.outbound.json.jsonObjectOf(
            "type" to "csqtt","server" to "192.0.2.1","server_port" to 46000,"turn_tcp" to true,"srtp_mode" to "wrap-srtp")))
        assertEquals("tcp_tls",p.turnTransport)
        assertEquals("wrap",p.srtpMode)
    }
    @Test fun twoProfilesWithTheSamePasswordCannotProbeOverAnActiveSession() {
        val first=Csqtt().apply { server="192.0.2.1";password="shared";deviceId="first" }
        val second=Csqtt().apply { server=first.server;password=first.password;deviceId="second" }
        assertEquals(first.probeSessionKey(),second.probeSessionKey())
        second.password="another"
        assertNotEquals(first.probeSessionKey(),second.probeSessionKey())
    }
}
