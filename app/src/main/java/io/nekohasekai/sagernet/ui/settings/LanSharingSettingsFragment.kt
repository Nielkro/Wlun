package app.qthrone.mobile.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.SettingValidators
import app.qthrone.mobile.database.SettingsRegistry
import app.qthrone.mobile.database.preference.EditTextPreferenceModifiers
import app.qthrone.mobile.ui.GroupSettingsActivity
import app.qthrone.mobile.ktx.needReload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class LanSharingSettingsFragment : SettingsScreenFragment(R.xml.settings_sharing) {
    override fun beforeInflate() { DataStore.initGlobal() }
    override fun bind() {
        val port = pref<EditTextPreference>(SettingsRegistry.INBOUND_SOCKS_PORT.key)
        checkText(port.key, R.string.invalid_port, valid=SettingValidators::isPort)
        port.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        val user = pref<EditTextPreference>(SettingsRegistry.INBOUND_USER.key)
        user.setOnPreferenceChangeListener { _, value ->
            if (value.toString().isBlank()) false else { needReload(); true }
        }
        val pass = pref<EditTextPreference>(SettingsRegistry.INBOUND_PASS.key)
        checkText(pass.key,R.string.qmobile_socks_password_invalid,valid={ it.length>=32 })
        pass.summaryProvider=GroupSettingsActivity.PasswordSummaryProvider
        pass.setOnBindEditTextListener { it.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        reloadOn(SettingsRegistry.LAN_SHARING_ENABLED.key)
        pref<Preference>("sharingAddresses").setOnPreferenceClickListener {
            SagerNet.trySetPrimaryClip(it.summary?.toString().orEmpty()); true
        }
        pref<Preference>("sharingHotspot").setOnPreferenceClickListener {
            runCatching { startActivity(Intent("android.settings.TETHER_SETTINGS")) }
                .onFailure { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            true
        }
    }
    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            val addresses=withContext(Dispatchers.IO) {
                runCatching { NetworkInterface.getNetworkInterfaces().toList().filter {
                    it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("wg")
                }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct() }.getOrDefault(emptyList())
            }
            pref<Preference>("sharingAddresses").summary=addresses.joinToString("\n").ifEmpty { getString(R.string.lan_sharing_no_address) }
        }
    }
}
