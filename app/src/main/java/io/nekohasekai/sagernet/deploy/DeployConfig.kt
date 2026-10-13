package app.qthrone.mobile.deploy

import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.link.LinkBuilder
import org.json.JSONObject

/** Importable links retain the identity and ports of the deployed server. */
object DeployConfig {
    fun link(config: JSONObject): String {
        val kind=config.getString("type")
        require(kind in listOf("qwdtt","csqtt"))
        val url=LinkBuilder(kind).apply { host=if(kind=="qwdtt") "config" else "connect";plusAsEscape=true }
        url.addQueryItem("name",config.optString("name").ifBlank { if(kind=="qwdtt") "qWDTT" else "CSQTT" })
        if(kind=="qwdtt") {
            url.addQueryItem("peer",Hosts.displayAddress(config.getString("server"),config.getInt("server_port")))
            url.addQueryItem("pass",config.getString("password"))
            if(config.optString("mode","raw")!="raw") url.addQueryItem("mode",config.getString("mode"))
            if(config.optInt("raw_port",56003)!=56003) url.addQueryItem("raw_port",config.getInt("raw_port").toString())
            if(config.optString("hash_source")=="auto") url.addQueryItem("hash_source","auto")
        } else {
            url.addQueryItem("v","2");url.addQueryItem("host",config.getString("server"))
            url.addQueryItem("peer",config.getInt("server_port").toString());url.addQueryItem("password",config.getString("password"))
        }
        config.optString("device_id").takeIf { it.isNotBlank() }?.let { url.addQueryItem("device_id",it) }
        return url.build()
    }
    fun display(saved: String): String = if(saved.trimStart().startsWith("{")) runCatching { link(JSONObject(saved)) }.getOrDefault(saved) else saved
}
