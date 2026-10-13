package app.qthrone.mobile.deploy

import org.junit.Assert.*
import org.junit.Test

class DeployDraftTest {
    @Test fun savingDoesNotRequireDeploymentSecrets() {
        for(kind in listOf("qwdtt","csqtt")) {
            val server=DeployServer.create(kind)
            server["host"]="vps.example.org"
            server.validateAddress()
            assertFalse(server.isEmptyDraft())
        }
    }

    @Test(expected=IllegalArgumentException::class)
    fun blankServerCannotBeExplicitlySaved() {
        DeployServer.create("qwdtt").validateAddress()
    }
    @Test fun untouchedRecordsCanBeReusedForBothDeployers() {
        for(kind in listOf("qwdtt","csqtt")) {
            assertTrue(DeployServer.create(kind).isEmptyDraft())
        }
    }

    @Test fun neverCollapseRecordsWithCredentialsOrCustomSettings() {
        for(kind in listOf("qwdtt","csqtt")) {
            for(key in listOf("host","name","sshPassword","key","password","webPassword","botToken","peerPort","hostKey")) {
                val server=DeployServer.create(kind)
                server[key]="custom-value"
                assertFalse("$kind: $key",server.isEmptyDraft())
            }
        }
    }
}
