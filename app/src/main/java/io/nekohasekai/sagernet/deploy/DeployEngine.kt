package app.qthrone.mobile.deploy

import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.bg.carrier.VkRelayAccount
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class DeployState(val serverId: String = "",val running: Boolean = false,val percent: Int = 0,val step: String = "",val logs: String = "",val config: String = "",val trust: String = "",val installerProgress: Boolean = false)

object DeployEngine {
    val state = MutableStateFlow(DeployState())
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job: Job? = null
    private var session: Session? = null
    private var trustReply: CompletableDeferred<Boolean>? = null
    fun approveHost(approved: Boolean) { trustReply?.complete(approved) }
    fun cancel() { job?.cancel();session?.disconnect();trustReply?.complete(false) }
    fun start(server: DeployServer, uninstall: Boolean) {
        if(state.value.running) return
        val copy = DeployServer(JSONObject(server.json.toString()))
        if(copy["manualPorts"] != "true") {
            copy["peerPort"]=if(copy.kind=="qwdtt") "56000" else "46000"
            copy["wgPort"]="56001";copy["directPort"]="56002";copy["rawPort"]="56003";copy["adminPort"]="56002";copy["webPort"]="46002"
        }
        state.value = DeployState(server.id,true,0,"Подключение")
        job = scope.launch {
            try { run(copy,uninstall) }
            catch(e: CancellationException) { update(0,"Операция остановлена");throw e }
            catch(e: Exception) { log("Ошибка: "+redact(copy,e.message ?: e.javaClass.simpleName));update(state.value.percent,"Ошибка установки или SSH") }
            finally { session?.disconnect();session = null;state.value = state.value.copy(running=false,trust="") }
        }
    }
    private fun update(percent: Int,step: String) { state.value = state.value.copy(percent=percent,step=step) }
    private fun log(line: String) { state.value = state.value.copy(logs=(state.value.logs+line+"\n").takeLast(200_000)) }
    private fun redact(server: DeployServer,text: String): String {
        var result = text
        for(key in listOf("sshPassword","key","keyPassword","sudoPassword","password","webPassword","botToken","adminToken")) {
            val secret = server[key]
            if(secret.isNotBlank()) result = result.replace(secret,"[скрыто]")
        }
        return result.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"),"")
    }
    fun quote(value: String) = DeployProtocol.quote(value)
    private fun root(command: String): String = "if [ \"\$(id -u)\" = 0 ]; then bash -c ${quote(command)}; else sudo -S -p '' bash -c ${quote(command)}; fi"
    private suspend fun run(server: DeployServer,uninstall: Boolean) {
        server.validate(!uninstall)
        val jsch = JSch()
        val key = server["key"].toByteArray()
        if(server["auth"] == "key") jsch.addIdentity("qThrone",key,null,server["keyPassword"].takeIf { it.isNotEmpty() }?.toByteArray())
        // Host verification precedes sending a password or authenticating with the private key.
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, bytes: ByteArray): Int {
                val fingerprint = "SHA256:"+android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes),android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val expected = server["hostKey"]
                if(expected.isNotBlank()) return if(expected == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
                val reply = CompletableDeferred<Boolean>();trustReply = reply
                state.value = state.value.copy(trust=fingerprint)
                val accepted = runBlocking { withTimeoutOrNull(120_000) { reply.await() } } == true
                state.value = state.value.copy(trust="");trustReply = null
                if(!accepted) return HostKeyRepository.CHANGED
                server["hostKey"] = fingerprint;DeployStore.save(server)
                return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?,userinfo: UserInfo?) {}
            override fun remove(host: String?,type: String?) {}
            override fun remove(host: String?,type: String?,key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "qThrone encrypted servers"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?,type: String?): Array<HostKey> = emptyArray()
        }
        val ssh = jsch.getSession(server["user"],server["host"],server.port("sshPort"))
        session = ssh
        ssh.setConfig("StrictHostKeyChecking","yes")
        ssh.setConfig("PreferredAuthentications",if(server["auth"] == "key") "publickey" else "password,keyboard-interactive")
        if(server["auth"] != "key") ssh.setPassword(server["sshPassword"])
        ssh.setServerAliveInterval(15000);ssh.setServerAliveCountMax(4)
        ssh.connect(30_000)
        val machine = exec(ssh,server,"uname -m",30_000).second.trim()
        val source = if(server.kind == "qwdtt") Pair("https://github.com/makriq-org/qwdtt/releases/download/v1.4.4/qWDTT_v1.4.4.apk","ff5b2d1f265b0c4253cdf54ebf1f8a5d64afd1424086aa8b8be754b5c14df6a7")
            else Pair("https://github.com/amurcanov/csqtt/releases/download/v2.1.9/CSQTT-universal.apk","49771d60a53d731452f3301e53dbe6bdee9142876e6b295dbf88f59f0b5aaf82")
        update(5,"Загрузка официального установщика")
        val apk = artifact(source.first,source.second)
        val binaryName = if(server.kind == "qwdtt") {
            require(machine == "x86_64" || uninstall) { "Официальный qWDTT установщик содержит сервер только для x86_64" }; "assets/server"
        } else when(machine) {
            "x86_64","amd64" -> "assets/csqtt-linux-amd64"
            "aarch64","arm64" -> "assets/csqtt-linux-arm64"
            "armv7l","armv7" -> "assets/csqtt-linux-armv7"
            else -> error("Архитектура VPS не поддерживается: $machine")
        }
        val sftp = ssh.openChannel("sftp") as ChannelSftp
        sftp.connect(30_000)
        try {
            fun upload(path: String,bytes: ByteArray) { sftp.put(bytes.inputStream(),path);sftp.chmod(384,path) }
            ZipFile(apk).use { zip ->
                fun entry(name: String): ByteArray {
                    val item = zip.getEntry(name) ?: error("Нет файла установщика $name")
                    require(item.size in 1..30_000_000)
                    return zip.getInputStream(item).use { it.readBytes() }
                }
                upload("/tmp/deploy.sh",entry("assets/deploy.sh"))
                if(!uninstall) {
                    update(30,"Передача файлов на VPS")
                    val binary=entry(binaryName)
                    if(server.kind == "qwdtt") require(DeployProtocol.safeQwdttServer(binary)) { "Устаревший сервер qWDTT: требуется сборка для wdtt0 и /etc/wdtt" }
                    upload(if(server.kind == "qwdtt") "/tmp/wdtt-server" else "/tmp/.csqtt-upload-server",binary)
                    if(server.kind == "qwdtt") {
                        upload("/tmp/wdtt-main.password",server["password"].toByteArray())
                        upload("/tmp/wdtt-admin.token",server["adminToken"].toByteArray())
                        upload("/tmp/wdtt-bot.token",server["botToken"].toByteArray())
                    } else {
                        upload("/tmp/.csqtt-upload-web.env",("CSQTT_WEB_USER="+server["webUser"]+"\nCSQTT_WEB_PASS="+server["webPassword"]+"\n").toByteArray())
                        upload("/tmp/.csqtt-upload-overrides.json",JSONObject().put("main_password",server["password"]).put("device_id",server["deviceId"]).toString().toByteArray())
                    }
                }
            }
        } finally { sftp.disconnect() }
        val env = if(server.kind == "qwdtt") "WDTT_DTLS_PORT=${server.port("peerPort")} WDTT_WG_PORT=${server.port("wgPort")} WDTT_RAW_PORT=${server.port("rawPort")} WDTT_DIRECT_PORT=${server.port("directPort")} WDTT_ADMIN_PORT=${server.port("adminPort")} WDTT_SSH_PORT=${server.port("sshPort")} WDTT_ADMIN_ID=${quote(server["adminId"])} WDTT_DNS_SERVERS=${quote(server["dns1"]+","+server["dns2"])}"
            else "CSQTT_PEER_PORT=${server.port("peerPort")} CSQTT_WEB_PORT=${server.port("webPort")} CSQTT_SSH_PORT=${server.port("sshPort")} CSQTT_DEPLOY_MODE=${if(server["docker"] == "true") "docker" else "systemd"}"
        update(45,if(uninstall) "Удаление" else "Установка и запуск сервиса")
        val result = exec(ssh,server,root("env $env bash /tmp/deploy.sh ${if(uninstall) "uninstall" else "install"}"),30*60_000L)
        require(result.first == 0) { "Установщик завершился с кодом ${result.first}" }
        if(!uninstall) {
            require(DeployProtocol.successful(server.kind,result.first,result.second)) { "Установщик не подтвердил запуск сервиса" }
            if(server.kind == "qwdtt") {
                val pin = Regex("WDTT_ADMIN_PIN\\|(sha256/[A-Za-z0-9+/=]+)").find(result.second)?.groupValues?.get(1).orEmpty()
                require(pin.isNotBlank()) { "Не получен TLS-отпечаток панели" };server["adminPin"] = pin
            }
            server["installed"] = "true";DeployStore.save(server)
            val json = JSONObject().put("type",server.kind).put("name",server["name"]).put("server",server["host"]).put("server_port",server.port("peerPort"))
                .put("password",server["password"]).put("device_id",server["deviceId"]).put("hashes",org.json.JSONArray())
                .put("hash_source",if(server.kind == "qwdtt" && VkRelayAccount.linked() && !VkRelayAccount.needsRelink()) "auto" else "manual")
            if(server.kind == "qwdtt") json.put("mode","raw").put("raw_port",server.port("rawPort"))
            state.value = state.value.copy(config=json.toString(2))
        } else { server["installed"] = "false";DeployStore.save(server) }
        update(100,if(uninstall) "Удаление завершено" else "Установка завершена")
    }
    private suspend fun artifact(url: String,sha: String): File {
        val target = File(app.cacheDir,"deploy-$sha.apk")
        fun digest(file: File): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536);while(true) { val count=input.read(buffer);if(count<0) break;hash.update(buffer,0,count) } }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if(target.exists() && digest(target) == sha) return target
        target.delete()
        val http = OkHttpClient.Builder().callTimeout(5,TimeUnit.MINUTES).build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Не удалось загрузить установщик: HTTP ${response.code}" }
                val body=response.body ?: error("Пустой установщик");val total=body.contentLength()
                body.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer=ByteArray(65536);var read=0L
                    while(true) { currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0) break;read+=count;check(read<=80_000_000);output.write(buffer,0,count);update(if(total>0) 5+(read*20/total).toInt() else 5,"Загрузка установщика") }
                } }
            }
            check(digest(target) == sha) { "Контрольная сумма установщика не совпала" }
            return target
        } catch(e: Exception) { target.delete();throw e }
    }
    private suspend fun exec(ssh: Session,server: DeployServer,command: String,timeout: Long): Pair<Int,String> {
        val channel=ssh.openChannel("exec") as ChannelExec
        channel.setCommand("$command 2>&1")
        channel.setInputStream((server["sudoPassword"].ifBlank { server["sshPassword"] }+"\n").byteInputStream())
        val input=channel.inputStream
        val output=StringBuilder();var pending=""
        val deadline=System.currentTimeMillis()+timeout
        try {
            channel.connect(30_000)
            val buffer=ByteArray(8192)
            while(true) {
                currentCoroutineContext().ensureActive()
                check(System.currentTimeMillis()<deadline) { "Превышено время ожидания SSH-команды" }
                while(input.available()>0) {
                    val count=input.read(buffer);if(count<0) break
                    val part=String(buffer,0,count,Charsets.UTF_8);output.append(part);pending+=part
                    while(pending.contains('\n')) {
                        val line=redact(server,pending.substringBefore('\n'));pending=pending.substringAfter('\n')
                        val stage=DeployProtocol.progress(server.kind,line)
                        if(stage!=null) state.value=state.value.copy(percent=stage.first,step=stage.second,installerProgress=true)
                        else log(line)
                    }
                    if(output.length>1_000_000) output.delete(0,output.length-1_000_000)
                }
                if(channel.isClosed && input.available()==0) break
                delay(100)
            }
            if(pending.isNotBlank()) log(redact(server,pending))
            return channel.exitStatus to output.toString()
        } finally { channel.disconnect() }
    }
}
