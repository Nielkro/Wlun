package app.qthrone.mobile.deploy

object DeployProtocol {
    fun safeQwdttServer(bytes: ByteArray): Boolean {
        val text=String(bytes,Charsets.ISO_8859_1)
        return !text.contains("/etc/wireguard") && (!text.contains("wg0") || text.contains("wdtt0"))
    }
    fun progress(kind: String,line: String): Pair<Int,String>? {
        val prefix=if(kind=="qwdtt") "WDTT_PROGRESS" else if(kind=="csqtt") "CSQTT_PROGRESS" else return null
        val parts=line.trim().split('|',limit=3)
        if(parts.size!=3 || parts[0]!=prefix) return null
        val value=parts[1].toDoubleOrNull() ?: return null
        if(!value.isFinite() || value !in 0.0..1.0) return null
        return (value*100).toInt().coerceIn(0,99) to parts[2]
    }
    fun quote(value: String) = "'"+value.replace("'","'\"'\"'")+"'"
    fun successful(kind: String,exit: Int,output: String): Boolean = exit == 0 && when(kind) {
        "qwdtt" -> output.contains("WDTT_DEPLOY_OK") && Regex("WDTT_ADMIN_PIN\\|(sha256/[A-Za-z0-9+/=]+)").containsMatchIn(output)
        "csqtt" -> output.contains("CSQTT_DEPLOY_OK")
        else -> false
    }
}
