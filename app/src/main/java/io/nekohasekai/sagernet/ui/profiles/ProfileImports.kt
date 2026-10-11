package app.qthrone.mobile.ui.profiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.ktx.snackbar
import app.qthrone.mobile.ktx.startFilesForResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.ui.ConfigurationFragment
import app.qthrone.mobile.ui.ScannerActivity
import app.qthrone.mobile.ui.SubscribeFlows
import app.qthrone.mobile.ui.profile.AnyTLSSettingsActivity
import app.qthrone.mobile.ui.profile.AutoSelectorSettingsActivity
import app.qthrone.mobile.ui.profile.ChainSettingsActivity
import app.qthrone.mobile.ui.profile.CustomSettingsActivity
import app.qthrone.mobile.ui.profile.DirectSettingsActivity
import app.qthrone.mobile.ui.profile.HttpSettingsActivity
import app.qthrone.mobile.ui.profile.HysteriaSettingsActivity
import app.qthrone.mobile.ui.profile.JuicitySettingsActivity
import app.qthrone.mobile.ui.profile.MasqueSettingsActivity
import app.qthrone.mobile.ui.profile.MieruSettingsActivity
import app.qthrone.mobile.ui.profile.NaiveSettingsActivity
import app.qthrone.mobile.ui.profile.OpenConnectSettingsActivity
import app.qthrone.mobile.ui.profile.OpenVpnSettingsActivity
import app.qthrone.mobile.ui.profile.ProfileTextImport
import app.qthrone.mobile.ui.profile.SSHSettingsActivity
import app.qthrone.mobile.ui.profile.ShadowTLSSettingsActivity
import app.qthrone.mobile.ui.profile.ShadowsocksSettingsActivity
import app.qthrone.mobile.ui.profile.SnellSettingsActivity
import app.qthrone.mobile.ui.profile.SocksSettingsActivity
import app.qthrone.mobile.ui.profile.TrojanSettingsActivity
import app.qthrone.mobile.ui.profile.TrustTunnelSettingsActivity
import app.qthrone.mobile.ui.profile.TuicSettingsActivity
import app.qthrone.mobile.ui.profile.VMessSettingsActivity
import app.qthrone.mobile.ui.profile.VlessSettingsActivity
import app.qthrone.mobile.ui.profile.WireGuardSettingsActivity
import app.qthrone.mobile.ui.profile.XrayVlessSettingsActivity
import java.util.zip.ZipInputStream

/** The "+" menu: scan, clipboard, file and the new-profile editors. Imports go to the current tab's group. */
class ProfileImports(private val host: ConfigurationFragment) {

    private val importFile = host.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFile(uri)
    }

    /** Devices without a camera (TVs) have nothing to scan with. */
    fun prepare(menu: Menu) {
        menu.findItem(R.id.action_scan_qr_code)?.isVisible = hasCamera(host.requireContext())
    }

    private fun hasCamera(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> host.startActivity(Intent(host.requireContext(), ScannerActivity::class.java))
            R.id.action_import_clipboard -> importClipboard()
            R.id.action_import_file -> host.startFilesForResult(importFile, "*/*")
            else -> {
                val editor = editorFor(item.itemId) ?: return false
                host.startActivity(Intent(host.requireContext(), editor))
            }
        }
        return true
    }

    /** import_or_handle_deeplink: links, documents, deep links and subscription URLs, into the current tab's group. */
    private fun importClipboard() {
        val text = SagerNet.getClipboardText()
        if (text.isBlank()) {
            host.snackbar(R.string.clipboard_empty).show()
        } else {
            SubscribeFlows.importText(host.requireActivity(), text, host.currentGroupId)
        }
    }

    /** importFromFiles for a text file; a zip (Android extra) holds one document per entry (WireGuard / OpenVPN). */
    private fun importFile(uri: Uri) {
        val groupId = host.currentGroupId
        val activity = host.requireActivity()
        val resolver = activity.contentResolver
        host.launchIo {
            try {
                val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (fileName?.endsWith(".zip", true) != true) {
                    host.onUi { SubscribeFlows.importUri(activity, uri, groupId) }
                    return@launchIo
                }
                val outbounds = mutableListOf<Outbound>()
                ZipInputStream(resolver.openInputStream(uri)!!).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        outbounds.addAll(ProfileTextImport.parse(zip.bufferedReader().readText()))
                        zip.closeEntry()
                    }
                }
                if (outbounds.isEmpty()) {
                    host.onUi { snackbar(R.string.no_proxies_found_in_file).show() }
                } else {
                    ProfileManager.addProfileBatch(outbounds, groupId)
                    host.onUi {
                        snackbar(resources.getQuantityString(R.plurals.added, outbounds.size, outbounds.size)).show()
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                host.onUi { snackbar(e.readableMessage).show() }
            }
        }
    }

    private fun editorFor(itemId: Int): Class<*>? = when (itemId) {
        R.id.action_new_auto_selector -> AutoSelectorSettingsActivity::class.java
        R.id.action_new_socks -> SocksSettingsActivity::class.java
        R.id.action_new_http -> HttpSettingsActivity::class.java
        R.id.action_new_ss -> ShadowsocksSettingsActivity::class.java
        R.id.action_new_vmess -> VMessSettingsActivity::class.java
        R.id.action_new_vless -> VlessSettingsActivity::class.java
        R.id.action_new_xray_vless -> XrayVlessSettingsActivity::class.java
        R.id.action_new_trojan -> TrojanSettingsActivity::class.java
        R.id.action_new_mieru -> MieruSettingsActivity::class.java
        R.id.action_new_naive -> NaiveSettingsActivity::class.java
        R.id.action_new_hysteria -> HysteriaSettingsActivity::class.java
        R.id.action_new_tuic -> TuicSettingsActivity::class.java
        R.id.action_new_juicity -> JuicitySettingsActivity::class.java
        R.id.action_new_ssh -> SSHSettingsActivity::class.java
        R.id.action_new_snell -> SnellSettingsActivity::class.java
        R.id.action_new_wg -> WireGuardSettingsActivity::class.java
        R.id.action_new_shadowtls -> ShadowTLSSettingsActivity::class.java
        R.id.action_new_anytls -> AnyTLSSettingsActivity::class.java
        R.id.action_new_trusttunnel -> TrustTunnelSettingsActivity::class.java
        R.id.action_new_direct -> DirectSettingsActivity::class.java
        R.id.action_new_masque -> MasqueSettingsActivity::class.java
        R.id.action_new_openvpn -> OpenVpnSettingsActivity::class.java
        R.id.action_new_openconnect -> OpenConnectSettingsActivity::class.java
        R.id.action_new_config -> CustomSettingsActivity::class.java
        R.id.action_new_chain -> ChainSettingsActivity::class.java
        else -> null
    }
}
