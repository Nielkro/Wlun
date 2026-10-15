package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.json.JsonInput

internal object NativeSessionPolicy {
    fun sharesRemoteSession(firstKind:CarrierKind,first:String,secondKind:CarrierKind,second:String):Boolean {
        if(firstKind!=secondKind || firstKind !in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT)) return false
        val a=JsonInput.parseObject(first);val b=JsonInput.parseObject(second)
        return a.string("peer").isNotEmpty() && a.string("device_id").isNotEmpty() &&
            a.string("peer")==b.string("peer") && a.string("device_id")==b.string("device_id")
    }
    fun startupFailure(line:String):String? = when {
        line.contains("FATAL_AUTH:") -> "Сервер отклонил авторизацию: проверьте пароль, срок доступа и привязку устройства"
        line.contains("FATAL_PROTOCOL:") -> "Сервер отклонил несовместимый протокол подключения"
        else -> null
    }
}
