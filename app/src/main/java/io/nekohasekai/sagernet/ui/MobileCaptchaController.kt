package app.qthrone.mobile.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.qthrone.mobile.ui.MobileSheetDialog as BottomSheetDialog

/** The captcha remains on its real HTTPS address in a bottom sheet. */
class MobileCaptchaController(private val host: FragmentActivity): AutoCloseable {
    private var dialog: BottomSheetDialog?=null
    private var currentId: String?=null
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            val id=intent.getStringExtra("id") ?: return
            if(intent.action==host.packageName+".CAPTCHA_CLOSE") {
                if(currentId==id) { currentId=null;dialog?.dismiss() }
            } else {
                val url=intent.getStringExtra("url") ?: return
                if(intent.getBooleanExtra("turn",false)) {
                    if(!url.matches(Regex("[A-Za-z0-9_-]{16,512}"))) { reply(id,"error:invalid call hash");return }
                    show(id,url,true);return
                }
                if(!allowed(url)) { reply(id,"error:invalid captcha address");return }
                show(id,url)
            }
        }
    }
    init {
        val filter=IntentFilter(host.packageName+".CAPTCHA_REQUEST").apply { addAction(host.packageName+".CAPTCHA_CLOSE") }
        ContextCompat.registerReceiver(host,receiver,filter,host.packageName+".SERVICE",null,ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun allowed(value: String)=CaptchaAddressPolicy.allowed(value)
    private fun reply(id: String,result: String) {
        host.sendBroadcast(Intent(host.packageName+".CAPTCHA_RESULT").setPackage(host.packageName).putExtra("id",id).putExtra("result",result),host.packageName+".SERVICE")
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun show(id: String,url: String,turn: Boolean=false) {
        dialog?.dismiss()
        currentId=id
        val sheet=BottomSheetDialog(host)
        dialog=sheet
        val body=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(MobilePalette.surface) }
        body.addView(TextView(host).apply { text=if(turn) "VK · qWDTT" else "VK · Captcha";textSize=19f;setTextColor(MobilePalette.text);setPadding(24,20,24,20) })
        var turnUrlIndex=0
        val web=WebView(host).apply {
            setBackgroundColor(MobilePalette.surface)
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            if(turn) {
                settings.userAgentString=app.qthrone.mobile.bg.carrier.VkRelayLogin.userAgent(android.webkit.WebSettings.getDefaultUserAgent(host),0)
                for((address,cookie) in app.qthrone.mobile.bg.carrier.VkRelayAccount.sessionCookies())
                    if(allowed(address)) android.webkit.CookieManager.getInstance().setCookie(address,cookie)
            }
            val hook=if(turn) VkTurnScripts.INTERCEPTOR else INTERCEPT
            if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this,hook,setOf("https://vk.ru","https://*.vk.ru","https://vk.com","https://*.vk.com","https://ok.ru","https://*.ok.ru","https://*.okcdn.ru"))
            webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest)= !allowed(request.url.toString())
                override fun onPageStarted(view: WebView,address: String,favicon: android.graphics.Bitmap?) {
                    if(allowed(address)) {
                        if(turn) view.evaluateJavascript(VkTurnScripts.AUTO_JOIN_RESET,null)
                        view.evaluateJavascript(hook,null)
                    }
                }
                override fun onPageFinished(view: WebView,address: String) {
                    if(allowed(address)) {
                        view.evaluateJavascript(hook,null)
                        if(turn) {
                            view.evaluateJavascript(VkTurnScripts.AUTO_JOIN_SETUP,null)
                            view.evaluateJavascript(VkTurnScripts.PAGE_404_CHECK) { value ->
                                if(value=="\"404\"" && currentId==id && turnUrlIndex<VkTurnScripts.joinUrlCandidates(url).lastIndex) {
                                    turnUrlIndex++
                                    view.loadUrl(VkTurnScripts.joinUrlCandidates(url)[turnUrlIndex])
                                }
                            }
                        }
                    }
                }
            }
        }
        body.addView(web,LinearLayout.LayoutParams(-1,(host.resources.displayMetrics.heightPixels*.65f).toInt()))
        val polling=object: Runnable {
            override fun run() {
                if(currentId!=id) return
                if(turn && web.url?.let(::allowed)==true) web.evaluateJavascript(VkTurnScripts.AUTO_JOIN_TRY,null)
                if(web.url?.let(::allowed)==true) web.evaluateJavascript(if(turn) "window.__qthroneTurnServer || null" else "window.__qthroneCaptchaToken || null") { raw ->
                    val token=runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                    val result=if(turn && token!=null) VkTurnPayload.encode(url,token) else token
                    if(!result.isNullOrEmpty() && currentId==id) { reply(id,result);currentId=null;sheet.dismiss() }
                }
                web.postDelayed(this,400)
            }
        }
        sheet.setOnDismissListener {
            web.removeCallbacks(polling)
            if(currentId==id) { reply(id,"error:cancelled");currentId=null }
            web.stopLoading();web.destroy()
            if(dialog===sheet) dialog=null
        }
        sheet.setContentView(body);sheet.show()
        web.loadUrl(if(turn) VkTurnScripts.joinUrlCandidates(url).first() else url)
        web.postDelayed(polling,400)
    }
    override fun close() { dialog?.dismiss();runCatching { host.unregisterReceiver(receiver) } }
    private companion object {
        // Capture only the captcha API's success field, without exposing a Java interface to page frames.
        val INTERCEPT="""(() => {
          if(window.__qthroneCaptchaHook) return; window.__qthroneCaptchaHook=true;
          const receive=(text)=>{try {const result=JSON.parse(text);const token=result.response?.success_token;if(typeof token==='string') window.__qthroneCaptchaToken=token;}catch(e){}};
          const fetchOriginal=window.fetch;
          window.fetch=async function(...args){const response=await fetchOriginal.apply(this,args);if(response.url.includes('captchaNotRobot.check')) response.clone().text().then(receive);return response;};
          const sendOriginal=XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send=function(...args){this.addEventListener('load',()=>{if(this.responseURL.includes('captchaNotRobot.check') && (!this.responseType || this.responseType==='text')) receive(this.responseText);});return sendOriginal.apply(this,args);};
        })();"""
    }
}
