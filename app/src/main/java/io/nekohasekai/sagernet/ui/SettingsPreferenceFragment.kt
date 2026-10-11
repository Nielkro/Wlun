package app.qthrone.mobile.ui

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.ktx.FixedLinearLayoutManager
import app.qthrone.mobile.ktx.confirmAction
import app.qthrone.mobile.ktx.triggerFullRestart

/**
 * The settings root: one entry per sub-screen (app.qthrone.mobile.ui.settings, opened by [SettingsFragment]),
 * the backup screen and the reset action.
 */
open class SettingsPreferenceFragment(private val advanced: Boolean = false) : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        app.qthrone.mobile.ui.MobileSettingsStyle.apply(this, view)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        addPreferencesFromResource(if (advanced) R.xml.global_preferences else R.xml.qmobile_settings)
        findPreference<Preference>("qmobileRouting")?.setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragment(MobileRoutingFragment())
            true
        }

        findPreference<Preference>(KEY_BACKUP)!!.setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_tools)
            true
        }

        findPreference<Preference>(KEY_RESET_SETTINGS)!!.setOnPreferenceClickListener {
            requireContext().confirmAction(
                getString(R.string.confirm_reset_settings),
                getString(R.string.reset_settings_message),
                R.string.confirm_restore,
            ) {
                DataStore.configurationStore.reset()
                triggerFullRestart(requireContext())
            }
            true
        }
    }

    private companion object {
        const val KEY_BACKUP = "settingsBackup"
        const val KEY_RESET_SETTINGS = "resetSettings"
    }
}

class AdvancedSettingsPreferenceFragment : SettingsPreferenceFragment(true)
