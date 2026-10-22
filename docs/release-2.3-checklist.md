# Release 2.3

- Version: 2.3; index: 66; stable APK code: 66999; canonical tag: v2.3.0.
- Shared preferences and server tiles retain themed surfaces, 5 dp corners, ripple feedback and at least 48 dp touch targets.
- Deployment fields/buttons share aligned edges; weighted rows have gaps only between children. Long fields and SSH key actions use full width.
- Preference fragments and full-screen deployment/subscription settings share a 90 ms fade, without translation.
- Subscription editor uses Material's bottom-sheet window animation; unsaved-change handling and swipe dismissal logic are preserved.
- Subscription settings subtitle identifies the loaded group, including restored activity instances.
- No transport, deployment engine, subscription storage, signing or native dependency changes.
- Fixed a test-only race: SSH cancellation can finish before the test observes its intermediate stopping state. Final stop status, absence of errors, and exactly one stop request remain asserted.
- Validation: local XML parsing, release-numbering regression and diff checks; Android compilation/unit tests and SSTP race/vet through CI, signed release verification through the release workflow.
- Long emulator/UI tests intentionally omitted at the user's request. Visual alignment and animation on a physical phone require user verification.
