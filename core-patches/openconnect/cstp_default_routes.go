package openconnect

import "net/netip"

// CSTP without split-include routes means full tunnel for that address family.
// Match OpenConnect's vpnc-script; do not claim families without a tunnel address.
// Split-exclude routes remain authoritative and are applied by the endpoint.
func cstpDefaultRoutes(addresses []netip.Prefix, routes []TunnelRoute) []TunnelRoute {
	var hasIPv4, hasIPv6, includesIPv4, includesIPv6 bool
	for _, address := range addresses {
		hasIPv4 = hasIPv4 || address.Addr().Is4()
		hasIPv6 = hasIPv6 || address.Addr().Is6()
	}
	for _, route := range routes {
		includesIPv4 = includesIPv4 || route.Prefix.Addr().Is4()
		includesIPv6 = includesIPv6 || route.Prefix.Addr().Is6()
	}
	if hasIPv4 && !includesIPv4 {
		routes = append(routes, TunnelRoute{Prefix: netip.MustParsePrefix("0.0.0.0/0")})
	}
	if hasIPv6 && !includesIPv6 {
		routes = append(routes, TunnelRoute{Prefix: netip.MustParsePrefix("::/0")})
	}
	return routes
}
