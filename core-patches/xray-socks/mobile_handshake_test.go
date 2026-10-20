package socks

import (
	"bytes"
	"io"
	"net"
	"testing"
	"time"

	M "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
)

func TestMobileSOCKSUDPHandshakeAdvertisesReservedSource(t *testing.T) {
	for _, bound := range []bool{false, true} {
		client, server := net.Pipe()
		_ = client.SetDeadline(time.Now().Add(time.Second))
		_ = server.SetDeadline(time.Now().Add(time.Second))
		requestBytes := make(chan []byte, 1)
		go func() {
			defer server.Close()
			greeting := make([]byte, 3)
			if _, err := io.ReadFull(server, greeting); err != nil {
				requestBytes <- nil
				return
			}
			_, _ = server.Write([]byte{5, 0})
			request := make([]byte, 10)
			if _, err := io.ReadFull(server, request); err != nil {
				requestBytes <- nil
				return
			}
			requestBytes <- request
			_, _ = server.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 90, 90})
		}()
		request := &protocol.RequestHeader{Version: 5, Command: protocol.RequestCommandUDP, Address: M.IPAddress([]byte{127, 0, 0, 1}), Port: 34567}
		_, err := clientHandshake(request, client, client, bound)
		client.Close()
		if err != nil {
			t.Fatal(err)
		}
		wire := <-requestBytes
		want := []byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}
		if bound {
			want = []byte{5, 3, 0, 1, 127, 0, 0, 1, byte(34567 >> 8), byte(34567 & 255)}
		}
		if !bytes.Equal(want, wire) {
			t.Fatalf("bound=%v: association source %v, want %v", bound, wire, want)
		}
	}
}
