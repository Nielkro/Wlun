package app.qthrone.mobile.deploy

object DeployProtocol {
    fun quote(value: String) = "'"+value.replace("'","'\"'\"'")+"'"
    fun successful(kind: String,exit: Int,output: String): Boolean = exit == 0 && when(kind) {
        "qwdtt" -> output.contains("WDTT_DEPLOY_OK") && Regex("WDTT_ADMIN_PIN\\|(sha256/[A-Za-z0-9+/=]+)").containsMatchIn(output)
        "csqtt" -> output.contains("CSQTT_DEPLOY_OK")
        else -> false
    }
}
