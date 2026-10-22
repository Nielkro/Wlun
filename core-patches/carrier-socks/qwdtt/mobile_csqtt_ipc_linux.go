//go:build linux || android

package main

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

// The raw IP transport has no reachable UDP port, including before the tunnel is ready.
type mobileCSQTTIPC struct {
	path   string
	dir    string
	local  net.Conn
	remote *os.File
	once   sync.Once
}

func newMobileCSQTTIPC(cache string) (*mobileCSQTTIPC, error) {
	dir, err := os.MkdirTemp(cache, "csqtt-ipc-")
	if err != nil {
		return nil, err
	}
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		os.RemoveAll(dir)
		return nil, err
	}
	first := os.NewFile(uintptr(fds[0]), "csqtt-local")
	remote := os.NewFile(uintptr(fds[1]), "csqtt-transport")
	local, err := net.FileConn(first)
	first.Close()
	if err != nil {
		remote.Close()
		os.RemoveAll(dir)
		return nil, err
	}
	return &mobileCSQTTIPC{path: filepath.Join(dir, "fd"), dir: dir, local: local, remote: remote}, nil
}

func (ipc *mobileCSQTTIPC) attach(ctx context.Context) (io.ReadWriteCloser, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	var connection net.Conn
	for {
		var err error
		connection, err = (&net.Dialer{}).DialContext(ctx, "unix", ipc.path)
		if err == nil {
			break
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(25 * time.Millisecond):
		}
	}
	defer connection.Close()
	deadline, _ := ctx.Deadline()
	connection.SetDeadline(deadline)
	stop := context.AfterFunc(ctx, func() { connection.Close() })
	defer stop()
	_, _, err := connection.(*net.UnixConn).WriteMsgUnix([]byte{1}, syscall.UnixRights(int(ipc.remote.Fd())), nil)
	if err != nil {
		return nil, err
	}
	var ack [1]byte
	if _, err = io.ReadFull(connection, ack[:]); err != nil {
		return nil, err
	}
	if ack[0] != 1 {
		return nil, errors.New("CSQTT rejected private IP transport")
	}
	ipc.remote.Close()
	return ipc.local, nil
}

func (ipc *mobileCSQTTIPC) Close() error {
	ipc.once.Do(func() { ipc.local.Close(); ipc.remote.Close(); os.RemoveAll(ipc.dir) })
	return nil
}
