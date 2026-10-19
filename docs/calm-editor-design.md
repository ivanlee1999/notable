# Calm editor design

This pass separates writing controls from document navigation. It takes cues from the
[reMarkable toolbar](https://support.remarkable.com/s/article/Toolbar) and
[writing tools](https://support.remarkable.com/s/article/Writing-tools), without copying
their branding or changing Notable's stored pen presets.

## Decisions

- The title bar owns exit, notebook switching, page navigation, and the document menu.
  The notebook name and chevron are one target. Paper settings remain in the menu.
- The rail owns writing tools, ink, width, and history. Undo and redo stay reachable
  without scrolling. Removing duplicate document buttons leaves more room for tools.
- Tool selection is monochrome. Small ink markers identify presets; named palette
  cells and a checkmark make selection understandable on monochrome displays.
- Controls use 48dp targets. Rail dimensions and popup offsets share the same size
  constant; the raw-ink exclusion band continues to derive from those dimensions.
- The notebook switcher distinguishes recent notebooks, current notebook, and resume
  page. Navigation has an explicit close control and selected-tab semantics.
- There are no new animations, shadows, gradients, or ink-pipeline changes.

## Validation

The draft requires full JVM tests, blocking lint, CouchDB integration, schema/parity
checks, Android instrumentation, and review of captured narrow/large-font and
left/right rail screenshots before merge. The screenshot tests exercise the real
Compose components rather than a separate design mockup.

Physical BOOX ghosting, pen latency, and stroke handoff behavior cannot be established
by emulator screenshots. Those remain device-validation items.
