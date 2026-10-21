# Android 2.2 delivery checklist

- [x] Review and integrate SSTP PR #5 source; discard contributed executable binaries.
- [x] Fix SSTP UDP header parsing, pin authenticated UDP source endpoints, reject missing SOCKS credentials, clear the UDP control handshake deadline, bound shutdown and cancel startup.
- [x] SSTP manual entry, factory, JSON, line/base64 subscriptions, profile editor/router and external Android scheme.
- [x] Test IPv6/embedded ports and invalid SSTP input; add SOCKS/UDP regression tests.
- [x] Local SSTP suite: 82 tests; `go vet ./...` passed. Race detector is configured in Linux CI (Windows has no C compiler).
- [x] Diagnose the supplied CSQTT link without committing its credentials: encrypted v2, one VK hash, explicit peer port.
- [x] Diagnose the supplied /sub URL: self-signed TLS certificate and HTML QR landing page.
- [x] Import CSQTT links from HTML input/anchor attributes without running scripts; deduplicate repeated links.
- [x] Supply per-launch CSQTT generation and salt; preserve stored/exported profile identity.
- [x] Themed subscription action icons; draggable bottom editor with dirty-state confirmation.
- [x] Move all advanced subscription fields to Update; preserve front/landing proxies for basic groups too.
- [x] Align shared preference/deploy/server tiles and press feedback; standardize screen transition durations; remove deploy first-frame blocking.
- [x] Parse all Android XML and check native source pins (only Windows CRLF normalized in the local check).
- [x] Update Graphify AST graph.
- [ ] Build SSTP from reviewed sources for arm64-v8a, armeabi-v7a and x86_64.
- [ ] Verify build provenance and ELF files, publish immutable archives and pin SSTP staging; require the binary on every supported ABI before release.
- [ ] Complete Android unit tests/APK compilation and Linux race tests in Actions.
- [ ] Confirm the supplied CSQTT connection against the live server; decoding and session setup alone do not prove the remote TURN path works.
- [ ] Bump stable version to 2.2, write final release notes, run stable CI and publish.

Long emulator/UI tests are intentionally excluded at the user's request. Visual checks and real phone/router behavior remain device checks.
