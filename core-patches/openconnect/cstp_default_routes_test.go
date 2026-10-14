package openconnect

import (
	"net/netip"
	"testing"
)

func TestCSTPDefaultRoutes(t *testing.T) {
	for _, tc := range []struct {
		name                      string
		addresses, includes, want []string
	}{
		{"IPv4 full tunnel", []string{"172.16.5.3/32"}, nil, []string{"0.0.0.0/0"}},
		{"IPv6 full tunnel", []string{"fd00::3/128"}, nil, []string{"::/0"}},
		{"dual stack full tunnel", []string{"172.16.5.3/32", "fd00::3/128"}, nil, []string{"0.0.0.0/0", "::/0"}},
		{"split include preserved", []string{"172.16.5.3/32"}, []string{"192.168.2.0/24"}, []string{"192.168.2.0/24"}},
		{"independent families", []string{"172.16.5.3/32", "fd00::3/128"}, []string{"192.168.2.0/24"}, []string{"192.168.2.0/24", "::/0"}},
		{"explicit default unchanged", []string{"172.16.5.3/32"}, []string{"0.0.0.0/0"}, []string{"0.0.0.0/0"}},
		{"no configured family", nil, nil, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var addresses []netip.Prefix
			var routes []TunnelRoute
			for _, value := range tc.addresses {
				addresses = append(addresses, netip.MustParsePrefix(value))
			}
			for _, value := range tc.includes {
				routes = append(routes, TunnelRoute{Prefix: netip.MustParsePrefix(value)})
			}
			got := cstpDefaultRoutes(addresses, routes)
			if len(got) != len(tc.want) {
				t.Fatalf("routes = %v, want %v", got, tc.want)
			}
			for i, want := range tc.want {
				if got[i].Prefix.String() != want {
					t.Fatalf("route %d = %s, want %s", i, got[i].Prefix, want)
				}
			}
		})
	}
}
