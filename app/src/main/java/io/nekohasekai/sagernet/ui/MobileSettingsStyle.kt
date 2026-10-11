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
        root.setBackgroundColor(Color.rgb(30,30,30))
        val list=fragment.listView
        list.setBackgroundColor(Color.rgb(30,30,30))
        val divider=androidx.recyclerview.widget.DividerItemDecoration(list.context,RecyclerView.VERTICAL)
        divider.setDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(67,69,72)); setSize(1,(list.resources.displayMetrics.density).toInt().coerceAtLeast(1))
        })
        list.addItemDecoration(divider)
        fun style(view: View) {
            val position=list.getChildAdapterPosition(view)
            if(position==RecyclerView.NO_POSITION) return
            val category=(list.adapter as? PreferenceGroupAdapter)?.getItem(position) is PreferenceCategory
            view.setBackgroundColor(if(category) Color.rgb(36,37,39) else Color.rgb(30,30,30))
            view.findViewById<TextView>(android.R.id.title)?.apply {
                setTextColor(if(category) Color.rgb(87,190,229) else Color.rgb(231,234,237))
                textSize=if(category) 16f else 16f
                setTypeface(null,if(category) Typeface.BOLD else Typeface.NORMAL)
            }
            view.findViewById<TextView>(android.R.id.summary)?.setTextColor(Color.rgb(173,182,191))
        }
        list.addOnChildAttachStateChangeListener(object: RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view:View) { style(view) }
            override fun onChildViewDetachedFromWindow(view:View) {}
        })
        list.post { for(i in 0 until list.childCount) style(list.getChildAt(i)) }
    }
}
