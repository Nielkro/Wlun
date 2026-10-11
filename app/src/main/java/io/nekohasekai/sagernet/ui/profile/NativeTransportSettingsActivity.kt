package app.qthrone.mobile.ui.profile
import app.qthrone.mobile.R
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.types.Qwdtt

/** Keeps the actual native profile type while using the existing complete JSON editor. */
class NativeTransportSettingsActivity:BindingSettingsActivity<Outbound>() {
    override fun createEntity():Outbound=Qwdtt()
    override val preferencesResource=R.xml.native_transport_preferences
    init { pbm.text("name") }
}
