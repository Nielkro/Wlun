package app.qthrone.mobile.ui

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreferenceDialogFragmentCompat

/** Keep preference validation and input modifiers while presenting the mobile dialog surface. */
class MobilePreferenceDialog : EditTextPreferenceDialogFragmentCompat() {
    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        val density = resources.displayMetrics.density
        alert.window?.setBackgroundDrawable(InsetDrawable(GradientDrawable().apply {
            setColor(MobilePalette.surface)
            cornerRadius = 20 * density
        }, (24 * density).toInt()))
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            alert.getButton(which)?.setTextColor(MobilePalette.accent(requireContext()))
        }
        alert.findViewById<android.widget.EditText>(android.R.id.edit)?.apply {
            setTextColor(MobilePalette.text)
            backgroundTintList = android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
        }
    }

    companion object {
        fun show(fragment: androidx.preference.PreferenceFragmentCompat, preference: androidx.preference.EditTextPreference) {
            if (fragment.parentFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG") != null) return
            if(preference.dialogTitle.isNullOrBlank()) preference.dialogTitle = preference.title
            MobilePreferenceDialog().apply {
                arguments = Bundle().apply { putString("key", preference.key) }
                @Suppress("DEPRECATION")
                setTargetFragment(fragment, 0)
            }.show(fragment.parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
        }
    }
}
