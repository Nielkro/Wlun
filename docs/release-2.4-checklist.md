# Release 2.4

- Version 2.4; index 67; stable APK code 67999; tag v2.4.0.
- Subscription editor: title/URL category comes before options; settings visibility and cached values are unchanged.
- Editor panel starts Material's bottom-sheet entrance after preference content is loaded and laid out. Activity entrance is suppressed to avoid double animation; restored activities do not replay the entrance.
- Shared preference layouts reserve one fixed icon column, including asynchronous VK account/hash rows. Existing switch/checkbox widgets, selector handling, color preview and unlink action remain available. Explicit custom preference layouts are preserved.
- Neutral outlines use one physical pixel and a low-opacity theme-aware grey; selected items keep their accent.
- Outlines cover preference tiles, home/menu tiles, deployment/server tiles and shared Material cards.
- Validate XML structure, preference order, standard widget IDs, release numbering, full Android CI/unit tests, SSTP race/vet, signed APK metadata and checksums.
- Long emulator/UI tests omitted at the user's request. Phone rendering and perceived animation smoothness require user verification.
