package socks

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/varbin"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

type trackedUDPSocket struct {
	net.PacketConn
	closed atomic.Bool
	reads  chan struct{}
}

func (c *trackedUDPSocket) Close() error { c.closed.Store(true); return c.PacketConn.Close() }
func (c *trackedUDPSocket) ReadFrom(b []byte) (int, net.Addr, error) {
	n, source, err := c.PacketConn.ReadFrom(b)
	if err == nil {
		select {
		case c.reads <- struct{}{}:
		default:
		}
	}
	return n, source, err
}

type sourceDialer struct {
	mu     sync.Mutex
	packet *trackedUDPSocket
}

func (d *sourceDialer) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	if network != N.NetworkTCP {
		return nil, errors.New("UDP must be prebound, never dialed after handshake")
	}
	return (&net.Dialer{}).DialContext(ctx, network, address.String())
}
func (d *sourceDialer) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	packet, err := (&net.ListenConfig{}).ListenPacket(ctx, "udp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	d.mu.Lock()
	d.packet = &trackedUDPSocket{PacketConn: packet, reads: make(chan struct{}, 16)}
	d.mu.Unlock()
	return d.socket(), nil
}
func (d *sourceDialer) socket() *trackedUDPSocket { d.mu.Lock(); defer d.mu.Unlock(); return d.packet }

func socksTestAuth(conn net.Conn) (socks5.Request, error) {
	reader := varbin.StubReader(conn)
	auth, err := socks5.ReadAuthRequest(reader)
	if err != nil {
		return socks5.Request{}, err
	}
	if len(auth.Methods) != 1 || auth.Methods[0] != socks5.AuthTypeUsernamePassword {
		return socks5.Request{}, fmt.Errorf("authentication not offered: %v", auth.Methods)
	}
	if err = socks5.WriteAuthResponse(conn, socks5.AuthResponse{Method: socks5.AuthTypeUsernamePassword}); err != nil {
		return socks5.Request{}, err
	}
	password, err := socks5.ReadUsernamePasswordAuthRequest(reader)
	if err != nil {
		return socks5.Request{}, err
	}
	if password.Username != "session" || password.Password != "secret" {
		return socks5.Request{}, errors.New("wrong session credentials")
	}
	if err = socks5.WriteUsernamePasswordAuthResponse(conn, socks5.UsernamePasswordAuthResponse{Status: socks5.UsernamePasswordStatusSuccess}); err != nil {
		return socks5.Request{}, err
	}
	return socks5.ReadRequest(reader)
}

func localSOCKSTest(t *testing.T, handler func(net.Conn) error) (M.Socksaddr, <-chan error) {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	done := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			done <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		done <- handler(conn)
	}()
	return M.SocksaddrFromNet(listener.Addr()).Unwrap(), done
}

func checkServer(t *testing.T, done <-chan error) {
	t.Helper()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(6 * time.Second):
		t.Fatal("SOCKS test server did not finish")
	}
}

