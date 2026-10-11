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
                if (fragment is app.qthrone.mobile.ui.settings.SettingsScreenFragment) preference.icon = null
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
            val category=(list.adapter as? PreferenceGroupAdapter)?.getItem(position) is PreferenceCategory
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
