# Changelog

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
