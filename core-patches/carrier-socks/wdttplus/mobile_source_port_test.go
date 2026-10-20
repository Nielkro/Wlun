package main

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

type mobileSystemSocksDialer struct{}

func (mobileSystemSocksDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, address)
}

func (mobileSystemSocksDialer) LookupContextHost(ctx context.Context, host string) ([]string, error) {
	return net.DefaultResolver.LookupHost(ctx, host)
}

func mobileWDTTControl(t *testing.T) net.Conn {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		control, err := listener.Accept()
		if err != nil {
			return
		}
		defer control.Close()
		server := &socks5Server{dialer: mobileSystemSocksDialer{}, authEnabled: true, username: "mobile-user", password: "mobile-password", udpEnabled: true}
		_ = server.serveClient(context.Background(), control)
	}()
	client, err := net.Dial("tcp4", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close() })
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = client.Write([]byte{5, 1, 2})
	reply := make([]byte, 2)
	if _, err = io.ReadFull(client, reply); err != nil || reply[1] != 2 {
		t.Fatalf("auth method %v: %v", reply, err)
	}
	auth := append([]byte{1, byte(len("mobile-user"))}, []byte("mobile-user")...)
	auth = append(auth, byte(len("mobile-password")))
	auth = append(auth, []byte("mobile-password")...)
	_, _ = client.Write(auth)
	if _, err = io.ReadFull(client, reply); err != nil || reply[1] != 0 {
		t.Fatalf("authentication %v: %v", reply, err)
	}
	return client
}

func TestMobileSOCKSUDPRejectsUnreservedSourcePort(t *testing.T) {
	client := mobileWDTTControl(t)
	_, _ = client.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	reply := make([]byte, 10)
	if _, err := io.ReadFull(client, reply); err != nil {
		t.Fatal(err)
	}
	if reply[1] != socksReplyNotAllowed {
		t.Fatalf("unreserved UDP port accepted: %v", reply)
	}
}

func TestMobileSOCKSUDPRejectsAnotherAppsFirstPacketAndPreservesUDP(t *testing.T) {
	control := mobileWDTTControl(t)
	legitimate, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer legitimate.Close()
	attacker, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer attacker.Close()
	echo, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		buffer := make([]byte, 1024)
		for {
			n, address, err := echo.ReadFromUDP(buffer)
			if err != nil {
				return
			}
			_, _ = echo.WriteToUDP(buffer[:n], address)
		}
	}()
	request := []byte{5, 3, 0, 1, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(request[8:], uint16(legitimate.LocalAddr().(*net.UDPAddr).Port))
	_, _ = control.Write(request)
	reply := make([]byte, 10)
	if _, err = io.ReadFull(control, reply); err != nil || reply[1] != socksReplyOK {
		t.Fatalf("UDP associate %v: %v", reply, err)
	}
	relay := &net.UDPAddr{IP: net.IP(reply[4:8]), Port: int(binary.BigEndian.Uint16(reply[8:]))}
	packet, err := buildSocksUDPDatagram(echo.LocalAddr().(*net.UDPAddr), []byte("attacker-first"))
	if err != nil {
		t.Fatal(err)
	}
	_, _ = attacker.WriteToUDP(packet, relay)
	_ = attacker.SetReadDeadline(time.Now().Add(120 * time.Millisecond))
	buffer := make([]byte, 1024)
	if _, _, err = attacker.ReadFromUDP(buffer); err == nil {
		t.Fatal("unauthenticated UDP socket received a tunneled reply")
	}
	packet, err = buildSocksUDPDatagram(echo.LocalAddr().(*net.UDPAddr), []byte("authenticated-udp"))
	if err != nil {
		t.Fatal(err)
	}
	_, _ = legitimate.WriteToUDP(packet, relay)
	_ = legitimate.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := legitimate.ReadFromUDP(buffer)
	if err != nil {
		t.Fatalf("legitimate UDP failed after the rejected attacker: %v", err)
	}
	if !strings.HasSuffix(string(buffer[:n]), "authenticated-udp") {
		t.Fatal("unexpected UDP reply")
	}
}
