package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.Outbound

/** qWDTT's netstack has IPv4 routes only (qwdtt/socks5.go: dialIPv4Only). Never change saved global settings. */
object TransportIpPolicy {
    fun ipv4Only(profile: Outbound, profiles: ProfileProvider): Boolean =
        profile.type == "qwdtt" || (profile.type == "chain" &&
            TypeAccess.chainHops(profile).any { profiles.get(it)?.type == "qwdtt" })

    fun settings(base: GeneratorSettings) = base.copy(
        ipv6Enabled = false, remoteDnsDisableIpv6 = true, fakeIpDisableIpv6 = true,
        resolveDomainStrategy = "ipv4_only",
    )
    fun context(base: BuildContext) = base.copy(directDnsDisableIpv6 = true, defaultDomainStrategy = "ipv4_only")
}
