package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.types.Qwdtt
import app.qthrone.mobile.outbound.types.WdttPlus
import app.qthrone.mobile.outbound.OutboundFactory

object NativeHashPolicy {
    /** All three clients share the qWDTT account binding, never their own account-login flow. */
    fun supportsAutomatic(profile: Qwdtt) = profile.type in listOf("qwdtt", "csqtt", "wdttplus")

    fun applyDefaultSource(profile: Qwdtt, source: String?) {
        if (!supportsAutomatic(profile) || profile.hashSourceChosen) return
        profile.hashSource = if (source == "auto") "auto" else "manual"
        profile.hashSourceChosen = true
    }

    /** A launch-only copy keeps account calls out of stored profiles, exports and subscriptions. */
    fun runtimeProfile(profile: Qwdtt, obtainHashes: (Int) -> List<String>): Qwdtt {
        if (!supportsAutomatic(profile) || profile.hashSource != "auto") return profile
        val copy = OutboundFactory.newByType(profile.type) as Qwdtt
        check(copy.parseFromJson(profile.exportToJson()))
        val count = ((copy.workers + 26) / 27).coerceIn(1, if (profile.type == "csqtt") 6 else 4)
        val hashes = obtainHashes(count)
        check(hashes.size == count && hashes.distinct().size == count && hashes.all {
            it.matches(Regex("[A-Za-z0-9_-]{16,512}"))
        }) { "VK не вернул необходимые хеши звонков" }
        copy.hashes = hashes.toList()
        copy.hashSource = "manual"
        return copy
    }

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
            incoming.workers=preservedWorkers(it.workers,workerLimit(it),workerLimit(incoming))
            // WG/RAW and TURN over TCP/UDP are the user's choice, kept like hashes and workers.
            incoming.mode=it.mode
            incoming.turnTcp=it.turnTcp
        }
    }
    fun workerLimit(profile:Qwdtt):Int {
        val slots=if(profile.type=="csqtt") 6 else 4
        val hashes=if(profile.hashSource=="auto") slots else profile.hashes.size.coerceIn(1,slots)
        // WDTT Plus groups 9 workers each up to 108 whatever the hash count.
        if(profile is WdttPlus) return (profile.maxWorkers / 9 * 9).takeIf { it >= 9 }?.coerceAtMost(108) ?: 108
        return (hashes*27).coerceAtMost(if(profile.type=="csqtt") 126 else 108)
    }
    fun preservedWorkers(previous:Int,previousLimit:Int,currentLimit:Int):Int =
        if(previous>=previousLimit) currentLimit else previous.coerceIn(9,currentLimit)/9*9
    fun mergeSlots(subscription:List<String>, previous:List<String>,limit:Int):List<String> {
        val prefix=subscription.take(limit)
        return (prefix+previous.drop(prefix.size)).distinct().take(limit)
    }
}
