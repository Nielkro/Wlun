package socks

import (
	"context"
	"fmt"
	"net"
	"sync"
)

// Keep the reserved source socket alive across SOCKS authentication and relay traffic.
type mobileSOCKSUDPConn struct {
	*net.UDPConn
	peerIP     net.IP
	remote     *net.UDPAddr
	stop       func() bool
	closeOnce  sync.Once
	closeError error
}

func newMobileSOCKSUDP(ctx context.Context, local, peer net.Addr) (*mobileSOCKSUDPConn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	localTCP, localOK := local.(*net.TCPAddr)
	peerTCP, peerOK := peer.(*net.TCPAddr)
	if !localOK || !peerOK || !localTCP.IP.IsLoopback() || !peerTCP.IP.IsLoopback() {
		return nil, fmt.Errorf("local SOCKS UDP requires loopback TCP endpoints")
	}
	network := "udp4"
	if localTCP.IP.To4() == nil {
		network = "udp6"
	}
	packet, err := net.ListenUDP(network, &net.UDPAddr{IP: localTCP.IP})
	if err != nil {
		return nil, err
	}
	conn := &mobileSOCKSUDPConn{UDPConn: packet, peerIP: append(net.IP(nil), peerTCP.IP...)}
	conn.stop = context.AfterFunc(ctx, func() { _ = packet.Close() })
	return conn, nil
}

func (conn *mobileSOCKSUDPConn) bindRemote(ip net.IP, port int) error {
	if !ip.Equal(conn.peerIP) || port < 1 || port > 65535 {
		return fmt.Errorf("local SOCKS UDP relay must remain on its TCP peer")
	}
	conn.remote = &net.UDPAddr{IP: append(net.IP(nil), ip...), Port: port}
	return nil
}

func (conn *mobileSOCKSUDPConn) Read(buffer []byte) (int, error) {
	if conn.remote == nil {
		return 0, fmt.Errorf("local SOCKS UDP relay is not bound")
	}
	for {
		n, source, err := conn.UDPConn.ReadFromUDP(buffer)
		if err != nil {
			return 0, err
		}
		if source.Port == conn.remote.Port && source.IP.Equal(conn.remote.IP) {
			return n, nil
		}
	}
}

func (conn *mobileSOCKSUDPConn) Write(buffer []byte) (int, error) {
	if conn.remote == nil {
		return 0, fmt.Errorf("local SOCKS UDP relay is not bound")
	}
	return conn.UDPConn.WriteToUDP(buffer, conn.remote)
}

func (conn *mobileSOCKSUDPConn) RemoteAddr() net.Addr { return conn.remote }

func (conn *mobileSOCKSUDPConn) Close() error {
	conn.closeOnce.Do(func() { conn.stop(); conn.closeError = conn.UDPConn.Close() })
	return conn.closeError
}
