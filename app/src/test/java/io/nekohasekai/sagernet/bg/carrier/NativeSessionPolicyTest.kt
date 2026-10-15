package app.qthrone.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class NativeSessionPolicyTest {
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
