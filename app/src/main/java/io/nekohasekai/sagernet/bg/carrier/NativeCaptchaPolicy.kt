package app.qthrone.mobile.bg.carrier

internal object NativeCaptchaPolicy {
    const val AUTOMATIC_TIMEOUT_MS = 10_000L
    const val AUTOMATIC_QUEUE_MS = 45_000L
    fun dismissal(automatic:Boolean):String = if(automatic) "error:timeout" else "error:cancelled"
    fun automatic(line:String):Boolean {
        val fields=line.split('|',limit=4)
        return fields.size==4 && fields[0]=="CAPTCHA_SOLVE" && fields[1]=="auto"
    }
    fun permitted(allowUi:Boolean,cancelled:Boolean,automatic:Boolean):Boolean =
        !cancelled && (allowUi || automatic)
}
