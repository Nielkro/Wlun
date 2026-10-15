package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.types.Qwdtt

object NativeHashPolicy {
    fun subscriptionIdentity(profile: Qwdtt): String = profile.exportIdentity().also {
        it.remove("hashes")
        it.remove("workers")
    }.toCompact()
    fun defaultDeviceId(hwid: String): String = java.util.UUID.nameUUIDFromBytes(
        ("qThrone-native:"+hwid).toByteArray(Charsets.UTF_8)).toString()
    fun preserve(incoming: Qwdtt, previous: List<Qwdtt>) {
        val sameType = previous.filter { it.type == incoming.type }
        val named = if(incoming.name.isNotBlank()) sameType.filter { it.name == incoming.name } else emptyList()
        val matches = if(named.size == 1) named else sameType.filter {
            it.server == incoming.server && it.serverPort == incoming.serverPort && it.password == incoming.password
        }
        matches.singleOrNull()?.let {
            incoming.deviceId=it.deviceId
            incoming.deviceIdProvided=true
            incoming.hashSource = it.hashSource; incoming.hashSourceChosen = true
            // The subscription owns its leading slots; local trailing slots survive until replaced.
            if (it.hashSource == "manual") incoming.hashes = mergeSlots(incoming.hashes,it.hashes,
                if(incoming.type=="csqtt") 6 else 4)
        }
    }
    fun mergeSlots(subscription:List<String>, previous:List<String>,limit:Int):List<String> {
        val prefix=subscription.take(limit)
        return (prefix+previous.drop(prefix.size)).distinct().take(limit)
    }
}
