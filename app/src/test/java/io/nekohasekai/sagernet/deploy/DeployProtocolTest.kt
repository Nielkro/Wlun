package app.qthrone.mobile.deploy

import org.junit.Assert.*
import org.junit.Test

class DeployProtocolTest {
    @Test fun rejectsLegacyWireguardAssetsLikeQwdtt() {
        assertFalse(DeployProtocol.safeQwdttServer("/etc/wireguard wdtt0".toByteArray()))
        assertFalse(DeployProtocol.safeQwdttServer("wg0".toByteArray()))
        assertTrue(DeployProtocol.safeQwdttServer("wg0 wdtt0 /etc/wdtt".toByteArray()))
    }
    @Test fun progressComesFromTheMatchingAuthorInstaller() {
        assertEquals(60 to "Бинарник",DeployProtocol.progress("qwdtt","WDTT_PROGRESS|0.60|Бинарник"))
        assertEquals(75 to "Сервис",DeployProtocol.progress("csqtt","CSQTT_PROGRESS|0.75|Сервис"))
        assertNull(DeployProtocol.progress("qwdtt","CSQTT_PROGRESS|0.75|Сервис"))
        assertNull(DeployProtocol.progress("csqtt","CSQTT_PROGRESS|NaN|Сервис"))
    }
    @Test fun nonzeroExitNeverMeansSuccess() {
        assertFalse(DeployProtocol.successful("csqtt",1,"CSQTT_DEPLOY_OK"))
        assertFalse(DeployProtocol.successful("qwdtt",1,"WDTT_DEPLOY_OK\nWDTT_ADMIN_PIN|sha256/abc="))
    }
    @Test fun qwdttRequiresProtectedPanelPin() {
        assertFalse(DeployProtocol.successful("qwdtt",0,"WDTT_DEPLOY_OK"))
        assertTrue(DeployProtocol.successful("qwdtt",0,"WDTT_DEPLOY_OK\nWDTT_ADMIN_PIN|sha256/abc="))
        assertTrue(DeployProtocol.successful("csqtt",0,"CSQTT_DEPLOY_OK"))
    }
    @Test fun shellArgumentsKeepQuotesAndSubstitutionLiteral() {
        assertEquals("'a'\"'\"'b'",DeployProtocol.quote("a'b"))
        assertEquals("'\$(id); rm -rf /'",DeployProtocol.quote("\$(id); rm -rf /"))
    }
}
