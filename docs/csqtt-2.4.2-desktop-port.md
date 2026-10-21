# CSQTT compatibility: desktop port notes

Verification status is tracked in release-2.4.2-checklist.md. Parsing a csqtt:// URI does not prove transport compatibility.

## Two protocols

Keep Legacy Rust and deployment at 2066156119a5afe7d63e104aefdc8e0849999d03.
Its registration is GETCONF:port|password|generation|salt|worker|desired-count and disconnect is DISCONNECT:password|salt.

The separate original client is pinned to v2.1.9, 446293aa2e873ac5323ef6fd2316d9b81d966c11.
It registers GETCONF:port|device-id|password|generation|salt|worker|desired-count|CSQTT-WIRE-3
and disconnects with DISCONNECT:device-id|salt.
The supplied pre-release APK passes -device-id and contains CSQTT-WIRE-3.
Keep each client's original framing, authentication, scheduling and captcha implementation.

## Automatic choice

- Start Legacy first, without a product UI selector.
- Retry once with the compatibility client after FATAL_PROTOCOL or the original watchdog's verdict: three WRAP_AUTH_TIMEOUT events, no worker ever active, 30 seconds elapsed.
- Treat invalid_epoch as a format mismatch only with a locally generated, well-formed generation and salt: old servers read those fields at different offsets.
- Do not switch after explicit FATAL_AUTH, cancelled captcha, cancellation or a generic network timeout.
- Emit only fixed protocol/timeout markers from the wrapper; raw logs may contain secrets.
- protocol_variant=legacy/compat is private per-launch JSON, not a subscription option.
- Preserve device ID, password and hashes; reserve a new generation/salt on every actual launch.
- Close the previous client with its original cleanup grace period before retrying.
- Map unified to the old client's original audio mode and tcp_tls to its original tcp mode.
- Supply Auto JS bootstrap tokens through the private configuration, never OS process arguments.

## Ping and safety

Use the same selection path for normal connections and latency probes.
Retain the probe's existing non-interactive captcha policy.
Do not launch a second remote session with the same server/password while the VPN owns it.
Keep authenticated SOCKS, reserved UDP source ports and private packet-FD IPC with peer identity checks.

Retain original native protocol/crypto tests; test Legacy-first selection, one compatibility retry,
credential preservation and separate epochs for both connection and probe paths.
Validate source pins, exact patch inputs, checksums, native architecture and archive provenance.

Real interoperability and server device-binding restrictions require a real connection test.