func TestLoopbackUDPAdvertisesBoundSourceAndRejectsInjectedReplies(t *testing.T) {
	dialer := &sourceDialer{}
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	attacker, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer attacker.Close()
	server, done := localSOCKSTest(t, func(tcp net.Conn) error {
		request, err := socksTestAuth(tcp)
		if err != nil {
			return err
		}
		packet := dialer.socket()
		if packet == nil {
			return errors.New("UDP source was not bound before handshake")
		}
		want := M.SocksaddrFromNet(packet.LocalAddr()).Unwrap()
		if request.Command != socks5.CommandUDPAssociate || request.Destination != want || want.Port == 0 {
			return fmt.Errorf("wrong advertised UDP source: got %v, want %v", request.Destination, want)
		}
		if err = socks5.WriteResponse(tcp, socks5.Response{ReplyCode: socks5.ReplyCodeSuccess, Bind: M.SocksaddrFromNet(relay.LocalAddr())}); err != nil {
			return err
		}
		_ = relay.SetDeadline(time.Now().Add(5 * time.Second))
		buffer := make([]byte, 4096)
		n, source, err := relay.ReadFromUDP(buffer)
		if err != nil {
			return err
		}
		if M.SocksaddrFromNet(source).Unwrap() != want {
			return errors.New("UDP source changed after authentication")
		}
		forged := append([]byte(nil), buffer[:n]...)
		copy(forged[len(forged)-4:], "evil")
		if _, err = attacker.WriteToUDP(forged, source); err != nil {
			return err
		}
		// Ensure the forged datagram was actually read before delivering the valid reply.
		select {
		case <-packet.reads:
		case <-time.After(3 * time.Second):
			return errors.New("forged packet was not consumed")
		}
		if _, err = relay.WriteToUDP(buffer[:n], source); err != nil {
			return err
		}
		_, _ = io.Copy(io.Discard, tcp)
		return nil
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client := NewClient(dialer, server, Version5, "session", "secret")
	conn, err := client.DialContext(ctx, N.NetworkUDP, M.ParseSocksaddr("1.1.1.1:53"))
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = conn.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 4096)
	n, err := conn.Read(buffer)
	if err != nil {
		t.Fatal(err)
	}
	if string(buffer[:n]) != "ping" {
		t.Fatalf("accepted unauthenticated relay packet: %q", buffer[:n])
	}
	_ = conn.Close()
	if !dialer.socket().closed.Load() {
		t.Fatal("association close leaked UDP socket")
	}
	checkServer(t, done)
}

func TestLoopbackUDPHandshakeCancellationClosesPreboundSocket(t *testing.T) {
	dialer := &sourceDialer{}
	authSeen := make(chan struct{})
	server, done := localSOCKSTest(t, func(tcp net.Conn) error {
		if _, err := socks5.ReadAuthRequest(varbin.StubReader(tcp)); err != nil {
			return err
		}
		close(authSeen)
		_, _ = io.Copy(io.Discard, tcp)
		return nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() {
		conn, err := NewClient(dialer, server, Version5, "session", "secret").DialContext(ctx, N.NetworkUDP, M.ParseSocksaddr("1.1.1.1:53"))
		if conn != nil {
			_ = conn.Close()
		}
		result <- err
	}()
	select {
	case <-authSeen:
	case <-time.After(5 * time.Second):
		t.Fatal("handshake did not start")
	}
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("cancellation returned %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("cancellation left handshake blocked")
	}
	if dialer.socket() == nil || !dialer.socket().closed.Load() {
		t.Fatal("cancellation leaked prebound UDP socket")
	}
	checkServer(t, done)
}

func TestLoopbackUDPRejectsUnauthenticatedServerDowngrade(t *testing.T) {
	dialer := &sourceDialer{}
	server, done := localSOCKSTest(t, func(tcp net.Conn) error {
		if _, err := socks5.ReadAuthRequest(varbin.StubReader(tcp)); err != nil {
			return err
		}
		return socks5.WriteAuthResponse(tcp, socks5.AuthResponse{Method: socks5.AuthTypeNotRequired})
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := NewClient(dialer, server, Version5, "session", "secret").DialContext(ctx, N.NetworkUDP, M.ParseSocksaddr("1.1.1.1:53"))
	if conn != nil {
		_ = conn.Close()
	}
	if err == nil {
		t.Fatal("accepted authentication downgrade")
	}
	if dialer.socket() == nil || !dialer.socket().closed.Load() {
		t.Fatal("failed authentication leaked UDP socket")
	}
	checkServer(t, done)
}

func TestRemoteSOCKSHandshakeKeepsNATCompatibleUnknownUDPSource(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	done := make(chan error, 1)
	go func() {
		defer server.Close()
		reader := varbin.StubReader(server)
		if _, err := socks5.ReadAuthRequest(reader); err != nil {
			done <- err
			return
		}
		if err := socks5.WriteAuthResponse(server, socks5.AuthResponse{Method: socks5.AuthTypeNotRequired}); err != nil {
			done <- err
			return
		}
		request, err := socks5.ReadRequest(reader)
		if err != nil {
			done <- err
			return
		}
		if request.Destination.Port != 0 {
			done <- errors.New("remote client advertised a private NAT source port")
			return
		}
		done <- socks5.WriteResponse(server, socks5.Response{ReplyCode: socks5.ReplyCodeSuccess, Bind: M.ParseSocksaddr("192.0.2.1:5000")})
	}()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := ClientHandshake5(client, socks5.CommandUDPAssociate, M.ParseSocksaddr("1.1.1.1:53"), "", ""); err != nil {
		t.Fatal(err)
	}
	checkServer(t, done)
}

type trackedInboundListener struct {
	mu     sync.Mutex
	packet *trackedUDPSocket
}

func (l *trackedInboundListener) ListenPacket(config net.ListenConfig, ctx context.Context, network, address string) (net.PacketConn, error) {
	packet, err := config.ListenPacket(ctx, network, address)
	if err != nil {
		return nil, err
	}
	l.mu.Lock()
	l.packet = &trackedUDPSocket{PacketConn: packet, reads: make(chan struct{}, 16)}
	l.mu.Unlock()
	return l.socket(), nil
}
func (l *trackedInboundListener) socket() *trackedUDPSocket {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.packet
}

type inboundEcho struct {
	tcp net.Conn
	err error
}

func (h *inboundEcho) NewConnectionEx(_ context.Context, conn net.Conn, _, _ M.Socksaddr, _ N.CloseHandlerFunc) {
	h.err = errors.New("unexpected TCP request")
	_ = conn.Close()
}
func (h *inboundEcho) NewPacketConnectionEx(_ context.Context, conn N.PacketConn, _, _ M.Socksaddr, _ N.CloseHandlerFunc) {
	defer conn.Close()
	packet := buf.NewPacket()
	destination, err := conn.ReadPacket(packet)
	if err != nil {
		packet.Release()
		h.err = err
		return
	}
	if string(packet.Bytes()) != "ping" {
		packet.Release()
		h.err = errors.New("inbound accepted an attacker as first UDP sender")
		return
	}
	reply := buf.NewPacket()
	reply.Resize(3+M.MaxSocksaddrLength, 0)
	_, _ = reply.Write(packet.Bytes())
	packet.Release()
	if err = conn.WritePacket(reply, destination); err != nil {
		h.err = err
		return
	}
	_, _ = io.Copy(io.Discard, h.tcp)
}

func TestAuthenticatedLoopbackInboundRejectsFirstPacketRaceAndKeepsUDP(t *testing.T) {
	listener := &trackedInboundListener{}
	server, done := localSOCKSTest(t, func(tcp net.Conn) error {
		handler := &inboundEcho{tcp: tcp}
		err := HandleConnectionEx(context.Background(), tcp, bufio.NewReader(tcp),
			auth.NewAuthenticator([]auth.User{{Username: "session", Password: "secret"}}), handler, listener, 5*time.Second,
			M.SocksaddrFromNet(tcp.RemoteAddr()), nil)
		if err != nil {
			return err
		}
		return handler.err
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := NewClient(&sourceDialer{}, server, Version5, "session", "secret").DialContext(ctx, N.NetworkUDP, M.ParseSocksaddr("1.1.1.1:53"))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	attacker, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer attacker.Close()
	// Valid SOCKS framing from the wrong source port must not win the first-packet race.
	if _, err = attacker.WriteTo([]byte{0, 0, 0, 1, 1, 1, 1, 1, 0, 53, 'e', 'v', 'i', 'l'}, listener.socket().LocalAddr()); err != nil {
		t.Fatal(err)
	}
	select {
	case <-listener.socket().reads:
	case <-time.After(3 * time.Second):
		t.Fatal("server did not consume forged packet")
	}
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = client.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, 128)
	n, err := client.Read(response)
	if err != nil {
		t.Fatal(err)
	}
	if string(response[:n]) != "ping" {
		t.Fatalf("bad UDP echo %q", response[:n])
	}
	_ = client.Close()
	checkServer(t, done)
}

func TestAuthenticatedLoopbackInboundRejectsUnknownUDPSourcePort(t *testing.T) {
	listener := &trackedInboundListener{}
	server, done := localSOCKSTest(t, func(tcp net.Conn) error {
		err := HandleConnectionEx(context.Background(), tcp, bufio.NewReader(tcp),
			auth.NewAuthenticator([]auth.User{{Username: "session", Password: "secret"}}), &inboundEcho{tcp: tcp}, listener, time.Second,
			M.SocksaddrFromNet(tcp.RemoteAddr()), nil)
		if err == nil {
			return errors.New("inbound accepted unknown UDP source port")
		}
		if listener.socket() != nil {
			return errors.New("inbound allocated UDP relay before source validation")
		}
		return nil
	})
	tcp, err := net.Dial("tcp", server.String())
	if err != nil {
		t.Fatal(err)
	}
	defer tcp.Close()
	_ = tcp.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = ClientHandshake5(tcp, socks5.CommandUDPAssociate, M.ParseSocksaddr("1.1.1.1:53"), "session", "secret"); err == nil {
		t.Fatal("legacy unknown-source handshake unexpectedly accepted")
	}
	checkServer(t, done)
}
