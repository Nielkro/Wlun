package app.qthrone.mobile.bg.carrier

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.outbound.types.Qwdtt
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account credentials stay outside profiles, exports, backups and logs, shared with :bg. */
object VkRelayAccount {
    const val DEFAULT_SOURCE = "vkRelayDefaultHashSource"
    private const val ALIAS = "qThrone.vk.relay"
    private fun storage() = AtomicFile(File(app.noBackupFilesDir,"vk-relay-account"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun read(): JSONObject = runCatching {
        val bytes = storage().readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }.getOrElse { JSONObject() }
    @Synchronized private fun write(json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val file = storage(); val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch(error: Exception) { file.failWrite(output); throw error }
    }
    fun linked() = read().optString("token").isNotBlank()
    fun userId() = read().optString("user")
    fun displayName() = read().optString("name").ifBlank { "VK ID: ${userId()}" }
    fun avatarUrl() = read().optString("avatar")
    @Synchronized fun refreshIdentity() {
        val session = read()
        if(session.optString("token").isBlank() || session.optString("name").isNotBlank()) return
        runCatching {
            val request = Request.Builder().url("https://api.vk.ru/method/users.get")
                .post(FormBody.Builder().add("access_token",session.getString("token")).add("v","5.199").add("fields","photo_100").build()).build()
            val person = client.newCall(request).execute().use { response ->
                check(response.isSuccessful)
                JSONObject(response.body?.string().orEmpty()).getJSONArray("response").getJSONObject(0)
            }
            session.put("name",listOf(person.optString("first_name"),person.optString("last_name")).filter { it.isNotBlank() }.joinToString(" "))
            session.put("avatar",person.optString("photo_100"))
            write(session)
        }
    }
    suspend fun unlinkWithManualHashes() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val groups = mutableSetOf<Long>()
            val db = SagerDatabase.instance
            db.runInTransaction {
                for(profile in SagerDatabase.proxyDao.getAll()) {
                    val outbound = profile.outbound as? Qwdtt ?: continue
                    outbound.hashSource = "manual"
                    outbound.hashSourceChosen = true
                    profile.putOutbound(outbound)
                    SagerDatabase.proxyDao.updateOutbound(profile.id,profile.type,profile.name,profile.outboundJson)
                    groups.add(profile.groupId)
                }
            }
            DataStore.configurationStore.putString(DEFAULT_SOURCE,"manual")
            unlink()
            for(group in groups) GroupRepo.postReload(group)
        }
    }
    fun link(token: String,user: String,expiresIn: Long) {
        require(token.isNotBlank())
        write(JSONObject().put("token",token).put("user",user)
            .put("expires",if(expiresIn > 0) System.currentTimeMillis()+expiresIn*1000 else Long.MAX_VALUE))
        refreshIdentity()
    }
    @Synchronized fun unlink() { storage().delete() }
    private val client = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    @Synchronized fun hashes(count: Int): List<String> {
        require(count in 1..6)
        val session = read(); val token = session.optString("token")
        check(token.isNotBlank() && session.optLong("expires") > System.currentTimeMillis()) { "Войдите в аккаунт VK в настройках VK-релея" }
        val cached = session.optJSONArray("hashes")
        if(cached != null && cached.length() >= count) return (0 until count).map { cached.getString(it) }
        val hashes = mutableListOf<String>()
        if(cached != null) for(i in 0 until cached.length()) hashes.add(cached.getString(i))
        while(hashes.size < count) {
            if(hashes.isNotEmpty()) Thread.sleep(2000)
            val request = Request.Builder().url("https://api.vk.ru/method/calls.start")
                .post(FormBody.Builder().add("access_token",token).add("v","5.199").build()).build()
            val json = client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Не удалось создать звонок VK: HTTP ${response.code}" }
                JSONObject(response.body?.string() ?: error("Пустой ответ VK"))
            }
            val vkError = json.optJSONObject("error")
            if(vkError != null) {
                if(vkError.optInt("error_code") in listOf(4,5,27,28)) unlink()
                error("Не удалось создать звонок VK: код ${vkError.optInt("error_code")}")
            }
            val call = json.optJSONObject("response") ?: error("VK не вернул ссылку звонка")
            val hash = call.optString("join_link").substringAfterLast('/').substringBefore('?').substringBefore('#')
            check(hash.length >= 16 && hash.none { it.isWhitespace() || it == '|' || it == ',' }) { "VK вернул некорректный хеш" }
            hashes.add(hash)
            session.put("hashes",org.json.JSONArray(hashes)); write(session)
        }
        return hashes.take(count)
    }
}
