package app.qthrone.mobile.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView

/** Shared palette for the settings root and every existing preference screen. */
object MobileSettingsStyle {
    fun apply(fragment: PreferenceFragmentCompat, root: View) {
        fun compact(group: androidx.preference.PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val preference = group.getPreference(i)
                if (fragment !is SettingsPreferenceFragment) preference.icon = null
                else if(preference !is PreferenceCategory) {
                    val icon=when {
                        preference.fragment?.contains("Appearance")==true -> app.qthrone.mobile.R.drawable.ic_mobile_color
                        preference.fragment?.contains("Testing")==true -> app.qthrone.mobile.R.drawable.ic_mobile_test
                        preference.key=="qmobileRouting" -> app.qthrone.mobile.R.drawable.ic_mobile_route
                        preference.fragment?.contains("Dns")==true -> app.qthrone.mobile.R.drawable.ic_mobile_globe
                        preference.fragment?.contains("Subscription")==true -> app.qthrone.mobile.R.drawable.ic_mobile_refresh
                        preference.key=="qmobileUpdate" -> app.qthrone.mobile.R.drawable.ic_mobile_refresh
                        else -> app.qthrone.mobile.R.drawable.ic_mobile_settings
                    }
                    preference.setIcon(icon)
                }
                preference.isIconSpaceReserved = preference.icon != null
                if (preference is androidx.preference.PreferenceGroup) compact(preference)
            }
        }
        compact(fragment.preferenceScreen)
        root.setBackgroundColor(MobilePalette.background)
        val list=fragment.listView
        list.setBackgroundColor(MobilePalette.background)
        val density=list.resources.displayMetrics.density
        list.setPadding((16*density).toInt(), (12*density).toInt(), (16*density).toInt(), (24*density).toInt())
        list.clipToPadding=false
        list.addItemDecoration(object: RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom=(10*density).toInt()
            }
        })
        fun style(view: View) {
            val position=list.getChildAdapterPosition(view)
            if(position==RecyclerView.NO_POSITION) return
            val preference=(list.adapter as? PreferenceGroupAdapter)?.getItem(position)
            val category=preference is PreferenceCategory
            if(preference?.key in listOf("inbound_user","inbound_pass")) {
                val container=view as? android.view.ViewGroup
                if(container!=null && container.findViewWithTag<View>("socks-copy")==null) {
                    val widget=container.findViewById<android.view.ViewGroup>(android.R.id.widget_frame)
                    widget?.visibility=View.VISIBLE
                    widget?.addView(android.widget.ImageButton(list.context).apply {
                        tag="socks-copy";contentDescription=list.context.getString(app.qthrone.mobile.R.string.action_copy)
                        setImageResource(app.qthrone.mobile.R.drawable.ic_mobile_clipboard)
                        imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(list.context))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener {
                            val value=if(preference?.key=="inbound_user") app.qthrone.mobile.database.DataStore.inboundUser else app.qthrone.mobile.database.DataStore.inboundPass
                            app.qthrone.mobile.SagerNet.trySetPrimaryClip(value)
                        }
                    },android.view.ViewGroup.LayoutParams((40*density).toInt(),(40*density).toInt()))
                }
            }
            view.background=android.graphics.drawable.GradientDrawable().apply {
                setColor(if(category) MobilePalette.background else MobilePalette.surface)
                cornerRadius=16*density
            }
            view.findViewById<android.widget.ImageView>(android.R.id.icon)?.imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(list.context))
            view.findViewById<TextView>(android.R.id.title)?.apply {
                setTextColor(if(category) MobilePalette.accent(list.context) else MobilePalette.text)
                textSize=if(category) 16f else 16f
                setTypeface(null,if(category) Typeface.BOLD else Typeface.NORMAL)
            }
            view.findViewById<TextView>(android.R.id.summary)?.setTextColor(MobilePalette.muted)
        }
        list.addOnChildAttachStateChangeListener(object: RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view:View) { style(view) }
            override fun onChildViewDetachedFromWindow(view:View) {}
        })
        list.post { for(i in 0 until list.childCount) style(list.getChildAt(i)) }
    }
}
