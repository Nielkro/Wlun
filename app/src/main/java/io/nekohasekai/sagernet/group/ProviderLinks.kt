package app.qthrone.mobile.group

import java.net.URLDecoder

object ProviderLinks {
    data class Link(val provider: String, val data: String, val encrypted: Boolean = false, val name: String = "", val format: String = "")
    private val pattern = Regex("^(happ|incy)://([^/]+)/(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** A failed decode is null for callers that only classify clipboard contents. */
    fun decode(text: String): Link? = runCatching { decodeChecked(text) }.getOrNull()

    /** Throws without including secret URLs or payloads in the error message. */
    fun decodeChecked(text: String): Link? {
        var content = text.trim()
        var encrypted = false
        var provider = ""
        var name = ""
        var format = ""
        repeat(8) {
            val match = pattern.matchEntire(content)
                ?: return if (provider.isEmpty()) null else Link(provider, content, encrypted, name, format)
            require(content.length <= SubscriptionCrypto.MAX_LINK_CHARS) { "Encrypted subscription is too large" }
            val scheme = match.groupValues[1].lowercase()
            val action = match.groupValues[2].lowercase()
            provider = if (scheme == "incy") "Incy" else "Happ"
            var payload = match.groupValues[3]
            if (action in listOf("add", "import")) {
                if (!payload.contains("://")) payload = URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
                content = payload
            } else if (action.startsWith("crypt")) {
                val decoded = try {
                    SubscriptionCrypto.decrypt(scheme, action, URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8"))
                } catch (_: Exception) {
                    throw IllegalArgumentException("Cannot decrypt subscription: unsupported format or damaged data")
                }
                content = decoded.content.trim()
                encrypted = true
                format = action
                if (decoded.name.isNotEmpty()) name = decoded.name
            } else return null
        }
        throw IllegalArgumentException("Subscription wrapper nesting limit exceeded")
    }

    /** Syntactic classification only: settings and menus must never perform RSA on the UI thread. */
    fun isEncrypted(text: String): Boolean {
        var content = text.trim()
        if (content.length > SubscriptionCrypto.MAX_LINK_CHARS) return false
        repeat(8) {
            val match = pattern.matchEntire(content) ?: return false
            val action = match.groupValues[2].lowercase()
            if (action.startsWith("crypt")) return true
            if (action !in listOf("add", "import")) return false
            content = match.groupValues[3]
            if (!content.contains("://")) content = runCatching { URLDecoder.decode(content.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return false
        }
        return false
    }

    fun isProviderLink(text: String): Boolean = pattern.matches(text.trim())

    fun subscription(text: String): Link? = decode(text)?.takeIf { SubscriptionCrypto.isHttpUrl(it.data) }
}
