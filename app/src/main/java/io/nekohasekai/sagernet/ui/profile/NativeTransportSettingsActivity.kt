package app.qthrone.mobile.ui.profile

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.*
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.bg.carrier.NativeCarrier
import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Typed controls edit the exported document, preserving unrecognized members. */
class NativeTransportSettingsActivity : ProfileSettingsActivity<Outbound>() {
    override fun createEntity(): Outbound = OutboundFactory.newByType(intent.getStringExtra("qmobileProfileType") ?: proxyEntity?.type ?: "qwdtt")
    private fun cache(path: String) = "native.$path"
    private var document = JsonObject()
    private val fields = linkedMapOf<String, Any>()

    override fun Outbound.init() {
        document = exportToJson().copy()
        fields.clear()
        if(type == "olcrtc") {
            for((key,value) in mapOf("provider" to "jitsi","transport" to "datachannel","room" to "","key" to "","token" to "","channel" to "","dns" to "8.8.8.8:53"))
                if(!document.contains(key)) document[key]=value
            val options=document.obj("options")
            for(key in listOf("vp8-fps","vp8-batch","fps","batch","frag","ack-ms","video-w","video-h","video-fps","video-qr-size","video-tile-module","video-tile-rs","video-codec","video-qr-recovery"))
                if(!options.contains(key)) options[key]=""
            document["options"]=options
        }
        if(type == "openflux") {
            for((key,value) in mapOf("secret" to "","context" to "http://openflux","codec" to "batched","negotiate" to true))
                if(!document.contains(key)) document[key]=value
        }
        fun write(obj: JsonObject, prefix: String = "") {
            for ((key, value) in obj) {
                if (key == "type") continue
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                if (value is JsonObject) write(value, path)
                else {
                    fields[path] = value
                    DataStore.profileCacheStore.putString(cache(path), when(value) {
                        is JsonArray -> if(key == "hashes") value.strings().joinToString("\n") else value.toCompact()
                        else -> value.toString()
                    })
                }
            }
        }
        write(document)
        if(type == "openflux") {
            fields.remove("transports")
            for(kind in listOf("direct","yandex","mailru","cupsonline")) {
                val transport=document.array("transports").filterIsInstance<JsonObject>().firstOrNull { it.string("type")==kind }
                val path="transport_$kind"
                val value=transport?.string(if(kind=="direct") "dial" else "url") ?: ""
                fields[path]=value
                DataStore.profileCacheStore.putString(cache(path),value)
            }
        }
    }

    override fun Outbound.serialize() {
        val result = document.copy()
        for ((path, original) in fields) {
            if(path.startsWith("transport_")) continue
            val parts = path.split('.')
            var obj = result
            for (part in parts.dropLast(1)) obj = obj.obj(part)
            val key = parts.last()
            val text = DataStore.profileCacheStore.getString(cache(path)) ?: ""
            if(path.startsWith("options.") && text.isBlank()) { obj.remove(key); continue }
            obj[key] = when(original) {
                is Boolean -> text.toBooleanStrict()
                is Number -> text.toLong()
                is JsonArray -> if(key == "hashes") JsonArray.of(*text.split(Regex("[,\\s]+" )).filter { it.isNotBlank() }.toTypedArray())
                    else JsonInput.parseValue(text) as? JsonArray ?: error("$key: JSON array required")
                else -> text
            }
        }
        if(type=="openflux") {
            val transports=JsonArray()
            for((index,kind) in listOf("direct","yandex","mailru","cupsonline").withIndex()) {
                val value=DataStore.profileCacheStore.getString(cache("transport_$kind"))?.trim().orEmpty()
                if(value.isEmpty()) continue
                val transport=document.array("transports").filterIsInstance<JsonObject>().firstOrNull { it.string("type")==kind }?.copy()
                    ?: jsonObjectOf("type" to kind,"name" to kind,"priority" to index)
                transport[if(kind=="direct") "dial" else "url"]=value
                transports.add(transport)
            }
            result["transports"]=transports
        }
        val candidate = OutboundFactory.newByType(type)
        require(candidate.parseFromJson(result)) { "Проверьте параметры профиля" }
        (candidate as NativeCarrier).privateConfig(1080,1081,CarrierBridgeCredentials("validation","validation"))
        require(parseFromJson(result))
    }

    override suspend fun saveAndExit() {
        try { super.saveAndExit() }
        catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) { Toast.makeText(this@NativeTransportSettingsActivity, e.message ?: "Проверьте параметры профиля", Toast.LENGTH_LONG).show() }
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val labels = mapOf("tag" to "Название", "name" to "Название", "server" to "Адрес сервера",
            "server_port" to "Порт сервера", "password" to "Пароль", "hashes" to "Хэши VK",
            "workers" to "Число работников", "turn_tcp" to "TURN через TCP", "mode" to "Режим",
            "raw_port" to "Порт RAW", "obfs" to "Маскировка", "go_dns" to "DNS",
            "device_id" to "Идентификатор устройства", "vk_anon_path" to "API VK", "captcha_mode" to "Режим капчи",
            "secret" to "Секрет", "context" to "Контекст", "codec" to "Кодек",
            "negotiate" to "Согласование транспорта", "transports" to "Транспорты (JSON)",
            "provider" to "Провайдер", "transport" to "Транспорт", "room" to "Комната",
            "key" to "Ключ", "token" to "Токен", "channel" to "Канал", "dns" to "DNS")
        val choices = mapOf("mode" to listOf("raw","wg"), "obfs" to listOf("audio","video"),
            "vk_anon_path" to listOf("vkcalls","legacy"), "captcha_mode" to listOf("auto","wv","rjs"),
            "provider" to listOf("jitsi","telemost","wbstream"),
            "transport" to listOf("datachannel","vp8channel","seichannel","videochannel"), "codec" to listOf("batched","legacy"))
        for ((path, value) in fields) {
            val key = path.substringAfterLast('.')
            val preference: Preference = when {
                value is Boolean -> SwitchPreference(requireContext()).apply {
                    isPersistent = false
                    isChecked = DataStore.profileCacheStore.getString(cache(path)).toBoolean()
                    setOnPreferenceChangeListener { _, next -> DataStore.profileCacheStore.putString(cache(path), next.toString()); true }
                }
                choices.containsKey(key) -> ListPreference(requireContext()).apply {
                    entries = choices.getValue(key).toTypedArray()
                    entryValues = entries
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }
                else -> EditTextPreference(requireContext()).apply {
                    summaryProvider = if(key in listOf("password","secret","key","token")) Preference.SummaryProvider<EditTextPreference> { "••••••" }
                        else EditTextPreference.SimpleSummaryProvider.getInstance()
                    setOnBindEditTextListener {
                        it.inputType = when {
                            value is Number -> InputType.TYPE_CLASS_NUMBER
                            key in listOf("password","secret","key","token") -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            key == "hashes" || value is JsonArray -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                    }
                    setOnPreferenceChangeListener { _, next ->
                        val text = next.toString()
                        when {
                            value is Number -> text.toLongOrNull() != null
                            value is JsonArray && key != "hashes" -> JsonInput.parseValue(text) is JsonArray
                            else -> true
                        }
                    }
                }
            }
            preference.key = cache(path)
            preference.title = when {
                path=="transport_direct" -> "Сервер (адрес:порт)"
                path.startsWith("transport_") -> path.removePrefix("transport_") + " URL"
                else -> labels[key] ?: path.removePrefix("options.")
            }
            preferenceScreen.addPreference(preference)
        }
    }
}
