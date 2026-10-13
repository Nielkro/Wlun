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
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import app.qthrone.mobile.ui.AnchoredTextPreference

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
            for((key,value) in optionDefaults)
                if(!options.contains(key)) options[key]=value
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
        val order = when(type) {
            "qwdtt" -> listOf("name","server","server_port","mode","password") + (1..4).map { "hash_$it" } + listOf("raw_port","workers","turn_tcp","vk_anon_path","captcha_mode","obfs","go_dns","device_id")
            "csqtt" -> listOf("name","server","server_port","password") + (1..6).map { "hash_$it" } + listOf("workers","turn_tcp","vk_anon_path","captcha_mode","obfs","device_id")
            "openflux" -> listOf("name","secret","transport_yandex","transport_mailru","transport_cupsonline","context")
            else -> listOf("name","provider","transport","room","key","token","channel","dns","device_id") + optionDefaults.keys.map { "options.$it" }
        }
        if(type in listOf("qwdtt","csqtt")) {
            val hashes=document.array("hashes").strings()
            for(i in 1..if(type=="csqtt") 6 else 4) {
                fields["hash_$i"]=hashes.getOrElse(i-1) { "" }
                DataStore.profileCacheStore.putString(cache("hash_$i"),hashes.getOrElse(i-1) { "" })
            }
        }
        if(type=="openflux") for(kind in listOf("yandex","mailru","cupsonline")) {
            val value=document.array("transports").filterIsInstance<JsonObject>().firstOrNull { it.string("type")==kind }?.string("url").orEmpty()
            fields["transport_$kind"]=value
            DataStore.profileCacheStore.putString(cache("transport_$kind"),value)
        }
        if(!fields.containsKey("name")) {
            fields["name"]=name
            DataStore.profileCacheStore.putString(cache("name"),name)
        }
        val ordered=order.mapNotNull { path -> fields[path]?.let { path to it } }
        fields.clear()
        fields.putAll(ordered)

    }

    override fun Outbound.serialize() {
        val result = document.copy()
        for ((path, original) in fields) {
            if(path.startsWith("transport_") || path.startsWith("hash_")) continue
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
        if(type in listOf("qwdtt","csqtt")) {
            result["hashes"]=JsonArray.of(*fields.keys.filter { it.startsWith("hash_") }.map { DataStore.profileCacheStore.getString(cache(it))?.trim().orEmpty() }.filter { it.isNotEmpty() }.toTypedArray())
        }
        if(type=="olcrtc") {
            val transport=DataStore.profileCacheStore.getString(cache("transport")).orEmpty()
            val options=result.obj("options")
            for(key in optionDefaults.keys) if(!optionVisible(key,transport)) options.remove(key)
        }
        if(type=="openflux") {
            val transports=JsonArray()
            document.array("transports").filterIsInstance<JsonObject>().filter { it.string("type")=="direct" }.forEach { transports.add(it.copy()) }
            for((index,kind) in listOf("yandex","mailru","cupsonline").withIndex()) {
                val value=DataStore.profileCacheStore.getString(cache("transport_$kind"))?.trim().orEmpty()
                if(value.isEmpty()) continue
                val transport=document.array("transports").filterIsInstance<JsonObject>().firstOrNull { it.string("type")==kind }?.copy()
                    ?: jsonObjectOf("type" to kind,"name" to kind,"priority" to index)
                transport[if(kind=="direct") "dial" else "url"]=value
                transports.add(transport)
            }
            result["transports"]=transports
            if(result.string("context").isBlank()) result["context"]=transports.filterIsInstance<JsonObject>().firstNotNullOfOrNull { it.string("url").takeIf(String::isNotBlank) } ?: "http://openflux"
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
            "workers" to "Воркеры", "turn_tcp" to "Транспорт TURN", "mode" to "Режим",
            "raw_port" to "Порт RAW", "obfs" to "Маскировка", "go_dns" to "DNS VK",
            "device_id" to "Идентификатор устройства", "vk_anon_path" to "Авторизация VK", "captcha_mode" to "Режим капчи",
            "secret" to "Секрет", "context" to "Контекст", "codec" to "Кодек",
            "negotiate" to "Согласование транспорта", "transports" to "Транспорты (JSON)",
            "provider" to "Сервис", "transport" to "Транспорт", "room" to "Комната / ссылка",
            "key" to "Ключ (64 HEX)", "token" to "Токен сервиса", "channel" to "Канал комнаты", "dns" to "DNS",
            "vp8-fps" to "VP8: кадров/с", "vp8-batch" to "VP8: кадров в пакете", "fps" to "SEI: кадров/с", "batch" to "SEI: кадров в пакете",
            "frag" to "SEI: размер фрагмента", "ack-ms" to "SEI: ожидание ACK, мс", "video-w" to "Видео: ширина", "video-h" to "Видео: высота",
            "video-fps" to "Видео: кадров/с", "video-qr-size" to "QR: размер фрагмента (0 — авто)", "video-tile-module" to "Tile: размер модуля",
            "video-tile-rs" to "Tile: восстановление, %", "video-codec" to "Кодек видео", "video-qr-recovery" to "Восстановление QR")
        val choices = mapOf("mode" to listOf("raw","wg"), "obfs" to listOf("audio","video"),
            "vk_anon_path" to listOf("vkcalls","legacy"), "captcha_mode" to listOf("auto","wv","rjs"),
            "provider" to listOf("jitsi","telemost","wbstream"),
            "transport" to listOf("datachannel","vp8channel","seichannel","videochannel"),
            "turn_tcp" to listOf("false","true"), "video-codec" to listOf("qrcode","tile"),
            "video-qr-recovery" to listOf("low","medium","high","highest"))
        val captions=mapOf("mode" to listOf("RAW","WG"),"turn_tcp" to listOf("UDP","TCP"),
            "vk_anon_path" to listOf("Звонок VK","Капча"),"captcha_mode" to listOf("Автоматически, с переходом в браузер","Окно браузера","Автоматически"),
            "provider" to listOf("Jitsi","Телемост","WBStream"),"obfs" to listOf("Аудио","Видео"))
        val editors=linkedMapOf<String,Preference>()
        fun refreshEditors() {
            editors["raw_port"]?.isEnabled=DataStore.profileCacheStore.getString(cache("mode"))!="wg"
            if(editingOutbound.type in listOf("qwdtt","csqtt")) {
                val count=editors.keys.filter { it.startsWith("hash_") }.map { DataStore.profileCacheStore.getString(cache(it))?.trim().orEmpty() }.filter { it.isNotEmpty() }.distinct().size.coerceAtLeast(1)
                val max=(count*27).coerceAtMost(if(editingOutbound.type=="csqtt") 126 else 108)
                (editors["workers"] as? ListPreference)?.apply {
                    entries=(9..max step 9).map(Int::toString).toTypedArray();entryValues=entries
                    val selected=(DataStore.profileCacheStore.getString(cache("workers"))?.toIntOrNull() ?: 9).coerceIn(9,max)/9*9
                    value=selected.toString()
                }
            }
            val transport=DataStore.profileCacheStore.getString(cache("transport")).orEmpty()
            for((path,preference) in editors) if(path.startsWith("options.")) {
                val key=path.substringAfter('.')
                if(key in listOf("video-codec","video-qr-recovery")) { preference.isVisible=true;preference.isEnabled=transport=="videochannel" }
                else preference.isVisible=optionVisible(key,transport)
            }
        }
        for ((path, value) in fields) {
            val key = path.substringAfterLast('.')
            val preference: Preference = when {
                value is Boolean && key!="turn_tcp" -> SwitchPreference(requireContext()).apply {
                    isPersistent = false
                    isChecked = DataStore.profileCacheStore.getString(cache(path)).toBoolean()
                    setOnPreferenceChangeListener { _, next -> DataStore.profileCacheStore.putString(cache(path), next.toString()); true }
                }
                choices.containsKey(key) || key=="workers" -> SimpleMenuPreference(requireContext()).apply {
                    entryValues = (choices[key] ?: listOf("9")).toTypedArray()
                    entries = (captions[key] ?: choices[key] ?: listOf("9")).toTypedArray()
                    this.value=DataStore.profileCacheStore.getString(cache(path))
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }
                else -> (if(path in listOf("go_dns","dns")) AnchoredTextPreference(requireContext()) else EditTextPreference(requireContext())).apply {
                    summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                    positiveButtonText=getString(android.R.string.ok)
                    negativeButtonText=getString(android.R.string.cancel)
                    setOnBindEditTextListener {
                        it.inputType = when {
                            value is Number -> InputType.TYPE_CLASS_NUMBER
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
                path.startsWith("hash_") -> "Хэш VK " + path.removePrefix("hash_")
                path=="transport_yandex" -> "Документ Яндекс"
                path=="transport_mailru" -> "Документ Mail.ru"
                path=="transport_cupsonline" -> "Документ Cups.online"
                else -> labels[key] ?: path.removePrefix("options.")
            }
            preference.setOnPreferenceChangeListener { _, next ->
                val text=next.toString()
                val range=optionLimits[path.removePrefix("options.")]
                if(value is Number && text.toLongOrNull()==null) return@setOnPreferenceChangeListener false
                if(range!=null && text.toIntOrNull()?.let { it in range }!=true) return@setOnPreferenceChangeListener false
                if(key in listOf("server_port","raw_port") && text.toIntOrNull()?.let { it in 1..65535 }!=true) return@setOnPreferenceChangeListener false
                DataStore.profileCacheStore.putString(cache(path),text)
                if(path=="provider") {
                    val current=DataStore.profileCacheStore.getString(cache("transport"))
                    if((text=="telemost" && current in listOf("datachannel","seichannel")) || (text=="wbstream" && current=="datachannel")) {
                        DataStore.profileCacheStore.putString(cache("transport"),"vp8channel")
                        (editors["transport"] as? ListPreference)?.value="vp8channel"
                    }
                }
                refreshEditors()
                true
            }
            if(path in listOf("go_dns","dns")) (preference as AnchoredTextPreference).onSelect = { anchor ->
                val values=(if(path=="go_dns") listOf("yandex","cloudflare","google","doh-yandex","doh-cloudflare","doh-google")
                    else listOf("8.8.8.8:53","1.1.1.1:53","77.88.8.8:53")).toMutableList()
                val current=DataStore.profileCacheStore.getString(cache(path)).orEmpty()
                if(current.isNotEmpty() && current !in values) values.add(current)
                values.add("Ввести вручную")
                app.qthrone.mobile.ui.MobileSelector.showAt(anchor, values,
                    values.indexOf(DataStore.profileCacheStore.getString(cache(path)))) { index ->
                    if(index==values.lastIndex) onDisplayPreferenceDialog(preference)
                    else if(preference.callChangeListener(values[index])) preference.text=values[index]
                }
            }
            editors[path]=preference
            preferenceScreen.addPreference(preference)
        }
        refreshEditors()
        if(editingOutbound.type=="olcrtc") preferenceScreen.addPreference(Preference(requireContext()).apply {
            summary="Только TCP. DNS внутри туннеля использует TCP. Параметры транспорта должны совпадать с сервером."
            isSelectable=false
        })
    }

    private val optionDefaults=linkedMapOf<String,Any>("vp8-fps" to 60,"vp8-batch" to 64,"fps" to 30,"batch" to 64,"frag" to 900,"ack-ms" to 2000,
        "video-w" to 1080,"video-h" to 1080,"video-fps" to 30,"video-qr-size" to 0,"video-tile-module" to 4,"video-tile-rs" to 0,"video-codec" to "qrcode","video-qr-recovery" to "low")
    private val optionLimits=mapOf("vp8-fps" to 1..120,"vp8-batch" to 1..64,"fps" to 1..120,"batch" to 1..64,"frag" to 1..65535,"ack-ms" to 1..60000,
        "video-w" to 1..4096,"video-h" to 1..4096,"video-fps" to 1..120,"video-qr-size" to 0..65535,"video-tile-module" to 1..270,"video-tile-rs" to 0..200)
    private fun optionVisible(key:String,transport:String)=when(transport) {
        "videochannel" -> key.startsWith("video-")
        "vp8channel" -> key.startsWith("vp8-")
        "seichannel" -> !key.contains('-') || key=="ack-ms"
        else -> false
    }
}
