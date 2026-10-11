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

/** Native client owner. Config contents and provider output must never enter application logs. */
class CarrierProcess private constructor(private val process: Process, private val config: File) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { process.outputStream.write("STOP\n".toByteArray()); process.outputStream.flush() }
        runCatching { process.outputStream.close() }
        // destroy() is available on Android 24; do not use the API 26 Process methods.
        runCatching { process.destroy() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        config.delete()
    }

    companion object {
        suspend fun start(
            kind: CarrierKind, nativeDirectory: File, privateDirectory: File,
            apiLevel: Int, privateJson: String, socksPort: Int,
            bridgeCredentials: CarrierBridgeCredentials,
            timeoutMs: Long = 60_000,
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
            val config = File.createTempFile("carrier-", ".json", privateDirectory)
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
                val running = CarrierProcess(child, config)
                owner = running
                // Drain both streams without retaining provider messages, tokens or URLs.
                Thread({ runCatching { child.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) >= 0) { }
                } } }, "qThrone-carrier-output").apply { isDaemon = true; start() }
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (System.nanoTime() < deadline) {
                    coroutineContext.ensureActive()
                    val exited = try { child.exitValue(); true } catch (_: IllegalThreadStateException) { false }
                    check(!exited) { "Transport client stopped before becoming ready" }
                    if (socksReady(socksPort, bridgeCredentials)) return@withContext running
                    delay(100)
                }
                throw IllegalStateException("Transport client startup timed out")
            } catch (error: Throwable) {
                owner?.close()
                config.delete()
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
