package app.qthrone.mobile.ui

import android.os.Bundle
import android.content.Intent
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            toolbar.title = if (intent.getBooleanExtra("details",false)) getString(R.string.provider_rules_title) else getString(R.string.provider_routing_title,group.subOptions.routingProvider)
            if (state == null) supportFragmentManager.beginTransaction().replace(content.id,Screen().apply {
                arguments = Bundle().apply { putLong("groupId",group.id); putBoolean("details",intent.getBooleanExtra("details",false)) }
            }).commit()
        }
    }

    class Screen : PreferenceFragmentCompat() {
        private var group: ProxyGroup? = null
        private var document = JsonObject()
        private val controls = mutableListOf<Preference>()
        private var saving: Job? = null
        private val saveLock = Mutex()
        private var fullJson = false
        override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            lifecycleScope.launch {
                group = withContext(Dispatchers.IO) {
                    val gid = requireArguments().getLong("groupId")
                    val profiles = SagerDatabase.proxyDao.getByGroup(gid)
                    fullJson = profiles.isNotEmpty() && profiles.all { it.outbound.isXrayFullConfig() }
                    GroupRepo.get(gid)
                }
                if (group == null) return@launch
                build()
            }
        }
        private fun build() {
            val options = group!!.subOptions
            document = JsonInput.parseObjectOrNull(options.providerRouting) ?: JsonObject()
            preferenceScreen.removeAll(); controls.clear()
            val details = requireArguments().getBoolean("details")
            fun category(label: Int) { preferenceScreen.addPreference(PreferenceCategory(requireContext()).apply { title = getString(label) }) }
            if (!details) {
            category(R.string.provider_general)
            preferenceScreen.addPreference(Preference(requireContext()).apply { title = getString(R.string.provider_subscription_name); summary = group!!.displayName(); isSelectable = false })
            val enabled = SwitchPreferenceCompat(requireContext()).apply {
                key = "providerEnabled"; isPersistent = false
                title = getString(R.string.provider_routing_use,options.routingProvider)
                summary = getString(R.string.provider_routing_use_summary)
                isChecked = options.providerRoutingEnabled
                if (fullJson) { isChecked = options.providerRouting != "off"; isEnabled = false }
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
                isEnabled = profiles.isNotEmpty() && !fullJson
            })
            category(R.string.provider_profile_settings)
            add(Preference(requireContext()).apply {
                title = document.string("Name").ifBlank { "Default" }
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(),ProviderRoutingActivity::class.java).putExtra("groupId",group!!.id).putExtra("details",true)); true
                }
            })
            if (fullJson) add(Preference(requireContext()).apply { summary = getString(R.string.provider_json_notice,options.routingProvider); isSelectable = false })
            return
            }
            fun add(preference: Preference) { preference.isPersistent = false; preferenceScreen.addPreference(preference) }
            add(Preference(requireContext()).apply { title = getString(R.string.provider_profile_heading); summary = document.string("Name").ifBlank { "Default" }; isSelectable = false })
            fun toggle(keyName: String, label: Int, fallback: Boolean) = add(SwitchPreferenceCompat(requireContext()).apply {
                key = keyName; title = getString(label); isChecked = ProviderRouting.truth(document,keyName,fallback)
                setOnPreferenceChangeListener { _, next -> document[keyName] = (next as Boolean).toString(); update(); true }
            })
            fun choice(keyName: String,label: Int,values: Array<String>,fallback: String) = add(SimpleMenuPreference(requireContext()).apply {
                key = keyName; title = getString(label); entries = values; entryValues = values
                value = document.string(keyName).ifBlank { fallback }; summary = value
                setOnPreferenceChangeListener { _, next -> document[keyName] = next as String; summary = next; update(); true }
            })
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
            category(R.string.provider_geo_files)
            for ((kind,label) in listOf("geosite" to R.string.provider_routing_geosite,"geoip" to R.string.provider_routing_geoip)) {
                add(Preference(requireContext()).apply {
                    key = "geo-$kind"; title = getString(label)
                    val file = app.qthrone.mobile.route.ProviderGeoAssets.file(document,kind)
                    summary = app.qthrone.mobile.route.ProviderGeoAssets.url(document,kind) + if (file.isFile) "\n" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified())) + ", " + android.text.format.Formatter.formatShortFileSize(requireContext(),file.length()) else ""
                    isEnabled = !fullJson
                    setOnPreferenceClickListener {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { runCatching { app.qthrone.mobile.route.ProviderGeoAssets.prepare(group!!.id,document,true) } }
                            build()
                        }; true
                    }
                })
            }
            if (!fullJson) {
                field("Geoipurl",R.string.provider_routing_geoip,app.qthrone.mobile.route.ProviderGeoAssets.url(document,"geoip"))
                field("Geositeurl",R.string.provider_routing_geosite,app.qthrone.mobile.route.ProviderGeoAssets.url(document,"geosite"))
            }
            category(if (fullJson) R.string.provider_tunnel_dns else R.string.provider_remote_dns)
            choice("RemoteDNSType",if (fullJson) R.string.provider_tunnel_type else R.string.provider_routing_remote_type,arrayOf("DoH","DoU"),"DoH")
            field("RemoteDNSDomain",if (fullJson) R.string.provider_tunnel_domain else R.string.provider_routing_remote_domain,"https://cloudflare-dns.com/dns-query")
            field("RemoteDNSIP",if (fullJson) R.string.provider_tunnel_ip else R.string.provider_routing_remote_ip,"1.1.1.1")
            if (fullJson) return
            category(R.string.provider_direct_dns)
            choice("DomesticDNSType",R.string.provider_routing_direct_type,arrayOf("DoH","DoU"),"DoU")
            field("DomesticDNSDomain",R.string.provider_routing_direct_domain)
            field("DomesticDNSIP",R.string.provider_routing_direct_ip,"8.8.8.8")
            field("DnsHosts",R.string.provider_routing_hosts,hosts = true)
            category(R.string.provider_rules_title)
            toggle("GlobalProxy",R.string.provider_routing_global,true)
            toggle("FakeDNS",R.string.provider_routing_fake_dns,false)
            choice("DomainStrategy",R.string.provider_routing_strategy,arrayOf("AsIs","IPIfNonMatch","IPOnDemand"),"AsIs")
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
                saveLock.withLock {
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
        }
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is EditTextPreference) MobilePreferenceDialog().apply {
                arguments = Bundle().apply { putString("key",preference.key) }; setTargetFragment(this@Screen,0)
            }.show(parentFragmentManager,"provider-input") else super.onDisplayPreferenceDialog(preference)
        }
    }
}
