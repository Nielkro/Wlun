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
class CarrierProcess private constructor(private val process: Process, private val config: File, allowInteractions: Boolean) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val startupFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val captcha=CarrierCaptchaBridge(process,allowUi=allowInteractions)

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
        if(!exited) runCatching { process.destroy() }
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
            launch: (ProcessBuilder) -> Process = { it.start() },
        ): CarrierProcess = withContext(Dispatchers.IO) {
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
            var owner: CarrierProcess? = null
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
                val running = CarrierProcess(child, config,allowInteractions)
                owner = running
                val redactor = CarrierLogRedactor(privateJson, bridgeCredentials)
                moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] Starting native transport")
                val outputReader=Thread({ runCatching { child.inputStream.bufferedReader().use { input ->
                    input.forEachLine { line ->
                        running.captcha.accept(line)
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
                    val now=System.nanoTime()
                    if(running.captcha.waiting) {
                        val paused=(now-lastWaitTick).coerceAtMost(interactionBudget)
                        deadline+=paused;interactionBudget-=paused
                    }
                    lastWaitTick=now
                    val exited = try { child.exitValue(); true } catch (_: IllegalThreadStateException) { false }
                    if(exited) {
                        outputReader.join(200)
                        error(running.startupFailure.get() ?: "Transport client stopped before becoming ready")
                    }
                    if (socksReady(socksPort, bridgeCredentials)) {
                        moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] Native transport ready")
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
