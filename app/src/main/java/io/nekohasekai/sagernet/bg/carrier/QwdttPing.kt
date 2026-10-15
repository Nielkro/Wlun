package app.qthrone.mobile.bg.carrier

import android.content.Context
import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.types.Qwdtt
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal object QwdttPing {
    // VK may spend up to 90 seconds authorizing each hash, before RunPing measures RTT.
    // The UI's network timeout must not terminate that preparation prematurely.
    fun processBudgetMs(timeoutMs:Long,hashCount:Int):Long =
        timeoutMs.coerceIn(1,300_000) + hashCount.coerceIn(1,4)*90_000L

    fun parseResult(line:String):Int? {
        if(!line.startsWith("PING_RESULT|")) return null
        return line.substringAfter('|').trim().toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?.coerceAtLeast(1)?.toInt()
    }
    fun configuration(profile:Qwdtt):String {
        require(profile.type=="qwdtt" && profile.hashSource=="manual")
        // The upstream ping is a DTLS handshake to the server port, not a RAW GETCONF session.
        // These loopback addresses are config schema placeholders: -ping-only never opens listeners.
        return profile.privateConfig(19001,19002,CarrierBridgeCredentials.fresh()).also {
            it["mode"]="wg"
            it["peer"]=Hosts.displayAddress(profile.server,profile.serverPort)
            it["probe_only"]=true;it["interactive_captcha"]=false
        }.toCompact()
    }
    suspend fun measure(context:Context,profile:Qwdtt,timeoutMs:Long=20_000):Int = execute(
        File(context.applicationInfo.nativeLibraryDir),context.cacheDir,configuration(profile),timeoutMs)

    internal suspend fun execute(nativeDirectory:File,cacheDirectory:File,privateJson:String,timeoutMs:Long=20_000,
        launch:(ProcessBuilder)->Process={ it.start() }):Int = withContext(Dispatchers.IO) {
        require(timeoutMs>0 && privateJson.toByteArray(Charsets.UTF_8).size<=65_536)
        coroutineContext.ensureActive()
        val hashCount=app.qthrone.mobile.outbound.json.JsonInput.parseObject(privateJson).array("hashes").size
        val processBudget=processBudgetMs(timeoutMs,hashCount)
        val binary=File(nativeDirectory,CarrierKind.QWDTT.executable)
        check(binary.canExecute()) { "Бинарный клиент qWDTT не найден" }
        val directory=File(cacheDirectory,"qwdtt-ping-"+UUID.randomUUID())
        check(directory.mkdir()) { "Не удалось создать каталог проверки" }
        val config=File(directory,"config.json")
        var child:Process?=null
        var bridge:CarrierCaptchaBridge?=null
        var reader:Thread?=null
        try {
            check(config.createNewFile())
            check(config.setReadable(false,false) && config.setWritable(false,false) &&
                config.setReadable(true,true) && config.setWritable(true,true))
            config.writeText(privateJson,Charsets.UTF_8)
            val builder=ProcessBuilder(binary.absolutePath,"-config",config.absolutePath,"-ping-only")
                .directory(nativeDirectory).redirectErrorStream(true).apply {
                    environment()["XDG_CACHE_HOME"]=File(cacheDirectory,"carriers").absolutePath
                }
            val running=launch(builder)
            child=running
            val captcha=CarrierCaptchaBridge(running,allowUi=false)
            bridge=captcha
            val latency=AtomicInteger(-1)
            reader=Thread({ runCatching {
                running.inputStream.bufferedReader().use { input -> input.forEachLine { line ->
                    captcha.accept(line)
                    parseResult(line)?.let { latency.set(it) }
                } }
            } },"qThrone-native-ping").apply { isDaemon=true;start() }
            val deadline=System.nanoTime()+processBudget*1_000_000
            while(System.nanoTime()<deadline) {
                coroutineContext.ensureActive()
                if(latency.get()>0) return@withContext latency.get()
                check(!captcha.interactionRequired) { "Для проверки требуется подтверждение VK" }
                val exited=try { running.exitValue();true } catch(_:IllegalThreadStateException) { false }
                if(exited) {
                    reader?.join(250)
                    if(latency.get()>0) return@withContext latency.get()
                    error("qWDTT не получил ответ на ping-only")
                }
                delay(50)
            }
            error("Время проверки qWDTT истекло")
        } finally {
            bridge?.close()
            child?.let { owned ->
                runCatching { owned.destroyForcibly() }.onFailure { runCatching { owned.destroy() } }
                runCatching { owned.inputStream.close() }
                runCatching { owned.outputStream.close() }
                runCatching { owned.errorStream.close() }
            }
            reader?.interrupt()
            directory.deleteRecursively()
        }
    }
}
