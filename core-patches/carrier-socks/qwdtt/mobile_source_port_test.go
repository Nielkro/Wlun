package main

import (
	"context"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

// Every destination is an isolated local echo server; this test never joins VK or starts a tunnel.
func mobileSOCKSControl(t *testing.T) (net.Conn, context.CancelFunc) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	dial := func(ctx context.Context, network, target string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, target)
	}
	go func() { _ = serveBridgeSOCKS(ctx, listener, dial, "mobile-user", "mobile-password") }()
	control, err := net.Dial("tcp4", listener.Addr().String())
	if err != nil {
		cancel()
		listener.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { cancel(); control.Close(); listener.Close() })
	_ = control.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = control.Write([]byte{5, 1, 2})
	reply := make([]byte, 2)
	if _, err = io.ReadFull(control, reply); err != nil || reply[1] != 2 {
		t.Fatalf("auth method %v: %v", reply, err)
	}
	auth := append([]byte{1, byte(len("mobile-user"))}, []byte("mobile-user")...)
	auth = append(auth, byte(len("mobile-password")))
	auth = append(auth, []byte("mobile-password")...)
	_, _ = control.Write(auth)
	if _, err = io.ReadFull(control, reply); err != nil || reply[1] != 0 {
		t.Fatalf("authentication %v: %v", reply, err)
	}
	return control, cancel
}

func TestMobileSOCKSUDPRejectsUnreservedSourcePort(t *testing.T) {
	control, _ := mobileSOCKSControl(t)
	_, _ = control.Write(append([]byte{5, 3, 0}, encodeSOCKSAddress("0.0.0.0:0")...))
	reply := make([]byte, 10)
	if _, err := io.ReadFull(control, reply); err != nil {
		t.Fatal(err)
	}
	if reply[1] != 2 {
		t.Fatalf("source-port zero accepted: %v", reply)
	}
}

func TestMobileSOCKSUDPRejectsAnotherAppsFirstPacketAndPreservesUDP(t *testing.T) {
	control, _ := mobileSOCKSControl(t)
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
	_, _ = control.Write(append([]byte{5, 3, 0}, encodeSOCKSAddress(legitimate.LocalAddr().String())...))
	header := make([]byte, 3)
	if _, err = io.ReadFull(control, header); err != nil || header[1] != 0 {
		t.Fatalf("UDP associate %v: %v", header, err)
	}
	address, err := readSOCKSAddress(control)
	if err != nil {
		t.Fatal(err)
	}
	relay, err := net.ResolveUDPAddr("udp4", address)
	if err != nil {
		t.Fatal(err)
	}
	packet := append([]byte{0, 0, 0}, encodeSOCKSAddress(echo.LocalAddr().String())...)
	_, _ = attacker.WriteToUDP(append(packet[:len(packet):len(packet)], []byte("attacker-first")...), relay)
	_ = attacker.SetReadDeadline(time.Now().Add(120 * time.Millisecond))
	buffer := make([]byte, 1024)
	if _, _, err = attacker.ReadFromUDP(buffer); err == nil {
		t.Fatal("unauthenticated socket received a tunneled reply")
	}
	_, _ = legitimate.WriteToUDP(append(packet[:len(packet):len(packet)], []byte("authenticated-udp")...), relay)
	_ = legitimate.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := legitimate.ReadFromUDP(buffer)
	if err != nil {
		t.Fatalf("legitimate UDP failed after the rejected attacker: %v", err)
	}
	if !strings.HasSuffix(string(buffer[:n]), "authenticated-udp") {
		t.Fatal("unexpected UDP reply")
	}
}
