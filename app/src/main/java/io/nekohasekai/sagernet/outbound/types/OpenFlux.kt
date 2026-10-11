package app.qthrone.mobile.outbound.types
import app.qthrone.mobile.outbound.*
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.*
import app.qthrone.mobile.bg.carrier.*
import java.util.zip.Inflater
import java.util.zip.Deflater
import java.io.ByteArrayOutputStream

class OpenFlux:Outbound("openflux"),NativeCarrier {
    override val carrierKind get()=CarrierKind.OPENFLUX
    private var data=JsonObject()
    override fun displayType()="OpenFlux"
    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type")!="openflux" || obj.string("mode").isNotEmpty()) return false
        data=obj.copy();name=obj.string("name").ifEmpty { obj.string("tag") }
        if(!data.contains("codec")) data["codec"]="batched"
        if(!data.contains("negotiate")) data["negotiate"]=true
        for(item in data.array("transports")) {
            val t=item as? JsonObject ?: return false
            if(t.string("type")=="direct") {
                val endpoint=LinkParser.parse("tcp://"+t.string("dial"));server=endpoint.host;serverPort=endpoint.port
            }
            if(data.string("context").isEmpty() && t.string("url").isNotEmpty()) data["context"]=t.string("url")
        }
        if(data.string("context").isEmpty()) data["context"]="http://openflux"
        return valid()
    }
    private fun valid():Boolean {
        fun safe(s:String)=s.none { it in "\r\n#;" }
        val secret=data.string("secret")
        val transports=data.array("transports")
        if(secret.length<16 || secret.any { it in "\r\n" } || !safe(data.string("context"))) return false
        if(transports.size !in 1..8 || !data.bool("negotiate") || data.string("codec") !in listOf("batched","legacy")) return false
        val seen=mutableSetOf<String>()
        for(item in transports) {
            val t=item as? JsonObject ?: return false;val type=t.string("type")
            if(type !in listOf("direct","yandex","mailru","cupsonline") || !seen.add(type) || t.string("name").ifEmpty { type }!=type) return false
            val raw=if(type=="direct") t.string("dial") else t.string("url")
            val u=LinkParser.parse(if(type=="direct") "tcp://"+raw else raw)
            if(!safe(raw) || !u.isValid || u.host.isEmpty() || u.userName.isNotEmpty() || u.password.isNotEmpty()) return false
            if(type=="direct") { if(u.port !in 1..65535 || u.path.isNotEmpty()) return false }
            else if(u.scheme !in listOf("http","https")) return false
        }
        return true
    }
    override fun parseFromLink(link:String):Boolean {
        if(!link.startsWith("openflux://v1/",true) || link.length>32768) return false
        val packed=Base64Strict.decode(link.substring(14).substringBefore('#'),true) ?: return false
        val inflater=Inflater(true)
        return try {
            inflater.setInput(packed);val bytes=ByteArray(16385);val count=inflater.inflate(bytes)
            if(!inflater.finished() || inflater.remaining!=0 || count>16384) false else {
                val obj=JsonInput.parseObject(String(bytes,0,count,Charsets.UTF_8));obj["type"]="openflux"
                if(link.contains('#')) obj["name"]=LinkCodec.decodeFully(link.substringAfter('#'))
                parseFromJson(obj)
            }
        } catch(_:Exception) { false } finally { inflater.end() }
    }
    override fun exportToJson()=data.copy().also { it["type"]=type;it["name"]=name }
    override fun exportIdentity()=exportToJson().also { it.remove("name");it.remove("tag") }
    override fun exportToLink():String {
        if(!valid()) return ""
        val bytes=exportToJson().also { it.remove("type") }.toCompact().toByteArray()
        if(bytes.size>16384) return ""
        val deflater=Deflater(Deflater.DEFAULT_COMPRESSION,true)
        return try { deflater.setInput(bytes);deflater.finish();val buffer=ByteArray(16385);val count=deflater.deflate(buffer)
            if(!deflater.finished() || count>16384) "" else "openflux://v1/"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.copyOf(count))
        } finally { deflater.end() }
    }
    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"OpenFlux requires a prepared native transport bridge")
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials)=exportToJson().also {
        require(valid());it["socks"]="127.0.0.1:$port";it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
    }
}
