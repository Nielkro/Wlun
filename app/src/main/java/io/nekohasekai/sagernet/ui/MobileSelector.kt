package app.qthrone.mobile.ui

import android.content.Context
import android.view.Gravity
import android.widget.*
import androidx.preference.ListPreference

object MobileSelector {
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
