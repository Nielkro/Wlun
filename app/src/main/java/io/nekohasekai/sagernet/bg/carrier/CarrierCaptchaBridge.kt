package app.qthrone.mobile.bg.carrier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.qthrone.mobile.ktx.app
import java.util.UUID

/** Private, same-app messages; captcha addresses and tokens never enter logs. */
class CarrierCaptchaBridge(private val process: Process, private val onlyIfUiIdle: Boolean = false,
    private val allowUi: Boolean = true): AutoCloseable {
    @Volatile var waiting=false
        private set
    @Volatile var interactionRequired=false
        private set
    private var receiver: BroadcastReceiver? = null
    private var requestId: String? = null
    private var pendingPost: Thread? = null
    private var command="CAPTCHA_RESULT|"
    private var closed=false
    private var cancelledByUser=false
    @Synchronized fun accept(line: String) {
        if(closed) return
        val turn=line.startsWith("VK_AUTH_REQUIRED|")
        if(!turn && !line.startsWith("CAPTCHA_SOLVE|")) return
        val fields=line.split('|',limit=4)
        val target=if(turn) fields.getOrNull(1).orEmpty() else fields.getOrNull(2).orEmpty()
        if(turn) { if(!target.matches(Regex("[A-Za-z0-9_-]{16,512}"))) return }
        else if(fields.size!=4 || target.length>8192) return
        finishRequest()
        command=if(turn) "TURN_CREDS|" else "CAPTCHA_RESULT|"
        val automatic=NativeCaptchaPolicy.automatic(line)
        if(!NativeCaptchaPolicy.permitted(allowUi,cancelledByUser,automatic)) {
            if(!allowUi) interactionRequired=true
            synchronized(process) { runCatching { process.outputStream.write((command+"error:cancelled\n").toByteArray());process.outputStream.flush() } }
            return
        }
        val id=UUID.randomUUID().toString()
        requestId=id
        waiting=true
        val callback=object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                respond(intent.getStringExtra("id"),intent.getStringExtra("result"))
            }
        }
        ContextCompat.registerReceiver(app,callback,IntentFilter(app.packageName+".CAPTCHA_RESULT"),app.packageName+".SERVICE",null,ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver=callback
        if(automatic) {
            // Native workers can authorize in parallel; one Android WebView owns the UI
            // request slot at a time. Queue automatic attempts instead of replacing a live request.
            pendingPost=Thread({
                val deadline=System.nanoTime()+NativeCaptchaPolicy.AUTOMATIC_QUEUE_MS*1_000_000
                while(System.nanoTime()<deadline) {
                    val posted=try {
                        synchronized(this@CarrierCaptchaBridge) {
                            if(closed || requestId!=id) return@Thread
                            CarrierUiRequests.post(id,target,turn,onlyIfIdle=true,automatic=true)
                        }
                    } catch(_:Exception) { respond(id,"error:VK confirmation unavailable");return@Thread }
                    if(posted) return@Thread
                    try { Thread.sleep(100) } catch(_:InterruptedException) { return@Thread }
                }
                respond(id,"error:timeout")
            },"qThrone-auto-captcha-queue").apply { isDaemon=true;start() }
        } else if (!CarrierUiRequests.post(id,target,turn,onlyIfIdle=onlyIfUiIdle || !allowUi)) {
            respond(id,"error:VK confirmation is busy")
        }
    }
    @Synchronized private fun respond(id: String?,result: String?) {
        if(id==null || id!=requestId || result==null || result.length>16384 || result.any { it=='\n' || it=='\r' }) return
        if(result=="error:cancelled") cancelledByUser=true
        synchronized(process) {
            runCatching { process.outputStream.write((command+result+"\n").toByteArray());process.outputStream.flush() }
        }
        finishRequest()
    }
    @Synchronized override fun close() {
        closed=true
        finishRequest()
    }
    private fun finishRequest() {
        pendingPost?.interrupt();pendingPost=null
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver=null
        waiting=false
        requestId?.let {
            CarrierUiRequests.clear(it)
            app.sendBroadcast(Intent(app.packageName+".CAPTCHA_CLOSE").setPackage(app.packageName).putExtra("id",it),app.packageName+".SERVICE")
        }
        requestId=null
    }
}
