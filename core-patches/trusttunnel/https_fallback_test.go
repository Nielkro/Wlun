//go:build with_quic

package trusttunnel

import (
	"context"
	"errors"
	"net"
	"testing"

	"github.com/sagernet/sing/common/auth"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/stretchr/testify/require"
)

type blockedUDPDialer struct{ N.Dialer }

func (d blockedUDPDialer) DialContext(ctx context.Context, network string, server M.Socksaddr) (net.Conn, error) {
	if network == N.NetworkUDP {
		return nil, errors.New("UDP blocked")
	}
	return d.Dialer.DialContext(ctx, network, server)
}

func TestHTTPSFallbackWithBlockedUDPAndReset(t *testing.T) {
	serverTLS, clientTLS := generateTestTLSPair(t)
	s := newTestSetupWith(t, &testServerTLSConfig{config: serverTLS}, &testClientTLSConfig{config: clientTLS}, new(N.DefaultDialer))
	clientTLS = clientTLS.Clone()
	clientTLS.NextProtos = []string{"h3"}
	client, err := NewClient(ClientOptions{Ctx: t.Context(), Detour: blockedUDPDialer{new(N.DefaultDialer)}, Server: s.client.server,
		Auth: auth.User{Username: "test", Password: "test"}, TLSConfig: &testClientTLSConfig{config: clientTLS}, QUIC: true})
	require.NoError(t, err)
	t.Cleanup(func() { client.Close() })
	transport := client.roundTripper.(*httpsFallbackTransport)
	for range 2 {
		stream := openLiveTCP(t, client, []byte("one stream after fallback"))
		require.Same(t, transport.https.roundTripper, transport.selected)
		stream.Close()
		client.ResetConnections()
		require.Nil(t, transport.selected)
		require.Zero(t, trackerLen(transport.https.connTracker))
	}
	client.Close()
	err = client.HealthCheck(t.Context())
	require.ErrorIs(t, err, net.ErrClosed)
}

func TestHTTPSFallbackKeepsWorkingQUIC(t *testing.T) {
	s := newQUICTestSetup(t, new(N.DefaultDialer))
	transport := s.client.roundTripper.(*httpsFallbackTransport)
	for range 2 {
		stream := openLiveTCP(t, s.client, []byte("use real QUIC"))
		require.Same(t, transport.quic, transport.selected)
		require.Zero(t, trackerLen(transport.https.connTracker))
		stream.Close()
		s.client.ResetConnections()
	}
}
