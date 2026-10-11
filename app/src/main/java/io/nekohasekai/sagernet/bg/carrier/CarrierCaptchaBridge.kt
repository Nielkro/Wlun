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
    fun accept(line: String) {
        if(!line.startsWith("CAPTCHA_SOLVE|")) return
        val fields=line.split('|',limit=4)
        if(fields.size!=4 || fields[2].length>8192) return
        close()
        val id=UUID.randomUUID().toString()
        requestId=id
        waiting=true
        val callback=object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(intent.getStringExtra("id")!=requestId) return
                val result=intent.getStringExtra("result") ?: return
                if(result.length>16384 || result.any { it=='\n' || it=='\r' }) return
                synchronized(process) {
                    runCatching { process.outputStream.write(("CAPTCHA_RESULT|"+result+"\n").toByteArray());process.outputStream.flush() }
                }
                close()
            }
        }
        ContextCompat.registerReceiver(app,callback,IntentFilter(app.packageName+".CAPTCHA_RESULT"),app.packageName+".SERVICE",null,ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver=callback
        app.sendBroadcast(Intent(app.packageName+".CAPTCHA_REQUEST").setPackage(app.packageName).putExtra("id",id).putExtra("url",fields[2]),app.packageName+".SERVICE")
    }
    override fun close() {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver=null
        waiting=false
        requestId?.let { app.sendBroadcast(Intent(app.packageName+".CAPTCHA_CLOSE").setPackage(app.packageName).putExtra("id",it),app.packageName+".SERVICE") }
        requestId=null
    }
}
