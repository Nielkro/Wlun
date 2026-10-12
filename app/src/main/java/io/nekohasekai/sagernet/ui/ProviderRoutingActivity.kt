package app.qthrone.mobile.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import app.qthrone.mobile.R
import app.qthrone.mobile.database.*
import app.qthrone.mobile.ktx.needReload
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.route.ProviderRouting
import kotlinx.coroutines.*
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class ProviderRoutingActivity : ThemedActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.background) }
        val toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text); setNavigationIcon(R.drawable.baseline_arrow_back_24)
            setNavigationOnClickListener { finish() }
        }
        val content = FrameLayout(this).apply { id = android.R.id.content + 1 }
        root.addView(toolbar); root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        lifecycleScope.launch {
            val group = withContext(Dispatchers.IO) { GroupRepo.get(intent.getLongExtra("groupId",0)) }
            if (group == null) { finish(); return@launch }
            toolbar.title = getString(R.string.provider_routing_title,group.subOptions.routingProvider)
            if (state == null) supportFragmentManager.beginTransaction().replace(content.id,Screen().apply {
                arguments = Bundle().apply { putLong("groupId",group.id) }
            }).commit()
        }
    }

    class Screen : PreferenceFragmentCompat() {
        private var group: ProxyGroup? = null
        private var document = JsonObject()
        private val controls = mutableListOf<Preference>()
        private var saving: Job? = null
        override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            lifecycleScope.launch {
                group = withContext(Dispatchers.IO) { GroupRepo.get(requireArguments().getLong("groupId")) }
                if (group == null) return@launch
                build()
            }
        }
        private fun build() {
            val options = group!!.subOptions
            document = JsonInput.parseObjectOrNull(options.providerRouting) ?: JsonObject()
            preferenceScreen.removeAll(); controls.clear()
            val enabled = SwitchPreferenceCompat(requireContext()).apply {
                key = "providerEnabled"; isPersistent = false
                title = getString(R.string.provider_routing_use,options.routingProvider)
                summary = getString(R.string.provider_routing_use_summary)
                isChecked = options.providerRoutingEnabled
                setOnPreferenceChangeListener { _, value ->
                    options.providerRoutingEnabled = value as Boolean
                    controls.forEach { it.isVisible = value }
                    save(); true
                }
            }
            preferenceScreen.addPreference(enabled)
            fun add(preference: Preference) { preference.isPersistent = false; preference.isVisible = enabled.isChecked; controls.add(preference); preferenceScreen.addPreference(preference) }
            val profiles = (JsonInput.parseValue(options.providerRoutingProfiles) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            add(SimpleMenuPreference(requireContext()).apply {
                key = "providerProfile"; title = getString(R.string.provider_routing_profile)
                entries = profiles.map { it.string("Name").ifBlank { "Default" } }.toTypedArray()
                entryValues = profiles.indices.map { it.toString() }.toTypedArray()
                value = profiles.indexOfFirst { it.string("Name") == document.string("Name") }.coerceAtLeast(0).toString()
                summary = document.string("Name").ifBlank { "Default" }
                setOnPreferenceChangeListener { _, next ->
                    document = profiles[(next as String).toInt()].copy(); options.providerRouting = document.toCompact()
                    save(); build(); true
                }
                isEnabled = profiles.isNotEmpty()
            })
            fun toggle(keyName: String, label: Int, fallback: Boolean) = add(SwitchPreferenceCompat(requireContext()).apply {
                key = keyName; title = getString(label); isChecked = ProviderRouting.truth(document,keyName,fallback)
                setOnPreferenceChangeListener { _, next -> document[keyName] = (next as Boolean).toString(); update(); true }
            })
            toggle("GlobalProxy",R.string.provider_routing_global,true)
            toggle("FakeDNS",R.string.provider_routing_fake_dns,false)
            fun choice(keyName: String,label: Int,values: Array<String>,fallback: String) = add(SimpleMenuPreference(requireContext()).apply {
                key = keyName; title = getString(label); entries = values; entryValues = values
                value = document.string(keyName).ifBlank { fallback }; summary = value
                setOnPreferenceChangeListener { _, next -> document[keyName] = next as String; summary = next; update(); true }
            })
            choice("DomainStrategy",R.string.provider_routing_strategy,arrayOf("AsIs","IPIfNonMatch","IPOnDemand"),"AsIs")
            choice("RemoteDNSType",R.string.provider_routing_remote_type,arrayOf("DoH","DoU"),"DoH")
            choice("DomesticDNSType",R.string.provider_routing_direct_type,arrayOf("DoH","DoU"),"DoU")
            fun field(keyName: String,label: Int,default: String = "",array: Boolean = false,hosts: Boolean = false) = add(EditTextPreference(requireContext()).apply {
                key = keyName; title = getString(label)
                text = when { array -> document.array(keyName).strings().joinToString("\n"); hosts -> document.obj(keyName).toCompact(); else -> document.string(keyName).ifBlank { default } }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or if (array || hosts) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_URI }
                setOnPreferenceChangeListener { _, next ->
                    val candidate = document.copy()
                    candidate[keyName] = when { array -> JsonArray().apply { (next as String).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) } }; hosts -> JsonInput.parseObjectOrNull(next as String) ?: return@setOnPreferenceChangeListener false; else -> next as String }
                    if (runCatching { ProviderRouting.profile(candidate,options.routingProvider) }.isFailure) return@setOnPreferenceChangeListener false
                    document = candidate; update(); true
                }
            })
            field("RemoteDNSDomain",R.string.provider_routing_remote_domain,"https://cloudflare-dns.com/dns-query")
            field("RemoteDNSIP",R.string.provider_routing_remote_ip,"1.1.1.1")
            field("DomesticDNSDomain",R.string.provider_routing_direct_domain)
            field("DomesticDNSIP",R.string.provider_routing_direct_ip,"8.8.8.8")
            field("DnsHosts",R.string.provider_routing_hosts,hosts = true)
            field("Geoipurl",R.string.provider_routing_geoip,"https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat")
            field("Geositeurl",R.string.provider_routing_geosite,"https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat")
            for ((key,label) in listOf("DirectSites" to R.string.provider_routing_direct_sites,"DirectIp" to R.string.provider_routing_direct_ips,"ProxySites" to R.string.provider_routing_proxy_sites,"ProxyIp" to R.string.provider_routing_proxy_ips,"BlockSites" to R.string.provider_routing_block_sites,"BlockIp" to R.string.provider_routing_block_ips)) field(key,label,array = true)
        }
        private fun update() {
            val options = group!!.subOptions
            val previous = JsonInput.parseObjectOrNull(options.providerRouting)
            val profiles = (JsonInput.parseValue(options.providerRoutingProfiles) as? JsonArray)?.filterIsInstance<JsonObject>()?.toMutableList() ?: mutableListOf()
            val index = profiles.indexOfFirst { it.string("Name") == previous?.string("Name") }
            if (index >= 0) profiles[index] = document.copy() else profiles.add(document.copy())
            options.providerRoutingProfiles = JsonArray().apply { profiles.forEach { add(it) } }.toCompact()
            options.providerRouting = document.toCompact(); save()
        }
        private fun save() {
            val gid = group!!.id; val options = group!!.subOptions.copy()
            saving = lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    SagerDatabase.instance.runInTransaction {
                        val latest = GroupRepo.get(gid) ?: return@runInTransaction
                        latest.subOptions.routingProvider = options.routingProvider
                        latest.subOptions.providerRoutingEnabled = options.providerRoutingEnabled
                        latest.subOptions.providerRouting = options.providerRouting
                        latest.subOptions.providerRoutingProfiles = options.providerRoutingProfiles
                        SagerDatabase.groupDao.update(latest)
                    }
                }
                needReload()
            }
        }
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is EditTextPreference) MobilePreferenceDialog().apply {
                arguments = Bundle().apply { putString("key",preference.key) }; setTargetFragment(this@Screen,0)
            }.show(parentFragmentManager,"provider-input") else super.onDisplayPreferenceDialog(preference)
        }
    }
}
