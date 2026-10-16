package app.qthrone.mobile.ui

/** Same checkbox auto-pass as the original Android client; a slider returns to the native fallback chain. */
internal object CaptchaAutoScripts {
    fun desktopUserAgent(engineUserAgent: String): String {
        val chrome = Regex("Chrome/[0-9.]+").find(engineUserAgent)?.value ?: "Chrome/146.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }
    val checkbox = """(() => {
        if(document.querySelector('[class*="SliderCaptcha"], [class*="Kaleidoscope"]')) return 'manual';
        const el=document.querySelector('label.vkc__Checkbox-module__Checkbox, label[for="not-robot-captcha-checkbox"]') || document.getElementById('not-robot-captcha-checkbox');
        if(!el) return null;
        const r=el.getBoundingClientRect(), s=getComputedStyle(el);
        if(r.width<5 || r.height<5 || s.display==='none' || s.visibility==='hidden') return null;
        return [r.left+r.width*.5,r.top+r.height*.5,window.innerWidth];
    })();"""
    val intercept = """(() => {
        if(window.__qthroneCaptchaHook) return; window.__qthroneCaptchaHook=true;
        const receive=text=>{try {
            const token=JSON.parse(text).response?.success_token;
            if(typeof token==='string' && token.length>0) {
                window.__qthroneCaptchaToken=token;
                if(window.QThroneCaptcha) window.QThroneCaptcha.postMessage(token);
            }
        }catch(e){}};
        const fetchOriginal=window.fetch;
        window.fetch=async function(...args){const response=await fetchOriginal.apply(this,args);if(response.url.includes('captchaNotRobot.check')) response.clone().text().then(receive);return response;};
        const sendOriginal=XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send=function(...args){this.addEventListener('load',()=>{if(this.responseURL.includes('captchaNotRobot.check') && (!this.responseType || this.responseType==='text')) receive(this.responseText);});return sendOriginal.apply(this,args);};
    })();"""
}
