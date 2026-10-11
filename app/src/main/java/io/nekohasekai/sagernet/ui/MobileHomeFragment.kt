package app.qthrone.mobile.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.qthrone.mobile.bg.test.TestSpec
import app.qthrone.mobile.ui.test.TestSessionClient
import androidx.room.InvalidationTracker
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.*
import app.qthrone.mobile.group.SubscriptionClient
import app.qthrone.mobile.ui.profile.profileSettingsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mobile dashboard backed by the existing Room repositories and subscription queue. */
class MobileHomeFragment : ToolbarFragment() {
    private val blue get() = MobilePalette.accent(requireContext())
    private val surface get() = MobilePalette.surface
    private val muted get() = MobilePalette.muted
    private var content: LinearLayout? = null
    private var power: TextView? = null
    private var load: Job? = null
    private val collapsed = mutableSetOf<Long>()
    private var observer: InvalidationTracker.Observer? = null
    private var openSwipe: MobileSwipeTile? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun tile(selected: Boolean = false) = GradientDrawable().apply {
        setColor(if (selected) androidx.core.graphics.ColorUtils.blendARGB(surface,blue,.16f) else surface)
        cornerRadius = dp(5).toFloat()
        setStroke(dp(1), if (selected) blue else Color.argb(45, 103, 116, 128))
    }
    private fun text(value: String, size: Float = 16f, color: Int = MobilePalette.text) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private fun action(label: String, callback: () -> Unit) = text(label, 16f, blue).apply {
        gravity = Gravity.CENTER; minHeight = dp(48)
        isFocusable = true; setOnClickListener { callback() }
    }
    private fun iconAction(icon: Int, description: String, color: Int = Color.TRANSPARENT, callback: () -> Unit) = ImageButton(requireContext()).apply {
        setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.text); setBackgroundColor(color); setPadding(dp(12),dp(12),dp(12),dp(12))
        contentDescription=description; setOnClickListener { callback() }
    }
    private fun host() = requireActivity() as MainActivity
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            background = MobileHomeBackdrop(); isFillViewport = true
        }
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        scroll.addView(content, FrameLayout.LayoutParams(-1,-2))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        return scroll
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        state?.getLongArray("collapsedGroups")?.let { collapsed.addAll(it.toList()) }
        observer = object : InvalidationTracker.Observer("groups", "profiles") {
            override fun onInvalidated(tables: Set<String>) { activity?.runOnUiThread { refresh() } }
        }.also { SagerDatabase.instance.invalidationTracker.addObserver(it) }
        viewLifecycleOwner.lifecycleScope.launch { SubscriptionClient.states.collect { refresh() } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("collapsedGroups", collapsed.toLongArray())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroyView() {
        observer?.let { SagerDatabase.instance.invalidationTracker.removeObserver(it) }
        observer = null; load?.cancel(); content = null; power = null
        openSwipe=null
        super.onDestroyView()
    }
    fun refresh() {
        if (content == null || !isAdded) return
        load?.cancel()
        load = viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                GroupRepo.all().sortedBy { if(it.id in pinnedGroups()) 0 else 1 }.map { it to SagerDatabase.proxyDao.getByGroup(it.id) }
            }
            val column = content ?: return@launch
            column.removeAllViews()
            openSwipe=null
            val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
            fun headerIcon(icon: Int, description: String, callback: (View) -> Unit) = ImageButton(requireContext()).apply {
                setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.text); background = null; setPadding(dp(10),dp(10),dp(10),dp(10))
                contentDescription = description; setOnClickListener { callback(it) }
            }
            header.addView(headerIcon(R.drawable.ic_mobile_settings, getString(R.string.qmobile_settings)) { host().displayFragmentWithId(R.id.nav_settings) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            header.addView(View(requireContext()), LinearLayout.LayoutParams(0, dp(48), 1f))
            header.addView(headerIcon(R.drawable.ic_mobile_add, getString(R.string.qmobile_add)) { addMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            column.addView(header)
            val centre = FrameLayout(requireContext())
            power = action("") { host().toggleService() }.apply {
                contentDescription = getString(R.string.qmobile_connect)
                background = MobilePowerArtwork(blue)
            }
            centre.addView(power, FrameLayout.LayoutParams(dp(252), dp(252), Gravity.CENTER))
            column.addView(centre, LinearLayout.LayoutParams(-1, dp(284)))
            updateState()
            val imports = LinearLayout(requireContext())
            fun importButton(label: String, icon: Int, callback: () -> Unit) = LinearLayout(requireContext()).apply {
                gravity=Gravity.CENTER; isFocusable=true; contentDescription=label
                setOnClickListener { callback() }
                background=GradientDrawable().apply { setColor(surface); cornerRadius=dp(16).toFloat() }
                addView(ImageView(requireContext()).apply { setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.text); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(10) })
                addView(text(label,14f).apply { setPadding(0,0,0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
            imports.addView(importButton(getString(R.string.qmobile_clipboard), R.drawable.ic_mobile_clipboard) { importClipboard() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            imports.addView(importButton(getString(R.string.qmobile_qr), R.drawable.ic_mobile_qr) { startActivity(Intent(requireContext(), ScannerActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            column.addView(MobileSeasonalView(requireContext()), LinearLayout.LayoutParams(-1,dp(64)))
            for ((group, profiles) in groups) {
                if (profiles.isEmpty() && !group.isSubscription) continue
                val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = tile() }
                val rows = LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                val title = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                val arrow = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_mobile_chevron); imageTintList=android.content.res.ColorStateList.valueOf(muted); rotation=if(group.id in collapsed) 0f else 90f
                }
                val heading = LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,0,0); isFocusable=true }
                heading.addView(arrow,LinearLayout.LayoutParams(dp(20),dp(20)))
                val names=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(4),dp(8)) }
                names.addView(text(group.displayName(),17f).apply { setPadding(0,0,0,0); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
                if(group.isSubscription && group.subLastUpdate>0) names.addView(text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(group.subLastUpdate*1000)),10f,muted).apply { setPadding(0,dp(3),0,0) })
                heading.addView(names,LinearLayout.LayoutParams(0,-2,1f))
                heading.setOnClickListener {
                    if (!collapsed.add(group.id)) collapsed.remove(group.id)
                    rows.visibility=if(group.id in collapsed) View.GONE else View.VISIBLE
                    arrow.animate().rotation(if(group.id in collapsed) 0f else 90f).setDuration(180).start()
                }
                title.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
                if (group.isSubscription) title.addView(iconAction(R.drawable.ic_mobile_refresh,getString(R.string.qmobile_refresh)) { SubscriptionClient.refreshGroup(group.id,true) }.apply { isEnabled=!SubscriptionClient.states.value.containsKey(group.id); alpha=if(isEnabled) 1f else .4f },LinearLayout.LayoutParams(dp(44),dp(48)))
                title.addView(iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests)) { testMenu(profiles.map { it.id }.toLongArray(),group.displayName(),group.id) },LinearLayout.LayoutParams(dp(44),dp(48)))
                lateinit var more: ImageButton
                more=iconAction(R.drawable.ic_mobile_more,getString(R.string.qmobile_more)) { groupMenu(group,more) }
                title.addView(more,LinearLayout.LayoutParams(dp(44),dp(48)))
                box.addView(title)
                if (group.isSubscription) {
                    val quota=group.subInfo
                    val traffic=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(12),dp(8)) }
                    traffic.addView(iconAction(R.drawable.ic_mobile_info,getString(R.string.qmobile_subscription_info)) {
                        val expires=if(quota.expire>0) java.text.DateFormat.getDateInstance().format(java.util.Date(quota.expire*1000)) else "\u2014"
                        MaterialAlertDialogBuilder(requireContext()).setTitle(group.displayName()).setMessage(getString(R.string.qmobile_expiry,expires)).setPositiveButton(android.R.string.ok,null).show()
                    },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val bar=FrameLayout(requireContext()).apply { background=GradientDrawable().apply { setColor(MobilePalette.toolbar); cornerRadius=dp(3).toFloat(); setStroke(dp(1),muted) } }
                    var usage=getString(R.string.qmobile_quota_unknown)
                    if (quota.valid && quota.hasQuota) {
                        val upload=quota.upload.coerceAtLeast(0); val download=quota.download.coerceAtLeast(0)
                        val bytes=if(download>Long.MAX_VALUE-upload) Long.MAX_VALUE else upload+download
                        val used=android.text.format.Formatter.formatShortFileSize(requireContext(),bytes)
                        val total=if(quota.total>0) android.text.format.Formatter.formatShortFileSize(requireContext(),quota.total) else "\u221e"
                        usage="$used / $total"
                    }
                    bar.addView(text(usage,11f).apply { gravity=Gravity.CENTER; setPadding(dp(4),0,dp(4),0) },FrameLayout.LayoutParams(-1,-1))
                    traffic.addView(bar,LinearLayout.LayoutParams(0,dp(20),1f).apply { marginStart=dp(8) }); box.addView(traffic)
                }
                for (profile in profiles) {
                    val row = LinearLayout(requireContext()).apply {
                        gravity = Gravity.CENTER_VERTICAL; background = tile(profile.id == DataStore.selectedProxy)
                        isFocusable = true
                        setOnClickListener {
                            DataStore.selectedProxy = profile.id
                            if (DataStore.serviceState.connected) SagerNet.reloadService()
                            refresh()
                        }
                        setOnLongClickListener {
                            startActivity(profile.profileSettingsIntent(requireContext(),group.isSubscription)); true
                        }
                    }
                    val displayName=profile.displayName().trim()
                    val first=if(displayName.isNotEmpty()) displayName.codePointAt(0) else 0
                    val firstLength=Character.charCount(first)
                    val second=if(displayName.length>firstLength) displayName.codePointAt(firstLength) else 0
                    val hasFlag=first in 0x1f1e6..0x1f1ff && second in 0x1f1e6..0x1f1ff
                    val flagLength=if(hasFlag) firstLength+Character.charCount(second) else 0
                    val badgeBackground=GradientDrawable().apply { setColor(MobilePalette.toolbar);cornerRadius=dp(7).toFloat() }
                    val badge=if(hasFlag) text(displayName.substring(0,flagLength),25f).apply { gravity=Gravity.CENTER;setPadding(0,0,0,0) } else ImageView(requireContext()).apply { setImageResource(R.drawable.ic_mobile_globe);imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.muted);setPadding(dp(5),dp(5),dp(5),dp(5)) }
                    badge.background=badgeBackground;badge.clipToOutline=true
                    row.addView(badge,LinearLayout.LayoutParams(dp(38),dp(38)).apply { marginStart=dp(10) })
                    val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(8), dp(9)) }
                    labels.addView(text(if(hasFlag) displayName.substring(flagLength).trimStart() else displayName, 16f).apply { setPadding(0,0,0,dp(4)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    labels.addView(text(profile.displayType(), 12f, muted).apply { setPadding(0,0,0,0); maxLines=1 })
                    row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    if(profile.latency>0) row.addView(text("${profile.latency} ms",13f,Color.rgb(128,192,139)))
                    else row.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_mobile_chevron); setPadding(dp(6),dp(6),dp(6),dp(6)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val swipe = MobileSwipeTile(requireContext(), row,
                        iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests),Color.rgb(36,171,85)) { testMenu(longArrayOf(profile.id),profile.displayName(),group.id) },
                        iconAction(R.drawable.ic_mobile_share,getString(R.string.qmobile_share),Color.rgb(60,132,190)) { shareProfile(profile) },
                        iconAction(R.drawable.ic_mobile_delete,getString(R.string.qmobile_delete),Color.rgb(220,66,61)) {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_delete).setMessage(profile.displayName())
                                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok) { _,_ ->
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val result=withContext(Dispatchers.IO) { ProfileManager.batchDeleteProfiles(listOf(profile.id),stopRunning=true) }
                                        if(!result.ok) Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show()
                                    }
                                }.show()
                        }).apply { onOpened={ opened -> openSwipe?.takeIf { it!==opened }?.closeActions(); openSwipe=opened } }
                    rows.addView(swipe, LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(5),0,dp(5),dp(5)) })
                }
                rows.visibility=if(group.id in collapsed) View.GONE else View.VISIBLE
                box.addView(rows)
                box.layoutTransition=android.animation.LayoutTransition().apply { setDuration(180) }
                column.addView(box, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
            }
            if(groups.all { it.second.isEmpty() && !it.first.isSubscription }) {
                column.addView(View(requireContext()), LinearLayout.LayoutParams(-1,0,1f))
                column.addView(text(getString(R.string.qmobile_empty),14f).apply { gravity=Gravity.CENTER; setPadding(dp(16),dp(16),dp(16),dp(12)) })
                column.addView(imports,LinearLayout.LayoutParams(-1,dp(48)))
            }
        }
    }
    fun updateState() {
        val state = DataStore.serviceState
        power?.isEnabled = state != app.qthrone.mobile.bg.BaseService.State.Stopping
        power?.contentDescription = getString(R.string.qmobile_connect) + ", " + getString(if (state.connected) R.string.qmobile_connected else if (state.canStop) R.string.qmobile_connecting else R.string.qmobile_disconnected)
        power?.setTextColor(if (state.connected) Color.rgb(128,192,139) else blue)
        (power?.background as? MobilePowerArtwork)?.connected=state.connected
    }
    private fun testMenu(ids: LongArray, label: String, gid: Long) {
        TestSessionClient.start(TestSpec(TestSpec.KIND_URL, profileIds=ids,scopeLabel=label),gid)
    }
    private fun importClipboard() {
        val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (!value.isNullOrBlank()) SubscribeFlows.importText(requireActivity(), value)
        else Toast.makeText(requireContext(), R.string.qmobile_clipboard_empty, Toast.LENGTH_SHORT).show()
    }
    private fun pinnedGroups() = DataStore.mobilePinnedGroups.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    private fun shareProfile(profile:ProxyEntity) {
        val swipe=openSwipe ?: return
        fun option(label:String,icon:Int,callback:()->Unit):View {
            return LinearLayout(requireContext()).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isFocusable=true;contentDescription=label;setBackgroundColor(Color.rgb(105,93,176))
                setOnClickListener { swipe.closeActions();callback() }
                addView(ImageView(requireContext()).apply { setImageResource(icon);imageTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(23),dp(23)))
                addView(text(label,10f,Color.WHITE).apply { setPadding(0,dp(4),0,0);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
        }
        fun copy(value:String) { Toast.makeText(requireContext(),if(SagerNet.trySetPrimaryClip(value)) R.string.action_export_msg else R.string.action_export_err,Toast.LENGTH_SHORT).show() }
        val link=profile.exportLink().ifBlank { profile.exportJsonLink() }
        swipe.showShareChoices(
            option("URL",R.drawable.ic_mobile_link) { copy(link) },
            option(getString(R.string.qmobile_qr),R.drawable.ic_mobile_qr) { app.qthrone.mobile.widget.QRCodeDialog(link,profile.displayName()).show(parentFragmentManager,"profile-qr") },
            option("JSON",R.drawable.ic_mobile_json) { copy(org.json.JSONObject(profile.outbound.exportToJson().toCompact()).toString(2)) },
        )
    }
    private fun groupMenu(group: ProxyGroup, anchor: View) {
        val dialog=com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val body=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(24)); setBackgroundColor(MobilePalette.background)
        }
        body.addView(View(requireContext()).apply { background=GradientDrawable().apply { setColor(MobilePalette.muted); cornerRadius=dp(2).toFloat() } },LinearLayout.LayoutParams(dp(40),dp(4)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(20) })
        body.addView(text(group.displayName(),18f).apply { setTypeface(null,Typeface.BOLD) })
        fun entry(label:String,icon:Int,action:()->Unit) {
            val row=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; isFocusable=true; contentDescription=label; setOnClickListener { dialog.dismiss();action() } }
            row.addView(ImageView(requireContext()).apply { setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.muted) },LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginStart=dp(14) })
            row.addView(text(label,17f).apply { setTypeface(null,Typeface.BOLD) },LinearLayout.LayoutParams(-1,dp(56)))
            body.addView(row)
        }
        entry(getString(R.string.qmobile_settings),R.drawable.ic_mobile_settings) { editGroup(group,true) }
        entry(getString(R.string.qmobile_routing),R.drawable.ic_baseline_transform_24) { host().displayFragment(MobileRoutingFragment()) }
        if(group.isSubscription) entry(getString(R.string.qmobile_refresh),R.drawable.ic_mobile_refresh) { SubscriptionClient.refreshGroup(group.id,true) }
        entry(getString(R.string.qmobile_ping),R.drawable.ic_mobile_test) { viewLifecycleOwner.lifecycleScope.launch { val ids=withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getByGroup(group.id).map { it.id }.toLongArray() };testMenu(ids,group.displayName(),group.id) } }
        entry(getString(R.string.qmobile_edit),R.drawable.ic_mobile_edit) { editGroup(group,false) }
        val pinned=group.id in pinnedGroups()
        entry(getString(if(pinned) R.string.qmobile_unpin else R.string.qmobile_pin),R.drawable.ic_mobile_pin) { val ids=pinnedGroups().toMutableSet();if(pinned) ids.remove(group.id) else ids.add(group.id);DataStore.mobilePinnedGroups=ids.joinToString(",");refresh() }
        entry(getString(R.string.qmobile_delete),R.drawable.ic_mobile_delete) {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_delete).setMessage(group.displayName()).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok) { _,_ ->
                viewLifecycleOwner.lifecycleScope.launch { val deleted=withContext(Dispatchers.IO) { GroupRepo.delete(group.id) };if(!deleted) Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show() }
            }.show()
        }
        dialog.setContentView(body);dialog.show()
    }
    private fun editGroup(group:ProxyGroup,settings:Boolean) {
        val dialog=com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val body=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(32));setBackgroundColor(MobilePalette.background) }
        body.addView(text(getString(if(settings) R.string.qmobile_settings else R.string.qmobile_edit),20f))
        val name=EditText(requireContext()).apply { setText(group.name);hint=getString(R.string.qmobile_subscription_name);setTextColor(MobilePalette.text);setHintTextColor(MobilePalette.muted);isSingleLine=true }
        val url=EditText(requireContext()).apply { setText(group.url);hint=getString(R.string.qmobile_subscription_url);setTextColor(MobilePalette.text);setHintTextColor(MobilePalette.muted);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        body.addView(name);if(group.isSubscription) body.addView(url)
        val auto=androidx.appcompat.widget.SwitchCompat(requireContext()).apply { text=getString(R.string.qmobile_auto_update);isChecked=!group.skipAutoUpdate;setTextColor(MobilePalette.text) }
        if(settings && group.isSubscription) body.addView(auto)
        body.addView(action(getString(R.string.qmobile_save)) {
            val nextUrl=if(group.isSubscription) url.text.toString().trim() else ""
            if(group.isSubscription && (!nextUrl.startsWith("https://") && !nextUrl.startsWith("http://"))) { url.error=getString(R.string.qmobile_subscription_url);return@action }
            group.name=name.text.toString().trim().ifBlank { if(group.isSubscription) android.net.Uri.parse(nextUrl).host.orEmpty() else getString(R.string.qmobile_server_list) };group.url=nextUrl;if(settings) group.skipAutoUpdate=!auto.isChecked
            viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { GroupRepo.save(group) };dialog.dismiss();refresh() }
        })
        dialog.setContentView(body);dialog.show()
    }
    private fun addMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(getString(R.string.qmobile_clipboard)).setOnMenuItemClickListener { importClipboard(); true }
        popup.menu.add(getString(R.string.qmobile_qr)).setOnMenuItemClickListener { startActivity(Intent(requireContext(), ScannerActivity::class.java)); true }
        popup.menu.add(getString(R.string.qmobile_profiles)).setOnMenuItemClickListener { host().displayFragmentWithId(R.id.nav_configuration); true }
        popup.show()
    }
}
