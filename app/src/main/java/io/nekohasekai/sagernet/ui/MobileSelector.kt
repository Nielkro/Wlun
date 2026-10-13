package app.qthrone.mobile.ui

import android.content.Context
import android.view.Gravity
import android.widget.*
import androidx.preference.ListPreference

object MobileSelector {
    fun showAt(anchor: android.view.View, labels: List<CharSequence>, selected: Int, onSelect: (Int) -> Unit) {
        val context = anchor.context
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        val list = ListView(context).apply {
            divider = null
            adapter = object : ArrayAdapter<CharSequence>(context, android.R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        text = if(position == selected) "✓  ${labels[position]}" else labels[position]
                        setTextColor(if(position == selected) MobilePalette.accent(context) else MobilePalette.text)
                        minimumHeight = dp(48)
                    }
            }
        }
        // Keep long DNS lists attached to their row instead of filling the middle of the screen.
        val popup = PopupWindow(list, minOf((anchor.width - dp(24)).coerceAtLeast(dp(120)), dp(240)), minOf(labels.size,4) * dp(48), true).apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(MobilePalette.surface); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), MobilePalette.muted)
            })
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
        }
        list.setOnItemClickListener { _, _, index, _ -> popup.dismiss(); onSelect(index) }
        popup.showAsDropDown(anchor, dp(12), 0)
    }
    fun show(context: Context, preference: ListPreference): Boolean {
        val labels=preference.entries ?: return false
        val values=preference.entryValues ?: return false
        show(context,preference.title ?: "",labels.toList(),preference.findIndexOfValue(preference.value)) { index ->
            val value=values[index].toString()
            if(preference.callChangeListener(value)) preference.value=value
        }
        return true
    }
    fun show(context: Context,title: CharSequence,labels: List<CharSequence>,selected: Int,onSelect:(Int)->Unit) {
        fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
        val dialog=MobileSheetDialog(context)
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(24)) }
        body.addView(TextView(context).apply { text=title;textSize=19f;setTextColor(MobilePalette.text);setPadding(dp(8),dp(8),dp(8),dp(12)) })
        val choices=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        labels.forEachIndexed { index,label ->
            choices.addView(TextView(context).apply {
                text=if(index==selected) "✓  $label" else label;textSize=16f;gravity=Gravity.CENTER_VERTICAL
                setTextColor(if(index==selected) MobilePalette.accent(context) else MobilePalette.text)
                setPadding(dp(16),0,dp(16),0);minimumHeight=dp(48)
                background=android.graphics.drawable.GradientDrawable().apply { setColor(MobilePalette.surface);cornerRadius=dp(12).toFloat() }
                MobileFeedback.apply(this);setOnClickListener { dialog.dismiss();onSelect(index) }
            },LinearLayout.LayoutParams(-1,dp(48)))
        }
        body.addView(ScrollView(context).apply { isVerticalScrollBarEnabled=false;addView(choices) },LinearLayout.LayoutParams(-1, minOf(labels.size*dp(48),(context.resources.displayMetrics.heightPixels*.55).toInt())))
        dialog.setContentView(body);dialog.show()
    }
}
