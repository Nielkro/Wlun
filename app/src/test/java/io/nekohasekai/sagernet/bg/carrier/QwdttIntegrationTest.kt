package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.types.Qwdtt
import app.qthrone.mobile.outbound.json.JsonInput
import org.junit.Assert.*
import org.junit.Test

class QwdttIntegrationTest {
    private fun profile()=Qwdtt().apply {
        assertTrue(parseFromLink("qwdtt://config?peer=192.0.2.1%3A56000&pass=a%2Bb&hashes=demo_hash_123456789&device_id=stable-test&mode=raw"))
    }
    @Test fun importPreservesLiteralPlusAndDeviceIdentity() {
        val original=profile()
        assertEquals("a+b",original.password)
        val restored=Qwdtt()
        assertTrue(restored.parseFromLink(original.exportToLink()))
        assertEquals(original.password,restored.password)
        assertEquals("stable-test",restored.deviceId)
        assertEquals("qwdtt",OutboundFactory.typeForScheme(original.exportToLink()))
        assertTrue(OutboundFactory.newByType("qwdtt") is Qwdtt)
    }
    @Test fun runtimePlanDoesNotPersistPortsOrBridgeSecrets() {
        val profile=profile()
        val before=profile.exportToJson().toCompact()
        val plan=CarrierPlan.qwdtt(7,profile)
        val config=JsonInput.parseObject(plan.privateJson)
        assertEquals("127.0.0.1:"+plan.port,config.string("socks"))
        assertEquals(plan.credentials.username,config.string("socks_user"))
        assertEquals(plan.credentials.password,plan.outbound().password)
        assertEquals(before,profile.exportToJson().toCompact())
        assertFalse(before.contains(plan.credentials.password))
        assertEquals("stable-test",config.string("device_id"))
    }
    @Test fun nameAndDeviceIdDoNotChangeSubscriptionIdentity() {
        val profile=profile()
        val identity=profile.exportIdentity().toCompact()
        profile.name="Renamed";profile.deviceId="another-device"
        assertEquals(identity,profile.exportIdentity().toCompact())
    }
    @Test fun invalidPrivateConfigIsRejectedBeforeNativeLaunch() {
        val profile=profile();profile.password="bad|secret"
        try { CarrierPlan.qwdtt(7,profile);fail("invalid password accepted") } catch(_:IllegalArgumentException) {}
    }
}
