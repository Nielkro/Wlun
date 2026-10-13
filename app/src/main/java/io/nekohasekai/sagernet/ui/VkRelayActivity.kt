package app.qthrone.mobile.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.*
import app.qthrone.mobile.R
import app.qthrone.mobile.bg.carrier.VkRelayAccount
import app.qthrone.mobile.database.DataStore
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class VkRelayActivity : ThemedActivity() {
    private var web: WebView? = null
    @SuppressLint("SetJavaScriptEnabled") override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.background) }
        val toolbar = Toolbar(this).apply {
            title = "Настройки VK-релея"; setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
        }
        val content = FrameLayout(this).apply { id = android.R.id.content + 2 }
        root.addView(toolbar); root.addView(content,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        if(intent.getBooleanExtra("login",false)) {
            val oauthState = java.util.UUID.randomUUID().toString()
            web = WebView(this).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    private var completed = false
                    override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if(uri.scheme != "https") return true
                        if(uri.host in listOf("oauth.vk.ru","oauth.vk.com") && uri.path == "/blank.html") {
                            val fields = android.net.Uri.parse("https://localhost/?"+uri.fragment.orEmpty())
                            val token = fields.getQueryParameter("access_token")
                            if(!completed && !token.isNullOrBlank() && fields.getQueryParameter("state") == oauthState) {
                                completed = true
                                lifecycleScope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching {
                                        VkRelayAccount.link(token,fields.getQueryParameter("user_id").orEmpty(),fields.getQueryParameter("expires_in")?.toLongOrNull() ?: 0)
                                    } }
                                    Toast.makeText(this@VkRelayActivity,if(result.isSuccess) "Аккаунт VK привязан" else "Не удалось сохранить аккаунт VK",Toast.LENGTH_LONG).show()
                                    if(result.isSuccess) { CookieManager.getInstance().flush(); finish() }
                                }
                            }
                            return true
                        }
                        val host = uri.host.orEmpty()
                        return !(host == "vk.ru" || host.endsWith(".vk.ru") || host == "vk.com" || host.endsWith(".vk.com"))
                    }
                }
                loadUrl("https://oauth.vk.ru/authorize?client_id=7793118&scope=1073737727&redirect_uri=https%3A%2F%2Foauth.vk.ru%2Fblank.html&display=mobile&response_type=token&v=5.199&state=$oauthState")
            }
            content.addView(web,FrameLayout.LayoutParams(-1,-1))
        } else if(state == null) supportFragmentManager.beginTransaction().replace(content.id,Screen()).commit()
    }
    override fun onDestroy() { web?.apply { stopLoading(); (parent as? android.view.ViewGroup)?.removeView(this); destroy() }; web = null; super.onDestroy() }
    class Screen : PreferenceFragmentCompat() {
        override fun onCreatePreferences(state: Bundle?,rootKey: String?) { preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()); build() }
        override fun onResume() { super.onResume(); build() }
        private fun build() { VkRelayPreferences.bind(this,preferenceScreen) }
    }
}
