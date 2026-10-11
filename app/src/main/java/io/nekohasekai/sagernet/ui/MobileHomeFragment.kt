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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mobile dashboard backed by the existing Room repositories and subscription queue. */
class MobileHomeFragment : ToolbarFragment() {
    private val blue = Color.rgb(87, 190, 229)
    private val surface = Color.rgb(49, 51, 54)
    private val muted = Color.rgb(173, 182, 191)
    private var content: LinearLayout? = null
    private var power: TextView? = null
    private var load: Job? = null
    private val collapsed = mutableSetOf<Long>()
    private var observer: InvalidationTracker.Observer? = null
    private var testDialog: androidx.appcompat.app.AlertDialog? = null
    private var openSwipe: MobileSwipeTile? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun tile(selected: Boolean = false) = GradientDrawable().apply {
        setColor(if (selected) Color.rgb(48, 67, 77) else surface)
        cornerRadius = dp(5).toFloat()
        setStroke(dp(1), if (selected) blue else Color.argb(45, 103, 116, 128))
    }
    private fun text(value: String, size: Float = 16f, color: Int = Color.rgb(231,234,237)) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private fun action(label: String, callback: () -> Unit) = text(label, 16f, blue).apply {
        gravity = Gravity.CENTER; minHeight = dp(48)
        isFocusable = true; setOnClickListener { callback() }
    }
    private fun iconAction(icon: Int, description: String, color: Int = Color.TRANSPARENT, callback: () -> Unit) = ImageButton(requireContext()).apply {
        setImageResource(icon); setBackgroundColor(color); setPadding(dp(12),dp(12),dp(12),dp(12))
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
        testDialog?.dismiss(); testDialog=null
        super.onDestroyView()
    }
    fun refresh() {
        if (content == null || !isAdded) return
        load?.cancel()
        load = viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                GroupRepo.all().map { it to SagerDatabase.proxyDao.getByGroup(it.id) }
            }
            val column = content ?: return@launch
            column.removeAllViews()
            openSwipe=null
            val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
            fun headerIcon(icon: Int, description: String, callback: (View) -> Unit) = ImageButton(requireContext()).apply {
                setImageResource(icon); background = null; setPadding(dp(10),dp(10),dp(10),dp(10))
                contentDescription = description; setOnClickListener { callback(it) }
            }
            header.addView(headerIcon(R.drawable.ic_mobile_settings, getString(R.string.qmobile_settings)) { host().displayFragmentWithId(R.id.nav_settings) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            header.addView(View(requireContext()), LinearLayout.LayoutParams(0, dp(48), 1f))
            header.addView(headerIcon(R.drawable.ic_mobile_add, getString(R.string.qmobile_add)) { addMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            column.addView(header)
            val centre = FrameLayout(requireContext())
            power = action("") { host().toggleService() }.apply {
                contentDescription = getString(R.string.qmobile_connect)
                background = MobilePowerArtwork()
            }
            centre.addView(power, FrameLayout.LayoutParams(dp(252), dp(252), Gravity.CENTER))
            column.addView(centre, LinearLayout.LayoutParams(-1, dp(284)))
            updateState()
            val imports = LinearLayout(requireContext())
            fun importButton(label: String, icon: Int, callback: () -> Unit) = LinearLayout(requireContext()).apply {
                gravity=Gravity.CENTER; isFocusable=true; contentDescription=label
                setOnClickListener { callback() }
                background=GradientDrawable().apply { setColor(Color.argb(145,57,61,65)); cornerRadius=dp(16).toFloat() }
                addView(ImageView(requireContext()).apply { setImageResource(icon); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(10) })
                addView(text(label,14f).apply { setPadding(0,0,0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
            imports.addView(importButton(getString(R.string.qmobile_clipboard), R.drawable.ic_mobile_clipboard) { importClipboard() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            imports.addView(importButton(getString(R.string.qmobile_qr), R.drawable.ic_mobile_qr) { startActivity(Intent(requireContext(), ScannerActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            if(groups.any { it.second.isNotEmpty() || it.first.isSubscription }) column.addView(action(getString(R.string.qmobile_collapse_all)) {
                val ids=groups.map { it.first.id }
                if (ids.all { it in collapsed }) collapsed.clear() else collapsed.addAll(ids)
                refresh()
            }.apply { gravity=Gravity.END; textSize=12f; setPadding(0,dp(4),dp(4),dp(4)) }, LinearLayout.LayoutParams(-1,dp(36)))
            for ((group, profiles) in groups) {
                if (profiles.isEmpty() && !group.isSubscription) continue
                val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = tile() }
                val rows = LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                val title = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                val arrow = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_mobile_chevron); rotation=if(group.id in collapsed) 0f else 90f
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
                        val expires=if(quota.expire>0) java.text.DateFormat.getDateInstance().format(java.util.Date(quota.expire*1000)) else "—"
                        MaterialAlertDialogBuilder(requireContext()).setTitle(group.displayName()).setMessage(getString(R.string.qmobile_expiry,expires)).setPositiveButton(android.R.string.ok,null).show()
                    },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val bar=FrameLayout(requireContext()).apply { background=GradientDrawable().apply { setColor(Color.rgb(35,37,40)); cornerRadius=dp(3).toFloat(); setStroke(dp(1),Color.rgb(20,22,25)) } }
                    var usage=getString(R.string.qmobile_quota_unknown)
                    if (quota.valid && quota.hasQuota) {
                        val upload=quota.upload.coerceAtLeast(0); val download=quota.download.coerceAtLeast(0)
                        val bytes=if(download>Long.MAX_VALUE-upload) Long.MAX_VALUE else upload+download
                        val used=android.text.format.Formatter.formatShortFileSize(requireContext(),bytes)
                        val total=if(quota.total>0) android.text.format.Formatter.formatShortFileSize(requireContext(),quota.total) else "∞"
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
                            viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { GroupRepo.setCurrent(group.id) }; host().displayFragmentWithId(R.id.nav_configuration) }; true
                        }
                    }
                    val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(8), dp(9)) }
                    labels.addView(text(profile.displayName(), 16f).apply { setPadding(0,0,0,dp(4)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    labels.addView(text(profile.displayType(), 12f, muted).apply { setPadding(0,0,0,0); maxLines=1 })
                    row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    if(profile.latency>0) row.addView(text("${profile.latency} ms",13f,Color.rgb(128,192,139)))
                    else row.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_mobile_chevron); setPadding(dp(6),dp(6),dp(6),dp(6)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val swipe = MobileSwipeTile(requireContext(), row,
                        iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests),Color.rgb(36,171,85)) { testMenu(longArrayOf(profile.id),profile.displayName(),group.id) },
                        iconAction(R.drawable.ic_mobile_share,getString(R.string.qmobile_share),Color.rgb(60,132,190)) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,profile.exportLink().ifBlank { profile.exportJsonLink() }) }, getString(R.string.qmobile_share))) },
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
        val labels = arrayOf(getString(R.string.qmobile_ping),getString(R.string.qmobile_speed))
        MaterialAlertDialogBuilder(requireContext()).setTitle(label).setItems(labels) { _, index ->
            val spec=TestSpec(if(index==0) TestSpec.KIND_URL else TestSpec.KIND_SPEED, profileIds=ids,scopeLabel=label)
            if(!TestSessionClient.start(spec,gid)) { showTestProgress(); return@setItems }
            showTestProgress()
        }.show()
    }
    private fun showTestProgress() {
        if(testDialog?.isShowing == true) return
        val info=text("",15f,muted)
        val dialog=MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_tests).setView(info)
            .setNegativeButton(R.string.qmobile_stop) { _,_ -> TestSessionClient.stop() }.setPositiveButton(android.R.string.ok,null).create()
        dialog.show()
        testDialog=dialog
        val job=viewLifecycleOwner.lifecycleScope.launch {
            TestSessionClient.state.collect { state ->
                info.text="${state.done} / ${state.total}"
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.isEnabled=state.running
            }
        }
        dialog.setOnDismissListener { job.cancel(); if(testDialog===dialog) testDialog=null }
    }
    private fun importClipboard() {
        val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (!value.isNullOrBlank()) SubscribeFlows.importText(requireActivity(), value)
        else Toast.makeText(requireContext(), R.string.qmobile_clipboard_empty, Toast.LENGTH_SHORT).show()
    }
    private fun groupMenu(group: ProxyGroup, anchor: View) {
        val menu=android.widget.PopupMenu(requireContext(),anchor)
        menu.menu.add(getString(R.string.qmobile_settings)).setOnMenuItemClickListener {
            startActivity(Intent(requireContext(),GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_GROUP_ID,group.id)); true
        }
        menu.menu.add(getString(R.string.qmobile_profiles)).setOnMenuItemClickListener {
            viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { GroupRepo.setCurrent(group.id) }; host().displayFragmentWithId(R.id.nav_configuration) }; true
        }
        if(group.isSubscription) menu.menu.add(getString(R.string.action_copy)).setOnMenuItemClickListener { SagerNet.trySetPrimaryClip(group.url); true }
        menu.show()
    }
    private fun addMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(getString(R.string.qmobile_clipboard)).setOnMenuItemClickListener { importClipboard(); true }
        popup.menu.add(getString(R.string.qmobile_qr)).setOnMenuItemClickListener { startActivity(Intent(requireContext(), ScannerActivity::class.java)); true }
        popup.menu.add(getString(R.string.qmobile_profiles)).setOnMenuItemClickListener { host().displayFragmentWithId(R.id.nav_configuration); true }
        popup.show()
    }
}
