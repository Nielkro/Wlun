package app.qthrone.mobile.ui

import android.content.res.ColorStateList
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.qthrone.mobile.R
import app.qthrone.mobile.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Presents the core's authentication continuation; answers are never stored or written to logs. */
class OpenConnectAuthDialog(private val activity: MainActivity) {
    private var dialog: AlertDialog? = null
    private var shownRequest: String? = null

    fun start() = activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) {
                    val service = activity.connection.service
                    val request = withContext(Dispatchers.IO) {
                        runCatching { service?.openConnectAuth().orEmpty() }.getOrDefault("")
                    }
                    if (request.isEmpty()) {
                        dialog?.dismiss()
                        dialog = null
                        shownRequest = null
                    } else {
                        runCatching { JSONObject(request) }.getOrNull()?.let { data ->
                            val challenge = data.getJSONObject("Challenge")
                            val key = data.getString("Session") + ":" + data.getString("Tag") + ":" + challenge.getString("ID")
                            if (shownRequest != key) {
                                dialog?.dismiss()
                                shownRequest = key
                                show(data)
                            }
                        }
                    }
                    delay(1000)
                }
            } finally {
                // Rotation/backgrounding must not cancel the server challenge.
                dialog?.dismiss()
                dialog = null
                shownRequest = null
            }
        }
    }

    private fun show(data: JSONObject) {
        val challenge = data.getJSONObject("Challenge")
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val answers = linkedMapOf<String, () -> String>()
        val browser = !challenge.isNull("Browser")
        val form = challenge.optJSONObject("Form")
        val supported = !browser && form != null
        for (name in listOf("Banner", "Message", "Error")) {
            val message = challenge.optString(name).take(4096)
            if (message.isNotBlank()) fields.addView(TextView(activity).apply {
                text = message
                setTextColor(MobilePalette.text)
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        if (!supported) fields.addView(TextView(activity).apply {
            setText(if (browser) R.string.openconnect_browser_required else R.string.openconnect_auth_unsupported)
            setTextColor(MobilePalette.text)
        })
        val inputs = form?.optJSONArray("Fields")
        var supportedFields = supported
        if (supported && inputs != null) for (i in 0 until inputs.length()) {
            val field = inputs.getJSONObject(i)
            val key = field.getString("SubmissionKey")
            fields.addView(TextView(activity).apply {
                text = field.optString("Label").ifBlank { field.optString("Name") }.take(256)
                setTextColor(MobilePalette.muted)
                setPadding(0, dp(12), 0, 0)
            })
            when (field.getString("Kind")) {
                "text", "password" -> {
                    val input = EditText(activity).apply {
                        inputType = if (field.getString("Kind") == "password")
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        setSingleLine()
                        setText(field.optString("Value"))
                        setTextColor(MobilePalette.text)
                        backgroundTintList = ColorStateList.valueOf(MobilePalette.accent(activity))
                        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                            setColor(MobilePalette.accent(activity)); setSize(dp(2), dp(24))
                        }
                    }
                    fields.addView(input)
                    answers[key] = { input.text.toString() }
                }
                "select" -> {
                    val choices = field.optJSONArray("Options")
                    val values = (0 until (choices?.length() ?: 0)).map { choices!!.getJSONObject(it).getString("Value") }
                    val labels = (0 until (choices?.length() ?: 0)).map {
                        choices!!.getJSONObject(it).optString("Label").ifBlank { values[it] }
                    }
                    val input = Spinner(activity).apply {
                        adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, labels) {
                            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(MobilePalette.text) }
                            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                                super.getDropDownView(position, convertView, parent).also {
                                    (it as TextView).setTextColor(MobilePalette.text); it.setBackgroundColor(MobilePalette.surface)
                                }
                        }
                        setSelection(values.indexOf(field.optString("Value")).coerceAtLeast(0))
                    }
                    fields.addView(input)
                    answers[key] = { values.getOrElse(input.selectedItemPosition) { "" } }
                    if (values.isEmpty()) supportedFields = false
                }
                else -> supportedFields = false
            }
        }
        val builder = MobileAlertDialogBuilder(activity)
            .setTitle(R.string.openconnect_auth_title)
            .setView(ScrollView(activity).apply { addView(fields) })
            .setNegativeButton(android.R.string.cancel) { _, _ -> reply(data, JSONObject(), true) }
        if (supportedFields) builder.setPositiveButton(android.R.string.ok, null)
        else builder.setPositiveButton(android.R.string.ok) { _, _ -> }
        dialog = builder.create().also { alert ->
            alert.setOnCancelListener { reply(data, JSONObject(), true) }
            alert.show()
            for (button in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE))
                alert.getButton(button)?.setTextColor(MobilePalette.accent(activity))
            if (supportedFields) alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = JSONObject()
                answers.forEach { (key, value) -> values.put(key, value()) }
                reply(data, values, false)
            }
        }
    }

    private fun reply(data: JSONObject, values: JSONObject, cancel: Boolean) {
        val service = activity.connection.service ?: return
        val currentDialog = dialog
        currentDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    service.replyOpenConnectAuth(data.getString("Session"), data.getString("Tag"),
                        data.getJSONObject("Challenge").getString("ID"), values.toString(), cancel).orEmpty()
                } catch (e: Exception) { e.readableMessage }
            }
            if (dialog !== currentDialog) return@launch
            if (error.isEmpty()) { currentDialog?.dismiss(); dialog = null }
            else {
                currentDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
