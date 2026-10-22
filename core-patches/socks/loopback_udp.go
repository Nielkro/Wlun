package socks

import (
	"context"
	"io"
	"net"

	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

// Local Android carriers require the UDP source port to be fixed before UDP
// ASSOCIATE. Learning it from the first datagram would let another local app race
// the authenticated client. Remote SOCKS/NAT behavior is deliberately unchanged.
func (c *Client) dialLoopbackUDP(ctx context.Context, tcpConn net.Conn, destination M.Socksaddr) (net.Conn, error) {
	var packetConn net.PacketConn
	success := false
	defer func() {
		if !success {
			_ = tcpConn.Close()
			if packetConn != nil {
				_ = packetConn.Close()
			}
		}
	}()
	peer := M.SocksaddrFromNet(tcpConn.RemoteAddr()).Unwrap()
	local := M.SocksaddrFromNet(tcpConn.LocalAddr()).Unwrap()
	if !peer.Addr.IsLoopback() || !local.Addr.IsLoopback() {
		return nil, net.InvalidAddrError("SOCKS loopback transport is not local")
	}
	var err error
	packetConn, err = c.dialer.ListenPacket(ctx, c.serverAddr)
	if err != nil {
		return nil, err
	}
	source := M.SocksaddrFromNet(packetConn.LocalAddr()).Unwrap()
	if source.Port == 0 || (source.Addr.IsValid() && !source.Addr.IsUnspecified() && source.Addr != local.Addr) {
		return nil, net.InvalidAddrError("SOCKS UDP source address is not bound to the TCP peer")
	}
	source.Addr = local.Addr
	source.Fqdn = ""
	response, err := clientHandshake5(tcpConn, socks5.CommandUDPAssociate, source, c.username, c.password, true)
	if err != nil {
		return nil, err
	}
	relay := response.Bind.Unwrap()
	if relay.Addr.IsUnspecified() {
		relay.Addr = peer.Addr
	}
	if relay.Port == 0 || relay.Addr != peer.Addr || relay.Fqdn != "" {
		return nil, net.InvalidAddrError("SOCKS UDP relay is not the authenticated loopback peer")
	}
	conn := NewAssociatePacketConn(&loopbackRelayConn{
		PacketConn: &sourceBoundPacketConn{PacketConn: packetConn, source: relay}, relay: relay,
	}, destination, tcpConn)
	// A SOCKS UDP association ends with its authenticated TCP control connection.
	go func() {
		_, _ = io.Copy(io.Discard, tcpConn)
		_ = conn.Close()
	}()
	success = true
	return conn, nil
}

// A prebound PacketConn is unconnected. Check the exact relay endpoint on every
// read; bufio's generic bind wrapper would accept datagrams from any local app.
// No Upstream accessor is exposed, so optimized readers cannot bypass this check.
type loopbackRelayConn struct {
	net.PacketConn
	relay M.Socksaddr
}

func (c *loopbackRelayConn) Read(p []byte) (int, error) {
	n, _, err := c.PacketConn.ReadFrom(p)
	return n, err
}

type sourceBoundPacketConn struct {
	net.PacketConn
	source M.Socksaddr
}

func (c *sourceBoundPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		n, source, err := c.PacketConn.ReadFrom(p)
		if err != nil {
			return n, source, err
		}
		if M.SocksaddrFromNet(source).Unwrap() == c.source {
			return n, source, nil
		}
	}
}

func (c *loopbackRelayConn) Write(p []byte) (int, error) {
	return c.PacketConn.WriteTo(p, c.relay.UDPAddr())
}

func (c *loopbackRelayConn) RemoteAddr() net.Addr { return c.relay.UDPAddr() }

// An authenticated local inbound cannot safely learn a UDP sender from the first
// packet. Require its port in the TCP request, before accepting any UDP traffic.
func authenticatedLoopbackUDPSource(tcpConn net.Conn, requested M.Socksaddr) (M.Socksaddr, error) {
	peer := M.SocksaddrFromNet(tcpConn.RemoteAddr()).Unwrap()
	if !peer.Addr.IsLoopback() {
		return M.Socksaddr{}, nil
	}
	requested = requested.Unwrap()
	if requested.Port == 0 || requested.Fqdn != "" ||
		(!requested.Addr.IsUnspecified() && requested.Addr != peer.Addr) {
		return M.Socksaddr{}, net.InvalidAddrError("authenticated loopback SOCKS UDP source must be declared")
	}
	requested.Addr = peer.Addr
	return requested, nil
}
