# Release 2.4.1

- Version 2.4.1; index 68; stable APK code 68999; tag v2.4.1.
- Restore default visibility of preference widget containers; AndroidX hides empty containers itself but does not unhide containers when inflating switches.
- Add a translucent manifest activity for subscription editor launches. Full-screen subscription settings retain their opaque activity. Apply the transparent overlay after selecting the user's theme.
- Decode bounded, nested HTTPS ssconnect.app q wrappers locally before Happ/Incy decoding; reject duplicate payloads, lookalike hosts and excessive nesting.
- Offer per-subscription insecure TLS permission at initial import and manual subscription creation, default off. Persist it before the first fetch; no global TLS bypass.
- Live supplied CSQTT endpoint: trusted TLS rejects its certificate; explicit diagnostic bypass returns HTML containing a valid AES-GCM v2 link. Secret URLs and passwords are not committed.
- Keep CSQTT 2.1.9 cleartext links, preliminary 2.5.0 AES-GCM links and current 2.5.0 Legacy links. Added independent/synthetic compatibility vectors and HTML regressions.
- Deployment and native transport pins remain unchanged: 2.5.0 Legacy only.
- Regression tests cover widget visibility, translucent launch metadata, website wrappers and CSQTT link formats. Full Android CI, SSTP checks and signed release verification gate publication.
- Long UI tests omitted. Phone animation/rendering and a live CSQTT tunnel require user verification.
