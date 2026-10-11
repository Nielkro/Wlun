package app.qthrone.mobile.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.doOnLayout
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.databinding.LayoutLogcatBinding
import app.qthrone.mobile.ktx.*
import app.qthrone.mobile.widget.applyListInsets
import moe.matsuri.nb4a.utils.CoreLog
import moe.matsuri.nb4a.utils.LogExport
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    companion object {
        // "Hide destinations" lasts for the process; "Hide sensitive data" is logExportRedact.
        private var hideDestinations = false
    }

    lateinit var binding: LayoutLogcatBinding

    private val saveLogs = registerForActivityResult(SaveDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        val redact = DataStore.logExportRedact
        val destinations = hideDestinations
        runOnDefaultDispatcher {
            val error = try {
                val file = LogExport.build(context, redact, destinations)
                try {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                } finally {
                    file.delete()
                }
                null
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            onMainDispatcher {
                safeSnackbar(
                    if (error == null) context.getString(R.string.log_saved)
                    else context.getString(R.string.log_export_failed, error)
                )
            }
        }
    }

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_log)

        toolbar?.inflateMenu(R.menu.logcat_menu)
        toolbar?.setOnMenuItemClickListener(this)
        updateExportOptions()

        binding = LayoutLogcatBinding.bind(view)

        if (Build.VERSION.SDK_INT >= 23) {
            binding.textview.breakStrategy = 0 // simple
        }

        binding.scroolview.applyListInsets()

        reloadSession()
    }

    private fun updateExportOptions() {
        val menu = toolbar?.menu ?: return
        val redact = DataStore.logExportRedact
        menu.findItem(R.id.action_hide_sensitive)?.isChecked = redact
        menu.findItem(R.id.action_hide_destinations)?.apply {
            isEnabled = redact
            isChecked = redact && hideDestinations
        }
    }

    private fun getColorForLine(line: String): ForegroundColorSpan {
        var color = ForegroundColorSpan(Color.GRAY)
        when {
            line.contains("INFO[") || line.contains(" [Info]") -> {
                color = ForegroundColorSpan((0xFF86C166).toInt())
            }

            line.contains("ERROR[") || line.contains(" [Error]") -> {
                color = ForegroundColorSpan(Color.RED)
            }

            line.contains("WARN[") || line.contains(" [Warning]") -> {
                color = ForegroundColorSpan((0xFFFFA000).toInt())
            }
        }
        return color
    }

    private fun reloadSession() {
        val span = SpannableString(
            String(CoreLog.read(50 * 1024))
        )
        var offset = 0
        for (line in span.lines()) {
            val color = getColorForLine(line)
            span.setSpan(
                color, offset, offset + line.length, SPAN_EXCLUSIVE_EXCLUSIVE
            )
            offset += line.length + 1
        }
        binding.textview.text = span
        binding.textview.clearFocus()
        // Scroll to the bottom once the text view is laid out
        binding.textview.doOnLayout {
            binding.scroolview.scrollTo(0, binding.textview.height)
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                runOnDefaultDispatcher {
                    try {
                        CoreLog.clear()
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        binding.textview.text = ""
                    }
                }

            }

            R.id.action_share_logs -> {
                val context = requireContext()
                val redact = DataStore.logExportRedact
                val destinations = hideDestinations
                runOnDefaultDispatcher {
                    try {
                        val file = LogExport.build(context, redact, destinations)
                        onMainDispatcher {
                            SendLog.share(context, file, file.nameWithoutExtension)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            safeSnackbar(context.getString(R.string.log_export_failed, e.readableMessage))
                        }
                    }
                }
            }

            R.id.action_save_logs -> startFilesForResult(saveLogs, LogExport.fileName())

            R.id.action_hide_sensitive -> {
                DataStore.logExportRedact = !DataStore.logExportRedact
                updateExportOptions()
            }

            R.id.action_hide_destinations -> {
                hideDestinations = !hideDestinations
                updateExportOptions()
            }

            R.id.action_refresh -> {
                reloadSession()
            }
        }
        return true
    }

}
