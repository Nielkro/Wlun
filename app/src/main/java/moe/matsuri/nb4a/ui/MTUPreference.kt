package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.text.InputType
import android.widget.EditText
import androidx.preference.ListPreference
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.R

class MTUPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : ListPreference(context, attrs, defStyle, 0) {
    init { setSummaryProvider { value.orEmpty() } }

    override fun onClick() {
        val options=arrayOf("1500", "9000", context.getString(R.string.qmobile_mtu_manual))
        val selected=when(value) { "1500" -> 0; "9000" -> 1; else -> 2 }
        MaterialAlertDialogBuilder(context).setTitle("MTU")
            .setSingleChoiceItems(options,selected) { dialog, index ->
                dialog.dismiss()
                if(index==2) showCustom() else save(options[index])
            }.show()
    }

    private fun save(raw: String) {
        if(callChangeListener(raw)) value=raw
    }

    private fun showCustom() {
        val input=EditText(context).apply {
            inputType=InputType.TYPE_CLASS_NUMBER
            setText(value.orEmpty()); selectAll()
            setPadding(48,24,48,24)
        }
        val dialog=MaterialAlertDialogBuilder(context).setTitle("MTU").setView(input)
            .setPositiveButton(android.R.string.ok,null)
            .setNegativeButton(android.R.string.cancel,null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mtu=input.text.toString().toIntOrNull()
                if(mtu==null || mtu !in 1000..10000) {
                    input.error=context.getString(R.string.qmobile_mtu_range)
                } else { save(mtu.toString()); dialog.dismiss() }
            }
            for(button in listOf(-1,-2)) dialog.getButton(button).setTextColor(app.qthrone.mobile.ui.MobilePalette.accent(context))
        }
        dialog.show()
    }
}
