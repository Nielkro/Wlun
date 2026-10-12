package app.qthrone.mobile.ui

import android.content.Context
import android.view.ContextThemeWrapper
import app.qthrone.mobile.utils.Theme
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Resolve the mobile palette even when a preference carries an older theme wrapper. */
class MobileAlertDialogBuilder(context: Context) : MaterialAlertDialogBuilder(
    ContextThemeWrapper(context, Theme.getTheme()).apply { Theme.apply(this) }
)
