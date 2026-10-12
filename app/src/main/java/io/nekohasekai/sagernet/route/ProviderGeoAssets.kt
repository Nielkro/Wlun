package app.qthrone.mobile.route

import app.qthrone.mobile.ktx.*
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.core.mobile.Mobile
import java.io.File
import java.security.MessageDigest

object ProviderGeoAssets {
    @Synchronized
    fun resolve(profile: RouteProfile, document: JsonObject, catalog: RuleSetCatalog): RuleSetCatalog {
        val entries = catalog.names().map { it to catalog.urlOf(it)!! }.toMutableList()
        val dir = File(app.filesDir, "provider-geodata").apply { mkdirs() }
        for (rule in profile.rules) {
            for (i in rule.rule_set.indices) {
                val selector = rule.rule_set[i]
                val kind = selector.substringBefore(':')
                if (kind != "geosite" && kind != "geoip") continue
                val url = document.string(if (kind == "geoip") "Geoipurl" else "Geositeurl").ifBlank {
                    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/$kind.dat"
                }
                require(url.startsWith("https://") || url.startsWith("http://")) { "Invalid geo file URL" }
                val sourceId = digest(url)
                val source = File(dir, "$sourceId.dat")
                if (!source.isFile || System.currentTimeMillis() - source.lastModified() > 7 * 86400000L) {
                    val response = httpGet(url, HttpGetOptions(maxBytes = 64L * 1024 * 1024))
                    check(response.ok) { "Cannot download $kind: ${response.error}" }
                    val temp = File(dir, "$sourceId.download")
                    temp.writeBytes(response.data)
                    check(temp.renameTo(source)) { "Cannot save geo file" }
                }
                val key = digest("$url/$selector/${source.lastModified()}")
                val converted = File(dir, "$key.srs")
                if (!converted.isFile) {
                    val temp = File(dir, "$key.tmp")
                    Mobile.convertGeoRuleSet(source.absolutePath, temp.absolutePath, kind, selector.substringAfter(':'))
                    check(temp.renameTo(converted)) { "Cannot save routing rules" }
                }
                val tag = "provider-$key"
                rule.rule_set[i] = tag
                entries.add(tag to "file://${converted.absolutePath}")
            }
        }
        return RuleSetCatalog(entries)
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
