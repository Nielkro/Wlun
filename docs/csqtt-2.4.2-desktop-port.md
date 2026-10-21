# CSQTT Legacy: desktop port notes

As of 2.4.3, only CSQTT 2.5.0 Legacy is supported. The separate 2.1.9 / 2.5.0 pre-release client and automatic protocol fallback from 2.4.2 have been removed at the owner's request.

Keep the original Legacy Rust client and deployment at `2066156119a5afe7d63e104aefdc8e0849999d03`. The pinned native archives, their checksums, and shared SOCKS security patches remain unchanged for arm64-v8a, armeabi-v7a and x86_64.

- Use Legacy for both connections and latency probes; never switch to another wire protocol after handshake or authentication failure.
- Preserve the original framing, authentication, worker scheduling, captcha and VK cleanup. Registration is `GETCONF:port|password|generation|salt|worker|desired-count`; disconnect is `DISCONNECT:password|salt`.
- Reserve a fresh generation and salt on every launch. Keep the active-session collision guard for probes.
- Keep the shared qWDTT account binding and launch-only Auto JS token handling. Do not put secrets in process arguments, logs or exported profiles.
- Retain authenticated SOCKS and private packet-FD IPC with peer identity checks.

The connection/probe regression test checks that a protocol rejection starts exactly one Legacy process, preserves credentials and removes its private configuration. It does not prove a live server connection.
