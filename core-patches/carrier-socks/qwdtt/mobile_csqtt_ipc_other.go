//go:build !linux && !android

package main

import (
	"context"
	"errors"
	"io"
)

type mobileCSQTTIPC struct{ path string }

func newMobileCSQTTIPC(string) (*mobileCSQTTIPC, error) {
	return nil, errors.New("mobile CSQTT private IPC requires Linux or Android")
}
func (*mobileCSQTTIPC) attach(context.Context) (io.ReadWriteCloser, error) {
	return nil, errors.New("mobile CSQTT private IPC requires Linux or Android")
}
func (*mobileCSQTTIPC) Close() error { return nil }
