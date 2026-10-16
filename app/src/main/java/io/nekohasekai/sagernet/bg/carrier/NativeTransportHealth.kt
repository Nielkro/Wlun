package app.qthrone.mobile.bg.carrier

enum class NativeConnectionStage { PREPARING, AUTHORIZING, CAPTCHA, WAITING_VK, CONNECTING, CONNECTED, RECONNECTING }

/** Only public lifecycle markers are kept; URLs, hashes and captcha tokens are never status text. */
internal class NativeTransportHealth(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var zeroSince = clock()
    private var workers: Int? = null
    var transientFailure = false
        private set
    var credentialsFailed = false
        private set
    private var stage = NativeConnectionStage.PREPARING
    private val active = Regex("(?:Активных:\\s*|\\bactive=)([0-9]+)", RegexOption.IGNORE_CASE)

    @Synchronized fun accept(line: String): NativeConnectionStage {
        active.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { count ->
            if (count == 0 && workers != 0) zeroSince = clock()
            workers = count
        }
        if (line.contains("[READY]") || line.contains("[CSQTT diagnostic] READY")) workers = maxOf(1, workers ?: 0)
        val flood = line.contains("Flood control", true) || Regex("error_code[\\s:=]+9\\b").containsMatchIn(line)
        if (flood || line.contains("captcha", true) || line.contains("капч", true) ||
            line.contains("timed out", true) || line.contains("timeout", true) ||
            line.contains("connection reset", true) || line.contains("network is unreachable", true)) transientFailure = true
        if (line.contains("Ошибка кредов", true)) credentialsFailed = true
        stage = when {
            (workers ?: 0) > 0 -> NativeConnectionStage.CONNECTED
            flood -> NativeConnectionStage.WAITING_VK
            line.startsWith("CAPTCHA_SOLVE|") || line.contains("решение капчи", true) -> NativeConnectionStage.CAPTCHA
            line.contains("Запрос кредов", true) || line.contains("VK Auth", true) -> NativeConnectionStage.AUTHORIZING
            line.contains("TURN TCP") || line.contains("TURN UDP") || line.contains("Креды OK") -> NativeConnectionStage.CONNECTING
            workers == 0 -> NativeConnectionStage.RECONNECTING
            else -> stage
        }
        return stage
    }

    @Synchronized fun needsRestart(captchaWaiting: Boolean, starting: Boolean): Boolean {
        if (captchaWaiting) { zeroSince = clock(); return false }
        if ((workers ?: 0) > 0) { zeroSince = clock(); return false }
        // Like the original Android watchdog: never restart just because no user traffic flows.
        return clock() - zeroSince >= 90_000 &&
            (workers == 0 || (starting && credentialsFailed && transientFailure))
    }
}
