package trusttunnel

import (
	"context"
	"net"
	"net/http"
	"sync"
	"time"
)

// Probe QUIC before sending an application stream. Never replay application data.
type httpsFallbackTransport struct {
	access   sync.Mutex
	quic     RoundTripper
	https    *Client
	owner    *Client
	selected RoundTripper
	closed   bool
}

func (c *Client) enableHTTPSFallback(options ClientOptions) error {
	options.QUIC = false
	options.HealthCheck = false
	options.TLSConfig = options.TLSConfig.Clone()
	options.TLSConfig.SetNextProtos([]string{"h2"})
	https, err := NewClient(options)
	if err != nil {
		return err
	}
	c.roundTripper = &httpsFallbackTransport{quic: c.roundTripper, https: https, owner: c}
	return nil
}

func (t *httpsFallbackTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	t.access.Lock()
	if t.closed {
		t.access.Unlock()
		return nil, net.ErrClosed
	}
	if t.selected == nil {
		ctx, cancel := context.WithTimeout(request.Context(), 3*time.Second)
		probe := newRequest(t.owner.server.String(), HealthCheckMagicAddress, nil).WithContext(ctx)
		probe.Header.Set("Proxy-Authorization", t.owner.auth)
		probe.Header.Set("User-Agent", t.owner.userAgents.HealthCheckUserAgent)
		response, err := t.quic.RoundTrip(probe)
		if response != nil {
			response.Body.Close()
		}
		cancel()
		if request.Context().Err() != nil {
			t.access.Unlock()
			return nil, request.Context().Err()
		}
		// An HTTP response proves QUIC works, even if authentication was rejected.
		// Do not mask an authentication failure by retrying it over another transport.
		if err == nil {
			t.selected = t.quic
		} else {
			t.quic.CloseIdleConnections()
			_ = t.owner.connTracker.Close()
			t.selected = t.https.roundTripper
		}
	}
	selected := t.selected
	t.access.Unlock()
	return selected.RoundTrip(request)
}

func (t *httpsFallbackTransport) CloseIdleConnections() {
	t.access.Lock()
	defer t.access.Unlock()
	t.quic.CloseIdleConnections()
	t.https.ResetConnections()
	t.selected = nil
}

func (t *httpsFallbackTransport) Close() error {
	t.access.Lock()
	defer t.access.Unlock()
	t.closed = true
	if closer, ok := t.quic.(interface{ Close() error }); ok {
		_ = closer.Close()
	}
	return t.https.Close()
}
