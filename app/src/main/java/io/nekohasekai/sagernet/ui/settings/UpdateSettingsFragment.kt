package app.qthrone.mobile.ui.settings

import android.content.Intent
import androidx.preference.Preference
import app.qthrone.mobile.Key
import app.qthrone.mobile.R
import app.qthrone.mobile.update.UpdateActivity
import app.qthrone.mobile.update.UpdateScheduler

class UpdateSettingsFragment : SettingsScreenFragment(R.xml.settings_updates) {
    override fun bind() {
        pref<Preference>(Key.UPDATE_CHECK_AUTO).setOnPreferenceChangeListener { _, value ->
            UpdateScheduler.schedule(enabled=value as Boolean)
            true
        }
        pref<Preference>("qmobileUpdate").setOnPreferenceClickListener {
            startActivity(Intent(requireContext(),UpdateActivity::class.java))
            true
        }
    }
}
