package app.qthrone.mobile.deploy

import app.qthrone.mobile.outbound.types.Csqtt
import app.qthrone.mobile.outbound.types.Qwdtt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeployConfigTest {
    private fun config(kind: String)=JSONObject().put("type",kind).put("name","VPS + test")
        .put("server","2001:db8::1").put("server_port",if(kind=="qwdtt") 56000 else 46000)
        .put("password",if(kind=="qwdtt") "secret+&!" else "secret123")
        .put("device_id","stable-deployed-owner").put("hashes",org.json.JSONArray())

    @Test fun qwdttLinkImportsPasswordsPortsAndAutoModeWithoutInventedHashes() {
        val data=config("qwdtt").put("hash_source","auto").put("raw_port",56123)
        val link=DeployConfig.link(data)
        val parsed=Qwdtt()
        assertTrue(link.startsWith("qwdtt://config?"))
        assertTrue(parsed.parseFromLink(link))
        assertEquals("2001:db8::1",parsed.server)
        assertEquals("VPS + test",parsed.name)
        assertEquals("secret+&!",parsed.password)
        assertEquals(56123,parsed.rawPort)
        assertEquals("auto",parsed.hashSource)
        assertEquals("stable-deployed-owner",parsed.deviceId)
        assertTrue(parsed.hashes.isEmpty())
    }
    @Test fun csqttLinkImportsWithoutHashesAndKeepsDeployedDeviceIdentity() {
        val link=DeployConfig.link(config("csqtt"))
        val parsed=Csqtt()
        assertTrue(link.startsWith("csqtt://connect?"))
        assertTrue(parsed.parseFromLink(link))
        assertEquals("2001:db8::1",parsed.server)
        assertEquals(46000,parsed.serverPort)
        assertEquals("secret123",parsed.password)
        assertEquals("stable-deployed-owner",parsed.deviceId)
        assertTrue(parsed.hashes.isEmpty())
    }
    @Test fun legacySavedJsonDisplaysAsAnImportableLink() {
        val data=config("qwdtt")
        assertEquals(DeployConfig.link(data),DeployConfig.display(data.toString(2)))
        assertEquals("",DeployConfig.display(""))
        val link=DeployConfig.link(data)
        assertEquals(link,DeployConfig.display(link))
    }
}
