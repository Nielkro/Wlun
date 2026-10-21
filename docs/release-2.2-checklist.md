# Android 2.2 delivery checklist

- [x] Review and integrate SSTP PR #5 source; discard contributed executable binaries.
- [x] Fix SSTP UDP header parsing, pin authenticated UDP source endpoints, reject missing SOCKS credentials, clear the UDP control handshake deadline, bound shutdown and cancel startup.
- [x] SSTP manual entry, factory, JSON, line/base64 subscriptions, profile editor/router and external Android scheme.
- [x] Test IPv6/embedded ports and invalid SSTP input; add SOCKS/UDP regression tests.
- [x] Local SSTP suite: 82 tests; `go vet ./...` passed. Race detector is configured in Linux CI (Windows has no C compiler).
- [x] Diagnose the supplied CSQTT link without committing its credentials: encrypted v2, three 43-character VK hashes, explicit peer port.
- [x] Diagnose the supplied /sub URL: self-signed TLS certificate and HTML QR landing page.
- [x] Import CSQTT links from HTML input/anchor attributes without running scripts; deduplicate repeated links.
- [x] Supply per-launch CSQTT generation and salt; preserve stored/exported profile identity.
- [x] Themed subscription action icons; draggable bottom editor with dirty-state confirmation.
- [x] Move all advanced subscription fields to Update; preserve front/landing proxies for basic groups too.
- [x] Align shared preference/deploy/server tiles and press feedback; standardize screen transition durations; remove deploy first-frame blocking.
- [x] Parse all Android XML and check native source pins (only Windows CRLF normalized in the local check).
- [x] Update Graphify AST graph.
- [x] Build SSTP from reviewed sources for arm64-v8a, armeabi-v7a and x86_64 (Actions 39543105859, build 9409f474ec032e97c9fc3c90d03f15f1fada0b1d).
- [x] Verify build provenance, ELF machine/type and 16KiB load alignment; publish immutable archives and pin SSTP staging. Source-set, checksum and archive traversal rejection were checked.
- [x] Initial Android CI 39543102104: 329 tests passed, no failures/ignored; APKs compiled; Linux race tests and vet passed.
- [x] Final CI with pinned SSTP binaries and version 2.2: 39544082055 passed on d875c2fd958ce904ab41867c47a38227ce23aafc.
- [ ] Confirm the supplied CSQTT connection against the live server; decoding and session setup alone do not prove the remote TURN path works.
- [x] Bump stable version to 2.2 and write final release notes.
- [ ] Run stable CI and publish.

Long emulator/UI tests are intentionally excluded at the user's request. Visual checks and real phone/router behavior remain device checks.
