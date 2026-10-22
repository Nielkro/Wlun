package socks

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestMobileSOCKSUDPReservesSourceAndFiltersOtherLocalApps(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client, err := newMobileSOCKSUDP(ctx, &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)}, &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	reserved := client.LocalAddr().(*net.UDPAddr)
	if reserved.Port == 0 {
		t.Fatal("UDP source was not reserved")
	}
	duplicate, err := net.ListenUDP("udp4", reserved)
	if err == nil {
		duplicate.Close()
		t.Fatal("a second app can bind the reserved UDP source")
	}
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	if err = client.bindRemote(net.IPv4(127, 0, 0, 1), relay.LocalAddr().(*net.UDPAddr).Port); err != nil {
		t.Fatal(err)
	}
	if _, err = client.Write([]byte("outbound")); err != nil {
		t.Fatal(err)
	}
	_ = relay.SetReadDeadline(time.Now().Add(time.Second))
	buffer := make([]byte, 128)
	_, source, err := relay.ReadFromUDP(buffer)
	if err != nil || source.Port != reserved.Port {
		t.Fatalf("reserved source changed: %v, %v", source, err)
	}
	attacker, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer attacker.Close()
	_, _ = attacker.WriteToUDP([]byte("forged-first"), reserved)
	_, _ = relay.WriteToUDP([]byte("trusted-reply"), reserved)
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	n, err := client.Read(buffer)
	if err != nil || string(buffer[:n]) != "trusted-reply" {
		t.Fatalf("untrusted reply accepted: %q, %v", buffer[:n], err)
	}
}

func TestMobileSOCKSUDPRefusesNonLoopbackRelayAndClosesOnCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	client, err := newMobileSOCKSUDP(ctx, &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)}, &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if client.bindRemote(net.IPv4(192, 0, 2, 1), 53) == nil {
		t.Fatal("relay escaped the loopback peer")
	}
	if client.bindRemote(net.IPv4(127, 0, 0, 1), 0) == nil {
		t.Fatal("invalid relay port accepted")
	}
	if err = client.bindRemote(net.IPv4(127, 0, 0, 1), 23456); err != nil {
		t.Fatal(err)
	}
	cancel()
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	if _, err = client.Read(make([]byte, 128)); err == nil {
		t.Fatal("cancelled UDP socket remained open")
	}
	if _, err = newMobileSOCKSUDP(ctx, &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)}, &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)}); err == nil {
		t.Fatal("cancelled request opened a socket")
	}
}
