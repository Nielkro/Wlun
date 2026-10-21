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

## Shared VK hashes and tokens

Keep one qWDTT-style cookie login for qWDTT, CSQTT and WDTT Plus. Reference:
https://github.com/pegakmop/qwdtt-android/blob/main/app/src/main/java/com/wdtt/client/VkCallHashGenerator.kt

Resolve a nonempty, unexpired cached token; otherwise use the original cookie-based HTTP authorization and then its WebView fallback. A failed HTTP request must not prevent that fallback. Keep the actual login user agent. Extract only matching-state callbacks on the trusted OAuth endpoint, from query or fragment, rejecting ambiguous fields and control characters.
Avoid non-local returns from inline resource/loop blocks in Android token lookup, as documented by qWDTT's ART workaround. Retry an API authentication rejection only once with a refreshed token; retain rate-limit backoff and account-change checks.

For CSQTT Auto JS, allow an empty token in the saved editor model and fill a launch-only copy from the shared binding. A link's explicit token takes priority. Never persist an account-derived token to a profile, subscription, export or backup. Explicit hash_source=auto must override stale vk_anon_path=auto_js after subscription refresh.

## MASQUE links and Clash imports

Support masque://private-key@host:port?publickey=peer-key&address=IPv4/prefix,IPv6/prefix&network=h2&mtu=1280&sni=front.example.org#name. Decode escaped base64 without converting literal plus signs to spaces. Preserve HTTP version/fallback, TLS and QUIC settings when sharing. Register the scheme in common text/QR/file/subscription import and OS intent handling.

Clash files may use shared mappings and merge sequences. The supplied template needs 25 aliases: the previous cap of 20 rejected the entire file. Retain a finite alias cap (50), depth limit and expanded-node budget, including cycle protection during JSON conversion. Enable wireguard in the Clash protocol table, map its peer, reserved bytes, addresses and AmneziaWG options into the existing model, and keep numeric headers/ranges. The regression fixture contains synthetic keys only.
