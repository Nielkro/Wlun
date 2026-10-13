package app.qthrone.mobile.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import app.qthrone.mobile.R
import app.qthrone.mobile.bg.carrier.VkRelayAccount
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.ktx.needReload
import kotlinx.coroutines.*
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object VkRelayPreferences {
    fun bind(fragment: PreferenceFragmentCompat, group: PreferenceGroup) {
        val context = fragment.requireContext()
        group.removeAll()
        val linked = VkRelayAccount.linked()
        val account = object : Preference(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                (holder.findViewById(R.id.vk_unlink) as? ImageButton)?.setOnClickListener { button ->
                    button.isEnabled = false
                    fragment.lifecycleScope.launch {
                        val result = runCatching { VkRelayAccount.unlinkWithManualHashes() }
                        if(!fragment.isAdded) return@launch
                        if(result.isSuccess) {
                            val cookies = CookieManager.getInstance()
                            for(domain in listOf("https://vk.ru","https://vk.com","https://oauth.vk.ru","https://oauth.vk.com","https://login.vk.ru","https://login.vk.com","https://id.vk.ru")) {
                                cookies.getCookie(domain)?.split(';')?.forEach { cookie -> cookies.setCookie(domain,cookie.substringBefore('=').trim()+"=; Max-Age=0; Path=/") }
                            }
                            cookies.flush()
                            bind(fragment,group)
                            fragment.needReload()
                        } else {
                            button.isEnabled = true
                            Toast.makeText(context,"Не удалось отвязать аккаунт VK",Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.apply {
            key = "vkRelayAccount"
            title = if(linked) "Аккаунт VK привязан" else "Привязать аккаунт VK"
            isSingleLineTitle = false
            setIcon(R.drawable.ic_mobile_vk)
            if(linked) {
                summary = VkRelayAccount.displayName()
                widgetLayoutResource = R.layout.vk_relay_unlink
                isSelectable = false
            } else setOnPreferenceClickListener {
                fragment.startActivity(Intent(context,VkRelayActivity::class.java).putExtra("login",true)); true
            }
        }
        group.addPreference(account)
        group.addPreference(SimpleMenuPreference(context).apply {
            key = "vkRelayDefaultHashes"
            title = "Хеши по умолчанию для новых профилей"
            setIcon(R.drawable.ic_mobile_hash)
            isSingleLineTitle = false; isPersistent = false
            entries = arrayOf("Вручную","Авто"); entryValues = arrayOf("manual","auto")
            value = DataStore.configurationStore.getString(VkRelayAccount.DEFAULT_SOURCE,"manual")
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _,next -> DataStore.configurationStore.putString(VkRelayAccount.DEFAULT_SOURCE,next as String); true }
        })
        if(linked) fragment.lifecycleScope.launch {
            val avatar = withContext(Dispatchers.IO) {
                VkRelayAccount.refreshIdentity()
                runCatching {
                    val url = VkRelayAccount.avatarUrl()
                    if(!url.startsWith("https://")) return@runCatching null
                    val client = OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if(!response.isSuccessful) return@use null
                        val stream = response.body?.byteStream() ?: return@use null
                        val bytes = stream.readBytesBounded()
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                    }
                }.getOrNull()
            }
            if(fragment.isAdded && group.findPreference<Preference>("vkRelayAccount") === account && VkRelayAccount.linked()) {
                account.summary = VkRelayAccount.displayName()
                if(avatar != null) account.icon = RoundedBitmapDrawableFactory.create(context.resources,avatar).apply { isCircular = true }
            }
        }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while(true) {
            val count = read(buffer)
            if(count < 0) break
            check(output.size()+count <= 1024*1024)
            output.write(buffer,0,count)
        }
        return output.toByteArray()
    }
}
