package app.qthrone.mobile.bg.carrier

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class CarrierKind(val executable: String, val configFlag: String, val minimumApi: Int) {
    QWDTT("libqwdtt.so", "-config", 24),
    CSQTT("libqwdtt.so", "-csqtt-config", 26),
    OPENFLUX("libopenflux.so", "--qthrone-config", 24),
    OLCRTC("libolcrtc.so", "--qthrone-config", 24),
}

/** Per-launch credentials, deliberately without a generated toString() containing secrets. */
class CarrierBridgeCredentials(val username: String, val password: String) {
    init {
        require(username.toByteArray(Charsets.UTF_8).size in 1..255)
        require(password.toByteArray(Charsets.UTF_8).size in 1..255)
    }
    companion object {
        fun fresh() = CarrierBridgeCredentials(UUID.randomUUID().toString(), UUID.randomUUID().toString())
    }
}

/** Native client owner. Config secrets are removed before provider output enters application logs. */
class CarrierProcess private constructor(private val process: Process, private val config: File, allowInteractions: Boolean,
    private val onStatus: (NativeConnectionStage) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val startupFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val unavailableCall = AtomicBoolean(false)
    private class UnavailableCall:IllegalStateException("VK: выбранный хеш недоступен; звонок не найден")
    private class TransientStartup:IllegalStateException("Временная ошибка авторизации VK; повторите подключение")
    private val captcha=CarrierCaptchaBridge(process,allowUi=allowInteractions)
    private val health=NativeTransportHealth()
    fun recoveryFailure(): Pair<String,Boolean>? {
        if(closed.get() || captcha.waiting) return null
        if(captcha.userCancelled) return "Подтверждение VK отменено" to true
        startupFailure.get()?.let { return it to true }
        val exited=try { process.exitValue();true } catch(_:IllegalThreadStateException) { false }
        return if(exited || health.needsRestart(false,false)) "Транспорт потерял соединение" to false else null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        captcha.close()
        runCatching { process.outputStream.write("STOP\n".toByteArray()); process.outputStream.flush() }
        runCatching { process.outputStream.close() }
        // Let the Go wrapper shut down its Rust child before sending SIGTERM.
        val deadline=System.nanoTime()+1_000_000_000L
        var exited=false
        while(System.nanoTime()<deadline) {
            exited=try { process.exitValue();true } catch(_:IllegalThreadStateException) { false }
            if(exited) break
            try { Thread.sleep(20) } catch(_:InterruptedException) { Thread.currentThread().interrupt();break }
        }
        if(!exited) {
            runCatching { process.destroy() }
            val terminated=runCatching { process.waitFor(1,java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
            if(!terminated) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(1,java.util.concurrent.TimeUnit.SECONDS) }
            }
        }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        config.parentFile?.deleteRecursively()
    }

    companion object {
        suspend fun start(
            kind: CarrierKind, nativeDirectory: File, privateDirectory: File,
            apiLevel: Int, privateJson: String, socksPort: Int,
            bridgeCredentials: CarrierBridgeCredentials,
            timeoutMs: Long = 60_000,
            allowInteractions: Boolean = true,
            onStatus: (NativeConnectionStage) -> Unit = {},
            launch: (ProcessBuilder) -> Process = { it.start() },
        ): CarrierProcess {
            val hashes=app.qthrone.mobile.outbound.json.JsonInput.parseObject(privateJson).array("hashes").size
            val attempts=if(kind==CarrierKind.QWDTT) hashes.coerceIn(1,4) else 1
            val deadline=System.nanoTime()+NativeSessionPolicy.startupTimeoutMs(kind,timeoutMs)*1_000_000
            var attempt=0
            var retries=0
            while(attempt<attempts) {
                coroutineContext.ensureActive()
                val remaining=(deadline-System.nanoTime())/1_000_000
                check(remaining>0) { "Transport client startup timed out" }
                try {
                    return startOnce(kind,nativeDirectory,privateDirectory,apiLevel,
                        NativeSessionPolicy.rotatedHashes(privateJson,attempt),socksPort,bridgeCredentials,
                        remaining,allowInteractions,onStatus,launch)
                } catch(error:UnavailableCall) {
                    if(attempt==attempts-1) throw error
                    attempt++
                    runCatching { onStatus(NativeConnectionStage.RECONNECTING) }
                    moe.matsuri.nb4a.utils.CoreLog.write("[Info] [QWDTT] VK call unavailable; trying another configured hash")
                } catch(error:TransientStartup) {
                    if(retries>=2) throw error
                    retries++
                    runCatching { onStatus(NativeConnectionStage.RECONNECTING) }
                    moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] Retrying transient transport startup ($retries/2)")
                    delay(retries*2_000L)
                }
            }
            error("No transport candidate")
        }

        private suspend fun startOnce(
            kind: CarrierKind, nativeDirectory: File, privateDirectory: File,
            apiLevel: Int, privateJson: String, socksPort: Int,
            bridgeCredentials: CarrierBridgeCredentials,
            timeoutMs: Long = 60_000,
            allowInteractions: Boolean = true,
            onStatus: (NativeConnectionStage) -> Unit,
            launch: (ProcessBuilder) -> Process = { it.start() },
        ): CarrierProcess {
            // withContext may discard a successful return when cancellation wins the
            // dispatcher hand-off. Keep ownership outside it so that process is closed too.
            var owner: CarrierProcess? = null
            try {
                return withContext(Dispatchers.IO) {
                    require(apiLevel >= kind.minimumApi) { "Transport requires Android API ${kind.minimumApi}" }
                    require(socksPort in 1..65535 && timeoutMs > 0)
                    require(privateJson.toByteArray(Charsets.UTF_8).size <= 65_536) { "Transport configuration is too large" }
                    val executable = File(nativeDirectory, kind.executable)
                    check(executable.isFile && executable.canExecute()) { "Transport client is not installed" }
                    if (kind == CarrierKind.CSQTT) {
                        check(File(nativeDirectory, "libcsqtt_transport.so").canExecute()) { "CSQTT transport client is not installed" }
                    }
                    check(privateDirectory.isDirectory || privateDirectory.mkdirs()) { "Private transport directory unavailable" }
                    val sessionDirectory=File(privateDirectory,"session-"+UUID.randomUUID().toString())
                    check(sessionDirectory.mkdir()) { "Cannot create private transport session" }
                    val config=File(sessionDirectory,"config.json")
                    check(config.createNewFile()) { "Cannot create private transport configuration" }
                    try {
                        check(config.setReadable(false, false) && config.setWritable(false, false)
                            && config.setReadable(true, true) && config.setWritable(true, true)) { "Cannot protect transport configuration" }
                        config.writeText(privateJson, Charsets.UTF_8)
                        val builder = ProcessBuilder(executable.absolutePath, kind.configFlag, config.absolutePath)
                            .directory(nativeDirectory).redirectErrorStream(true)
                        builder.environment()["XDG_CACHE_HOME"] = privateDirectory.absolutePath
                        val child = try { launch(builder) } catch (_: Exception) {
                            throw IllegalStateException("Cannot start transport client")
                        }
                        val running = CarrierProcess(child, config,allowInteractions,onStatus)
                        owner = running
                        runCatching { onStatus(NativeConnectionStage.AUTHORIZING) }
                        val redactor = CarrierLogRedactor(privateJson, bridgeCredentials)
                        moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] Starting native transport")
                        val outputReader=Thread({ runCatching { child.inputStream.bufferedReader().use { input ->
                            input.forEachLine { line ->
                                runCatching { onStatus(running.health.accept(line)) }
                                running.captcha.accept(line)
                                if(NativeSessionPolicy.unavailableCall(line)) running.unavailableCall.set(true)
                                NativeSessionPolicy.startupFailure(line)?.let { running.startupFailure.compareAndSet(null,it) }
                                redactor.redact(line)?.let { safe -> moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] $safe") }
                            }
                        } }.also { running.captcha.close() } }, "qThrone-carrier-output").apply { isDaemon = true; start() }
                        var deadline = System.nanoTime() + timeoutMs * 1_000_000
                        var lastWaitTick=System.nanoTime()
                        var interactionBudget=20L*60*1_000_000_000L
                        while (System.nanoTime() < deadline) {
                            coroutineContext.ensureActive()
                            check(!running.captcha.interactionRequired) { "VK confirmation required; connect this profile before testing" }
                            check(!running.captcha.userCancelled) { "VK confirmation cancelled" }
                            running.startupFailure.get()?.let { throw IllegalStateException(it) }
                            if(kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT) && running.health.needsRestart(running.captcha.waiting,true)) throw TransientStartup()
                            val now=System.nanoTime()
                            if(running.captcha.waiting) {
                                val paused=(now-lastWaitTick).coerceAtMost(interactionBudget)
                                deadline+=paused;interactionBudget-=paused
                            }
                            lastWaitTick=now
                            val exited = try { child.exitValue(); true } catch (_: IllegalThreadStateException) { false }
                            if(exited) {
                                outputReader.join(200)
                                running.startupFailure.get()?.let { throw IllegalStateException(it) }
                                if(kind==CarrierKind.QWDTT && running.unavailableCall.get()) throw UnavailableCall()
                                if(kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT) && running.health.transientFailure) throw TransientStartup()
                                error(running.startupFailure.get() ?: "Transport client stopped before becoming ready")
                            }
                            if (socksReady(socksPort, bridgeCredentials)) {
                                moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] Native transport ready")
                                runCatching { onStatus(NativeConnectionStage.CONNECTED) }
                                return@withContext running
                            }
                            running.startupFailure.get()?.let { throw IllegalStateException(it) }
                            delay(100)
                        }
                        throw IllegalStateException("Transport client startup timed out")
                    } catch (error: Throwable) {
                        owner?.close()
                        config.parentFile?.deleteRecursively()
                        throw error
                    }
                }
            } catch (error: Throwable) {
                owner?.close()
                throw error
            }
        }

        internal fun socksReady(port: Int, credentials: CarrierBridgeCredentials): Boolean = runCatching {
            // A loopback address alone does not isolate this listener from other Android apps.
            // Reject a client which accepts the no-authentication method, even if auth also works.
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                socket.soTimeout = 200
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                val input = socket.getInputStream()
                if (input.read() != 5 || input.read() != 255) return@runCatching false
            }
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                socket.soTimeout = 200
                val output = socket.getOutputStream()
                output.write(byteArrayOf(5, 1, 2))
                val input = socket.getInputStream()
                if (input.read() != 5 || input.read() != 2) return@runCatching false
                val username = credentials.username.toByteArray(Charsets.UTF_8)
                val password = credentials.password.toByteArray(Charsets.UTF_8)
                output.write(byteArrayOf(1, username.size.toByte()) + username +
                    byteArrayOf(password.size.toByte()) + password)
                input.read() == 1 && input.read() == 0
            }
        }.getOrDefault(false)
    }
}
