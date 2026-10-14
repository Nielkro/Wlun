package app.qthrone.mobile.bg.carrier

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Same native -check-hashes protocol as qWDTT's HashCheckHelper (GPL-3.0).
 * Own process, no bridge listener/config, and no access to the running VPN's process. */
class CarrierHashCheck : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var captcha: CarrierCaptchaBridge? = null
    @Volatile private var watchdog: Thread? = null

    override fun close() {
        stopped.set(true)
        process?.let { runCatching { it.destroyForcibly() } }
        captcha?.close()
        watchdog?.interrupt()
    }

    fun run(context: Context, hashes: List<Pair<Int, String>>, captchaMode: String,
            anonPath: String, dns: String, onUpdate: (Int, String) -> Unit) {
        if (hashes.isEmpty() || stopped.get()) return
        require(hashes.all { it.second.matches(Regex("[A-Za-z0-9_-]{16,512}")) }) { "Некорректный хеш VK" }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libqwdtt.so")
        check(binary.canExecute()) { "Бинарный клиент не найден" }
        val child = ProcessBuilder(binary.absolutePath, "-check-hashes", "-vk", hashes.joinToString(",") { it.second },
            "-captcha-mode", captchaMode, "-vk-auth", "anonymous", "-vk-anon-path",
            if (anonPath == "legacy") "legacy" else "vkcalls", "-go-dns", dns,
            "-device-id", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")
            .directory(context.filesDir).redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }.start()
        process = child
        if (stopped.get()) { close(); return }
        val bridge = CarrierCaptchaBridge(child, onlyIfUiIdle = true)
        captcha = bridge
        if (stopped.get()) { close(); return }
        val finished = mutableSetOf<Int>()
        val timedOut = AtomicBoolean(false)
        val killer = Thread({
            try {
                if (!child.waitFor((hashes.size * 100_000L).coerceAtLeast(90_000L), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    timedOut.set(true)
                    child.destroyForcibly()
                    bridge.close()
                }
            } catch (_: InterruptedException) { }
        }, "qThrone-hash-check-timeout").apply { isDaemon = true }
        watchdog = killer
        if (stopped.get()) { close(); return }
        killer.start()
        try {
            child.inputStream.bufferedReader().use { reader ->
                while (!stopped.get()) {
                    val line = reader.readLine() ?: break
                    if (stopped.get()) break
                    bridge.accept(line)
                    HashCheckProtocol.parse(line,hashes)?.let {
                        if(it.finished) finished.add(it.slot)
                        onUpdate(it.slot,it.message)
                    }
                }
            }
            if (!stopped.get()) hashes.filter { it.first !in finished }.forEach {
                onUpdate(it.first, if (timedOut.get()) "Время проверки истекло" else "Нет ответа проверки")
            }
        } finally { close() }
    }
}
