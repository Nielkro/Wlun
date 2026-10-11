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
import com.google.android.material.bottomsheet.BottomSheetDialog

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
                if(!allowed(url)) { reply(id,"error:invalid captcha address");return }
                show(id,url)
            }
        }
    }
    init {
        val filter=IntentFilter(host.packageName+".CAPTCHA_REQUEST").apply { addAction(host.packageName+".CAPTCHA_CLOSE") }
        ContextCompat.registerReceiver(host,receiver,filter,host.packageName+".SERVICE",null,ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun allowed(value: String): Boolean {
        val uri=Uri.parse(value)
        val domain=uri.host?.lowercase() ?: return false
        return uri.scheme=="https" && uri.userInfo==null && uri.port in listOf(-1,443) &&
            listOf("vk.com","vk.ru","ok.ru","okcdn.ru").any { domain==it || domain.endsWith("."+it) }
    }
    private fun reply(id: String,result: String) {
        host.sendBroadcast(Intent(host.packageName+".CAPTCHA_RESULT").setPackage(host.packageName).putExtra("id",id).putExtra("result",result),host.packageName+".SERVICE")
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun show(id: String,url: String) {
        dialog?.dismiss()
        currentId=id
        val sheet=BottomSheetDialog(host)
        dialog=sheet
        val body=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(MobilePalette.surface) }
        body.addView(TextView(host).apply { text="VK · Captcha";textSize=19f;setTextColor(MobilePalette.text);setPadding(24,20,24,20) })
        val web=WebView(host).apply {
            setBackgroundColor(MobilePalette.surface)
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest)= !allowed(request.url.toString())
                override fun onPageFinished(view: WebView,address: String) {
                    if(allowed(address)) view.evaluateJavascript(INTERCEPT,null)
                }
            }
        }
        body.addView(web,LinearLayout.LayoutParams(-1,(host.resources.displayMetrics.heightPixels*.65f).toInt()))
        val polling=object: Runnable {
            override fun run() {
                if(currentId!=id) return
                if(web.url?.let(::allowed)==true) web.evaluateJavascript("window.__qthroneCaptchaToken || null") { raw ->
                    val token=runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                    if(!token.isNullOrEmpty() && currentId==id) { reply(id,token);currentId=null;sheet.dismiss() }
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
        web.loadUrl(url)
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
