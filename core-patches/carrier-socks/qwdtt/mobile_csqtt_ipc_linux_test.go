//go:build linux || android

package main

import (
	"context"
	"io"
	"net"
	"os"
	"syscall"
	"testing"
	"time"

	"wg-turn-client/internal/csqtt"
)

func TestMobileSOCKSCSQTTPrivateIPCannotBeInjectedOverUDP(t *testing.T) {
	ipc, err := newMobileCSQTTIPC(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ipc.Close()
	info, err := os.Stat(ipc.dir)
	if err != nil || info.Mode().Perm() != 0700 {
		t.Fatalf("IPC directory is not private: %v %v", info, err)
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: ipc.path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	transport := make(chan net.Conn, 1)
	go func() {
		connection, err := listener.AcceptUnix()
		if err != nil {
			return
		}
		defer connection.Close()
		connection.SetDeadline(time.Now().Add(3 * time.Second))
		payload, controls := make([]byte, 1), make([]byte, syscall.CmsgSpace(4))
		_, count, _, _, err := connection.ReadMsgUnix(payload, controls)
		if err != nil {
			return
		}
		messages, err := syscall.ParseSocketControlMessage(controls[:count])
		if err != nil || len(messages) != 1 {
			return
		}
		fds, err := syscall.ParseUnixRights(&messages[0])
		if err != nil || len(fds) != 1 {
			return
		}
		file := os.NewFile(uintptr(fds[0]), "rust-tun-fixture")
		peer, err := net.FileConn(file)
		file.Close()
		if err != nil {
			return
		}
		if _, err = connection.Write([]byte{1}); err != nil {
			peer.Close()
			return
		}
		transport <- peer
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	local, err := ipc.attach(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var remote net.Conn
	select {
	case remote = <-transport:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	defer remote.Close()
	remote.SetDeadline(time.Now().Add(3 * time.Second))
	// The userspace IP device sees exactly the same packets in both directions.
	device, application := net.Pipe()
	defer application.Close()
	finished := make(chan error, 1)
	go func() { finished <- csqtt.RelayIP(ctx, device, local) }()
	packet := []byte{0x45, 0, 0, 20, 0, 0, 0, 0, 64, 17, 0, 0, 10, 0, 0, 2, 192, 0, 2, 1}
	go application.Write(packet)
	buffer := make([]byte, 65535)
	n, err := remote.Read(buffer)
	if err != nil || string(buffer[:n]) != string(packet) {
		t.Fatalf("outbound IP changed: %x %v", buffer[:n], err)
	}
	// An unrelated application has no network address for either socketpair endpoint.
	if _, ok := local.(net.Conn).LocalAddr().(*net.UnixAddr); !ok {
		t.Fatal("raw IP endpoint is addressable over IP")
	}
	if _, err = net.ResolveUDPAddr("udp4", ipc.path); err == nil {
		t.Fatal("private IPC became a UDP endpoint")
	}
	packet[15] = 3
	if _, err = remote.Write(packet); err != nil {
		t.Fatal(err)
	}
	application.SetReadDeadline(time.Now().Add(3 * time.Second))
	received := make([]byte, len(packet))
	if _, err = io.ReadFull(application, received); err != nil || string(received) != string(packet) {
		t.Fatalf("return IP changed: %x %v", received, err)
	}
	cancel()
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("cancellation leaked IP relay")
	}
	ipc.Close()
	if _, err = os.Stat(ipc.dir); !os.IsNotExist(err) {
		t.Fatalf("private IPC directory was not removed: %v", err)
	}
}

func TestMobileSOCKSCSQTTPrivateIPAttachCancellationCleansUp(t *testing.T) {
	ipc, err := newMobileCSQTTIPC(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = ipc.attach(ctx); err == nil {
		t.Fatal("cancelled descriptor handover succeeded")
	}
	ipc.Close()
	if _, err = os.Stat(ipc.dir); !os.IsNotExist(err) {
		t.Fatal("cancelled handover left IPC state")
	}
}
