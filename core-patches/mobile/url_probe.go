package mobile

import (
	"context"
	"strings"
	"sync"
	"time"

	"ThroneCore/internal/probe"
	E "github.com/sagernet/sing/common/exceptions"
)

// URLTestTask cancels just this probe, including its pending outbound dials.
// It never closes the live instance or rearms the process-wide test context.
type URLTestTask struct{ cancel context.CancelFunc }

func (t *URLTestTask) Cancel() { t.cancel() }

func StartScopedURLTest(current *Instance, platform PlatformInterface, request *TestRequest, handler URLTestHandler) (*URLTestTask, error) {
	if request == nil || handler == nil {
		return nil, E.New("nil request or handler")
	}
	env, err := prepareTestEnv(current, request.TestCurrent, platform, request)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(probe.TestContext())
	task := &URLTestTask{cancel: cancel}
	if request.TestCurrent {
		ctx = probe.LiveInstance(ctx)
	}
	go func() {
		defer handler.OnDone()
		defer env.close()
		defer cancel()
		var mu sync.Mutex
		failed := make(map[string]bool)

		keepAlive := strings.Contains(request.URL, "#keepalive")
		reqURL := strings.TrimSuffix(request.URL, "#keepalive")

		if keepAlive {
			// Phase 1: Warm-up pass in the same core box. We send the first request batch with closeOutbound=false.
			// This establishes the TCP + TLS connection and keeps it alive in the connection pool.
			warmupDone := make(chan struct{})
			go func() {
				defer close(warmupDone)
				probe.BatchURLTestTo(ctx, env.box, env.tags, reqURL,
					int(request.MaxConcurrency), false,
					time.Duration(request.TimeoutMs)*time.Millisecond,
					nil)
			}()
			select {
			case <-warmupDone:
			case <-ctx.Done():
				return
			}
		}

		// Phase 2: Measured pass.
		// In keepalive mode, the established TLS session is reused to measure clean response latency.
		results := probe.BatchURLTestTo(ctx, env.box, env.tags, reqURL,
			int(request.MaxConcurrency), !request.TestCurrent,
			time.Duration(request.TimeoutMs)*time.Millisecond,
			func(result *probe.URLTestResult) {
				mu.Lock()
				failed[result.Tag] = result.Error != nil
				mu.Unlock()
				handler.OnResult(result.Tag, int32(result.Duration.Milliseconds()), errorString(result.Error))
			})
		// Aborted tags are deliberately not published by BatchURLTestTo.
		for _, result := range results {
			if result.Error == probe.ErrTestAborted {
				handler.OnResult(result.Tag, 0, errorString(result.Error))
			}
		}
		for _, tag := range request.vpnEndpointTags {
			if failed[tag] && ctx.Err() == nil {
				connected, state, failure := vpnEndpointVerdict(env.box, tag)
				handler.OnVPNStatus(tag, connected, state, failure)
			}
		}
	}()
	return task, nil
}
