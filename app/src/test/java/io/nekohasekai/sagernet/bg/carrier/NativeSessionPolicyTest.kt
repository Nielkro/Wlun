package app.qthrone.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class NativeSessionPolicyTest {
    @Test fun nineWorkerProbeNeverChangesTheProfileOrAnInteractiveConnection() {
        val source="""{"peer":"192.0.2.1:56003","device_id":"phone","workers":108,"mode":"raw","hashes":["one","two","three"]}"""
        val probe=app.qthrone.mobile.outbound.json.JsonInput.parseObject(NativeSessionPolicy.probeConfig(CarrierKind.QWDTT,source,false))
        assertEquals(9,probe.int("workers"))
        assertEquals("raw",probe.string("mode"))
        assertTrue(probe.bool("probe_only"))
        assertTrue(probe.bool("interactive_captcha"))
        assertEquals(source,NativeSessionPolicy.probeConfig(CarrierKind.QWDTT,source,true))
        val speed=app.qthrone.mobile.outbound.json.JsonInput.parseObject(NativeSessionPolicy.probeConfig(CarrierKind.QWDTT,source,false,latencyProbe=false))
        assertEquals(108,speed.int("workers"))
        assertTrue(speed.bool("probe_only"))
        val cs=app.qthrone.mobile.outbound.json.JsonInput.parseObject(NativeSessionPolicy.probeConfig(CarrierKind.CSQTT,source,false))
        assertEquals(108,cs.int("workers"))
        assertTrue(cs.bool("interactive_captcha"))
    }

    @Test fun vkPreparationHasItsOwnBudgetForBothCarriers() {
        for(kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT)) {
            assertEquals(300_000L,NativeSessionPolicy.startupTimeoutMs(kind,60_000))
            assertEquals(400_000L,NativeSessionPolicy.startupTimeoutMs(kind,400_000))
        }
        assertEquals(60_000L,NativeSessionPolicy.startupTimeoutMs(CarrierKind.OPENFLUX,60_000))
    }
    @Test fun unavailableFirstCallCanTryEveryHashWithoutChangingTheStoredConfig() {
        val source="""{"peer":"192.0.2.1:56003","device_id":"phone","hashes":["closed","second","third"],"password":"fixture"}"""
        assertTrue(NativeSessionPolicy.unavailableCall("step2: Call not found (error_code=951)"))
        assertFalse(NativeSessionPolicy.unavailableCall("network timeout"))
        assertFalse(NativeSessionPolicy.unavailableCall("FATAL_AUTH: wrong password"))
        for(attempt in 0..2) {
            val candidate=app.qthrone.mobile.outbound.json.JsonInput.parseObject(NativeSessionPolicy.rotatedHashes(source,attempt))
            assertEquals(listOf("closed","second","third")[attempt],candidate.array("hashes").strings().first())
            assertEquals(setOf("closed","second","third"),candidate.array("hashes").strings().toSet())
            assertEquals("fixture",candidate.string("password"))
            assertEquals("phone",candidate.string("device_id"))
        }
        assertEquals(source,NativeSessionPolicy.rotatedHashes(source,0))
    }

    @Test fun differentHashesMustNotCreateAnotherSessionForAnActiveDevice() {
        for(kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT)) {
            val active="""{"peer":"192.0.2.1:46000","device_id":"phone","hashes":["a"]}"""
            val changed="""{"peer":"192.0.2.1:46000","device_id":"phone","hashes":["b"],"workers":27}"""
            assertTrue(NativeSessionPolicy.sharesRemoteSession(kind,active,kind,changed))
            assertFalse(NativeSessionPolicy.sharesRemoteSession(kind,active,kind,changed.replace("192.0.2.1","192.0.2.2")))
            assertFalse(NativeSessionPolicy.sharesRemoteSession(kind,active,kind,changed.replace("phone","another")))
        }
        assertFalse(NativeSessionPolicy.sharesRemoteSession(CarrierKind.CSQTT,"{}",CarrierKind.CSQTT,"{}"))
    }
    @Test fun serverRejectionsAreReportedWithoutEchoingSecretsOrGuessingOneCause() {
        val message=NativeSessionPolicy.startupFailure("[worker] FATAL_AUTH: private-password private-device")!!
        assertFalse(message.contains("private"))
        assertTrue(message.contains("пароль"))
        assertTrue(message.contains("привязку"))
        assertNotNull(NativeSessionPolicy.startupFailure("FATAL_PROTOCOL: other version"))
        assertNull(NativeSessionPolicy.startupFailure("[READY] worker is up"))
    }
}
