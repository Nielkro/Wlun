package app.qthrone.mobile.bg.carrier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.qthrone.mobile.ktx.app
import java.util.UUID

/** Private, same-app messages; captcha addresses and tokens never enter logs. */
class CarrierCaptchaBridge(private val process: Process): AutoCloseable {
    @Volatile var waiting=false
        private set
    private var receiver: BroadcastReceiver? = null
    private var requestId: String? = null
    private var command="CAPTCHA_RESULT|"
    @Synchronized fun accept(line: String) {
        val turn=line.startsWith("VK_AUTH_REQUIRED|")
        if(!turn && !line.startsWith("CAPTCHA_SOLVE|")) return
        val fields=line.split('|',limit=4)
        val target=if(turn) fields.getOrNull(1).orEmpty() else fields.getOrNull(2).orEmpty()
        if(turn) { if(!target.matches(Regex("[A-Za-z0-9_-]{16,512}"))) return }
        else if(fields.size!=4 || target.length>8192) return
        close()
        command=if(turn) "TURN_CREDS|" else "CAPTCHA_RESULT|"
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
        app.sendBroadcast(Intent(app.packageName+".CAPTCHA_REQUEST").setPackage(app.packageName).putExtra("id",id).putExtra("url",target).putExtra("turn",turn),app.packageName+".SERVICE")
    }
    @Synchronized private fun respond(id: String?,result: String?) {
        if(id==null || id!=requestId || result==null || result.length>16384 || result.any { it=='\n' || it=='\r' }) return
        synchronized(process) {
            runCatching { process.outputStream.write((command+result+"\n").toByteArray());process.outputStream.flush() }
        }
        close()
    }
    @Synchronized override fun close() {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver=null
        waiting=false
        requestId?.let { app.sendBroadcast(Intent(app.packageName+".CAPTCHA_CLOSE").setPackage(app.packageName).putExtra("id",it),app.packageName+".SERVICE") }
        requestId=null
    }
}
