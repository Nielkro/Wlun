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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.qthrone.mobile.ui.MobileSheetDialog as BottomSheetDialog

/** The captcha remains on its real HTTPS address in a bottom sheet. */
class MobileCaptchaController(private val host: FragmentActivity): AutoCloseable {
    private var dialog: BottomSheetDialog?=null
    private var currentId: String?=null
    private var cleanupWeb: (() -> Unit)?=null
    private var showOnResume=false
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            val id=intent.getStringExtra("id") ?: return
            if(intent.action==host.packageName+".CAPTCHA_CLOSE") {
                if(currentId==id) { val sheet=dialog;currentId=null;cleanupWeb?.invoke();sheet?.dismiss() }
            } else {
                // Only the visible activity owns the WebView; other hosts replay on resume.
                if(!host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
                if(currentId==id) {
                    if(showOnResume && host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) dialog?.show()
                    return
                }
                val url=intent.getStringExtra("url") ?: return
                val tokenState=intent.getStringExtra("tokenState")
                if(tokenState!=null) {
                    if(!allowed(url)) { reply(id,"error:invalid token address");return }
                    show(id,url,oauthState=tokenState);return
                }
                if(intent.getBooleanExtra("turn",false)) {
                    if(!url.matches(Regex("[A-Za-z0-9_-]{16,512}"))) { reply(id,"error:invalid call hash");return }
                    show(id,url,true);return
                }
                if(!allowed(url)) { reply(id,"error:invalid captcha address");return }
                show(id,url,automatic=intent.getBooleanExtra("automatic",false))
            }
        }
    }
    private val lifecycleObserver=androidx.lifecycle.LifecycleEventObserver { _,event ->
        if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) host.lifecycleScope.launch {
            val pending=withContext(Dispatchers.IO) { app.qthrone.mobile.bg.carrier.CarrierUiRequests.pending() }
            if(pending!=null && host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                receiver.onReceive(host,pending)
        }
    }
    init {
        val filter=IntentFilter(host.packageName+".CAPTCHA_REQUEST").apply { addAction(host.packageName+".CAPTCHA_CLOSE") }
        ContextCompat.registerReceiver(host,receiver,filter,host.packageName+".SERVICE",null,ContextCompat.RECEIVER_NOT_EXPORTED)
        host.lifecycle.addObserver(lifecycleObserver)
    }
    private fun allowed(value: String)=CaptchaAddressPolicy.allowed(value)
    private fun reply(id: String,result: String) {
        host.sendBroadcast(Intent(host.packageName+".CAPTCHA_RESULT").setPackage(host.packageName).putExtra("id",id).putExtra("result",result),host.packageName+".SERVICE")
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun show(id: String,url: String,turn: Boolean=false,oauthState: String?=null,automatic: Boolean=false) {
        if(app.qthrone.mobile.bg.carrier.CarrierUiRequests.pending()?.getStringExtra("id")!=id) return
        val previousSheet=dialog
        cleanupWeb?.invoke()
        previousSheet?.dismiss()
        currentId=id
        showOnResume=false
        val sheet=BottomSheetDialog(host)
        dialog=sheet
        val body=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(MobilePalette.surface) }
        val header=LinearLayout(host).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        header.addView(TextView(host).apply { text=if(turn || oauthState!=null) "VK · qWDTT" else "VK · Captcha";textSize=19f;setTextColor(MobilePalette.text);setPadding(24,20,24,20) },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(android.widget.ImageButton(host).apply {
            setImageResource(app.qthrone.mobile.R.drawable.ic_navigation_close)
            imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(host))
            background=null;contentDescription="Закрыть капчу"
            setOnClickListener { sheet.dismiss() }
        },LinearLayout.LayoutParams((48*host.resources.displayMetrics.density).toInt(),(48*host.resources.displayMetrics.density).toInt()))
        body.addView(header)
        var turnUrlIndex=0
        val web=WebView(host).apply {
            setBackgroundColor(MobilePalette.surface)
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.mediaPlaybackRequiresUserGesture=false
            settings.loadWithOverviewMode=true
            settings.useWideViewPort=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            if(turn || oauthState!=null) {
                settings.userAgentString=app.qthrone.mobile.bg.carrier.VkRelayLogin.userAgent(android.webkit.WebSettings.getDefaultUserAgent(host),0)
            }
            val hook=if(turn) VkTurnScripts.INTERCEPTOR else INTERCEPT
            if(turn && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER))
                androidx.webkit.WebViewCompat.addWebMessageListener(this,"QThroneVkTurn",
                    setOf("https://vk.ru","https://*.vk.ru","https://vk.com","https://*.vk.com")) { _,message,origin,_,_ ->
                    val trusted=runCatching { app.qthrone.mobile.bg.carrier.VkRelayOAuth.trustedHttps(java.net.URI(origin.toString())) }.getOrDefault(false)
                    val payload=if(trusted) runCatching { message.data }.getOrNull()?.let { VkTurnPayload.encode(url,it) } else null
                    if(payload!=null && currentId==id) {
                        reply(id,payload);currentId=null;cleanupWeb?.invoke();sheet.dismiss()
                    }
                }
            if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this,hook,setOf("https://vk.ru","https://*.vk.ru","https://vk.com","https://*.vk.com","https://ok.ru","https://*.ok.ru","https://*.okcdn.ru"))
            webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                    if(oauthState!=null && deliverToken(request.url.toString())) return true
                    return !allowed(request.url.toString())
                }
                private fun deliverToken(address: String): Boolean {
                    val state=oauthState ?: return false
                    val token=app.qthrone.mobile.bg.carrier.VkRelayOAuth.tokenFields(address,state) ?: return false
                    if(currentId==id) {
                        reply(id,org.json.JSONObject().put("token",token.first).put("expires",token.second).toString())
                        currentId=null;cleanupWeb?.invoke();sheet.dismiss()
                    }
                    return true
                }
                override fun onPageStarted(view: WebView,address: String,favicon: android.graphics.Bitmap?) {
                    if(allowed(address)) {
                        if(turn) view.evaluateJavascript(VkTurnScripts.AUTO_JOIN_RESET,null)
                        view.evaluateJavascript(hook,null)
                    }
                }
                override fun onPageFinished(view: WebView,address: String) {
                    if(oauthState!=null && deliverToken(address)) return
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
                if(oauthState==null && web.url?.let(::allowed)==true) web.evaluateJavascript(if(turn) "window.__qthroneTurnServer || null" else "window.__qthroneCaptchaToken || null") { raw ->
                    val token=runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                    val result=if(turn && token!=null) VkTurnPayload.encode(url,token) else token
                    if(!result.isNullOrEmpty() && currentId==id) { reply(id,result);currentId=null;cleanupWeb?.invoke();sheet.dismiss() }
                }
                web.postDelayed(this,400)
            }
        }
        val fallback=Runnable {
            if(currentId==id && !host.isFinishing) {
                if(automatic) {
                    reply(id,"error:timeout");currentId=null;cleanupWeb?.invoke();sheet.dismiss()
                    return@Runnable
                }
                showOnResume=true
                if(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) sheet.show()
            }
        }
        var cleaned=false
        val cleanup: () -> Unit = {
          if(!cleaned) {
            cleaned=true
            web.removeCallbacks(polling)
            web.removeCallbacks(fallback)
            if(currentId==id) { reply(id,app.qthrone.mobile.bg.carrier.NativeCaptchaPolicy.dismissal(automatic));currentId=null }
            if(automatic) (body.parent as? android.view.ViewGroup)?.removeView(body)
            web.stopLoading();web.destroy()
            if(dialog===sheet) dialog=null
            cleanupWeb=null
          }
        }
        cleanupWeb=cleanup
        sheet.setOnDismissListener { cleanup() }
        sheet.setContentView(body)
        // qWDTT first tries an invisible join for 22 seconds, then opens its WebView.
        if(automatic) {
            // A measured, attached WebView can execute VK's automatic challenge without
            // taking focus, receiving touches, or displaying a captcha sheet.
            (body.parent as? android.view.ViewGroup)?.removeView(body)
            body.alpha=0f
            body.translationX=-2f*host.resources.displayMetrics.widthPixels
            body.importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            (host.window.decorView as android.view.ViewGroup).addView(body,
                android.view.ViewGroup.LayoutParams(host.resources.displayMetrics.widthPixels,
                    (host.resources.displayMetrics.heightPixels*.65f).toInt()))
            web.postDelayed(fallback,app.qthrone.mobile.bg.carrier.NativeCaptchaPolicy.AUTOMATIC_TIMEOUT_MS)
        } else if(turn) web.postDelayed(fallback,22_000) else {
            showOnResume=true
            if(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) sheet.show()
        }
        val startUrl=if(turn) VkTurnScripts.joinUrlCandidates(url).first() else url
        val cookiePairs=if(turn || oauthState!=null) app.qthrone.mobile.bg.carrier.VkRelayAccount.sessionCookies().filterKeys(::allowed)
            .flatMap { (address,header) -> header.split(';').map { address to it.trim() }.filter { it.second.isNotEmpty() } } else emptyList()
        if(cookiePairs.isEmpty()) web.loadUrl(startUrl) else {
            val pending=java.util.concurrent.atomic.AtomicInteger(cookiePairs.size)
            for((address,cookie) in cookiePairs) android.webkit.CookieManager.getInstance().setCookie(address,cookie) {
                if(pending.decrementAndGet()==0 && currentId==id && !cleaned) {
                    android.webkit.CookieManager.getInstance().flush()
                    web.loadUrl(startUrl)
                }
            }
        }
        web.postDelayed(polling,400)
    }
    override fun close() { val sheet=dialog;cleanupWeb?.invoke();sheet?.dismiss();host.lifecycle.removeObserver(lifecycleObserver);runCatching { host.unregisterReceiver(receiver) } }
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
