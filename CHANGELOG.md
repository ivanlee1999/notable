# Changelog

## 0.5.0

### Naming things

- **A new folder or notebook asks what to call it.** They used to be created as "New Folder"
  and "New notebook", and renaming one meant knowing that a long press opens its settings. The
  suggested name arrives filled in and selected, so accepting it and typing over it are each a
  single tap. If you would rather name things later, turn the prompt off in Settings → General.
- **Rename from the long-press menu**, for folders, notebooks and now pages.
- **Quick pages can be named.** They were told apart only by their thumbnails. Each now carries
  a label underneath: its name, or the date it was created if you have not given it one.

### Fixes

- **A renamed folder could quietly change back.** The rename was saved without updating the
  folder's change time, so the next sync judged the other device's copy to be the newer one and
  restored the old name.
- **A name could be lost on the way out of the dialog.** Titles were saved when the text field
  lost focus, which is not something a closing dialog guarantees. A name is now saved when you
  confirm it.
- **The notebook settings dialog showed the text `${bookTitle}`** where the notebook's name
  belonged, for any linked file path longer than 32 characters — in practice, all of them.
- **A deleted notebook could be brought back by your other device.** A deleted document reads as
  a 404, the same answer an unknown notebook gets, and it was taken to mean "never existed" — so
  the next push recreated it.
- **WebDAV kept syncing while CouchDB was selected.** Closing a note ran a WebDAV sync even with
  the Couch backend active, leaving two engines writing the same notebooks over different
  transports. Every path now follows the backend switch.

### Upgrading

The database schema moves from 38 to 39 to store page names. The migration is additive and runs
automatically, but **it is one-way** — installing an older build over this one will fail to open
the database.

If you sync with bopa on an iPad, update it as well. Builds of it from before this release do
not know about page names, and drop them when they sync.

## 0.4.0

- **Vertical navigation can turn pages instead of scrolling.** Continuous scrolling follows your
  finger, which on e-ink means a run of partial refreshes and a visible smear. The new Paged mode
  advances exactly one screen per swipe, so a turn is a single full refresh and a line always
  lands in the same place; a small overlap keeps the last line of the outgoing screen in view.
  Choose it under Vertical Navigation. Continuous remains the default.
- Polish translations for the Library and rail strings added in 0.3.0.

## 0.3.0

### CouchDB sync

A second sync backend, alongside WebDAV. Pick it in Settings → Sync. WebDAV remains the
default and is unchanged.

- **Your writing syncs while you write.** Previously a stroke only left the device when you
  closed the note, restarted the app, or waited out a background job that can be 15 minutes
  apart — so a note could sit on the tablet looking synced while the other device never saw it.
  Changes now upload about three seconds after you stop writing.
- **Changes from your other device arrive in seconds**, instead of whenever the next poll
  happened to run.
- **Editing the same page on two devices no longer loses work.** The old behaviour overwrote
  the whole notebook whenever the two clocks differed by more than a second, silently. Ink from
  both devices is now kept.
- **Erasing sticks.** Erased strokes are recorded, so the other device stops restoring them.
- **Works offline.** Edits queue on the device and upload when you reconnect.

### Fixes

- "Sync now" no longer does nothing when a sync is already running. It was queued with a policy
  that discarded the request, silently, which is one of the ways an edit could appear to vanish.
- The preview build workflow no longer overwrites the repository's `google-services.json` with
  an empty file when no `FIREBASE_CONFIG` secret is set, which failed the build.

### Upgrading

The database schema moves from 37 to 38 to record erased strokes. The migration is additive and
runs automatically, but **it is one-way** — installing an older build over this one will fail to
open the database.

Nothing changes for WebDAV users until they switch backends. Switching does not migrate existing
notes: point both devices at the same CouchDB server and use "Upload everything on this BOOX"
once to seed it.

### Known limitations of CouchDB sync

- Deleting a *page* does not propagate to the other device yet. Deleting a whole notebook does.
- Erasing an image does not propagate yet.
- A pen type this build does not recognise arrives as a ballpoint; the ink itself is kept.
- Standalone quick pages are not synced, only pages inside notebooks.

## 0.2.6 and earlier

See the release history on GitHub.
