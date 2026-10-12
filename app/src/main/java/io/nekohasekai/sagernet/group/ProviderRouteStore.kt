package app.qthrone.mobile.group

import app.qthrone.mobile.database.*
import app.qthrone.mobile.route.ProviderRouting
import app.qthrone.mobile.outbound.json.*

object ProviderRouteStore {
    fun apply(gid: Long, commands: List<ProviderRouting.Command>, fullJson: Boolean = false) {
        if (commands.isEmpty()) return
        for (command in commands) command.document?.let {
            app.qthrone.mobile.route.ProviderGeoAssets.prepare(gid,it)
        }
        SagerDatabase.instance.runInTransaction {
            val group = GroupRepo.get(gid) ?: return@runInTransaction
            val options = group.subOptions
            val profiles = (JsonInput.parseValue(options.providerRoutingProfiles) as? JsonArray)?.toList()?.filterIsInstance<JsonObject>()?.toMutableList() ?: mutableListOf()
            for (command in commands) {
                options.routingProvider = command.provider
                if (command.off) { options.providerRouting = "off"; continue }
                val document = command.document ?: continue
                if (fullJson) profiles.removeAll { it.string("Name") != document.string("Name") }
                // Validate the complete replacement before changing the stored profile.
                ProviderRouting.profile(document, command.provider)
                val index = profiles.indexOfFirst { it.string("Name") == document.string("Name") }
                if (index >= 0) {
                    val previous = profiles[index]
                    val oldStamp = previous.variantLong("LastUpdated")
                    val stamp = document.variantLong("LastUpdated")
                    if (stamp > 0 && oldStamp > 0 && stamp < oldStamp) continue
                    profiles[index] = document
                    val selected = JsonInput.parseObjectOrNull(options.providerRouting)
                    if (selected?.string("Name") == document.string("Name")) options.providerRouting = document.toCompact()
                } else profiles.add(document)
                if (options.providerRouting.isBlank() || command.activate) options.providerRouting = document.toCompact()
            }
            options.providerRoutingProfiles = JsonArray().apply { profiles.forEach { add(it) } }.toCompact()
            SagerDatabase.groupDao.update(group)
        }
    }
}
