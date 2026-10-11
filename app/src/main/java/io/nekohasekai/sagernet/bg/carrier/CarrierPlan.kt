package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.types.Qwdtt
import app.qthrone.mobile.outbound.types.Socks
import java.net.ServerSocket
import java.net.DatagramSocket
import java.net.InetAddress

/** One generation owns its bridge; no runtime secret is written back to a stored profile. */
class CarrierPlan private constructor(
    val profileId:Long, val kind:CarrierKind, val port:Int,
    val credentials:CarrierBridgeCredentials, val privateJson:String,
) {
    fun outbound()=Socks().also {
        it.server="127.0.0.1";it.serverPort=port
        it.username=credentials.username;it.password=credentials.password
    }
    companion object {
        fun qwdtt(profileId:Long, profile:Qwdtt):CarrierPlan {
            val loopback=InetAddress.getByName("127.0.0.1")
            val credentials=CarrierBridgeCredentials.fresh()
            return ServerSocket(0,1,loopback).use { tcp -> DatagramSocket(0,loopback).use { udp ->
                CarrierPlan(profileId,CarrierKind.QWDTT,tcp.localPort,credentials,
                    profile.privateConfig(tcp.localPort,udp.localPort,credentials).toCompact())
            } }
        }
    }
}
