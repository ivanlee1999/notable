# October 2026 engineering review

Fork: `ivanlee1999/notable`; upstream: `Ethran/notable`.
Baseline: fork `11f11000`, upstream `ec38948b`.

## Integration decisions

- Keep the fork's device-local drawing limits, zoom-aware eraser, fitted sheets, continuous
  seams, text boxes, memory-bounded undo, save failure indicator and per-page write ordering.
- Integrate upstream editor-owned page snapshots and cancellation ownership checks. Outgoing
  editors must persist their own page, and stale loaders must not remove a replacement load.
- Retain notebook outline/bookmark tabs while preventing stale QuickNav loads from overwriting
  the selected page's state.
- Integrate density scaling with the fork's light e-ink palette and existing dialog behavior.
- Apply atomic sync-setting updates to both WebDAV and CouchDB. Unrelated edits preserve the
  stored encrypted secrets without a decrypt/re-encrypt round trip. Log setting keys only.
- Keep the established CI signing secrets and release identity; no new key or credentials.

## Existing pull requests

PR [117](https://github.com/ivanlee1999/notable/pull/117) remains open. Its handwriting
recognition change conflicts with main, proposes schema 49 (main already uses 50), and its
successful check is from August. Its firmware recognizer also needs BOOX hardware validation.
It is not suitable for a blind merge or inclusion in this release.

## UX and platform references

- [reMarkable basics](https://support.remarkable.com/s/getting-started/step-2): writing tools,
  thickness and color grouped in the toolbar. The support page's full content requires its
  client application; indexed official guidance was available. No proprietary assets used.
- [BOOX Pen SDK](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md):
  point-list delivery precedes pen-end; raw drawing and rendering have separate enable flags;
  the limit rectangle belongs to the host view.
- [Android stylus lifecycle](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features):
  cancellation removes the current motion set; preserve pressure, tilt and orientation.
- [Android front-buffer renderer](https://developer.android.com/reference/androidx/graphics/lowlatency/GLFrontBufferedRenderer):
  transient rendering and completed content must share one lifecycle.

Validation results will be recorded when the release candidate passes.
