package app.qthrone.mobile.ui

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet

class HashTextPreference(context: Context) : EditTextPreference(context) {
    init { widgetLayoutResource = R.layout.qmobile_hash_copy }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.hash_copy)?.apply {
            isEnabled = !text.isNullOrBlank()
            setOnClickListener { text?.takeIf(String::isNotBlank)?.let { SagerNet.trySetPrimaryClip(it) } }
        }
    }
}
