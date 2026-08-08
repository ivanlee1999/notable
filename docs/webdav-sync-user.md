# WebDAV Sync - User Guide

## Overview

Notable supports WebDAV synchronization to keep your notebooks, pages, and drawings in sync across
multiple devices. WebDAV is a standard protocol that works with many cloud storage providers and
self-hosted servers. Sync is experimental: keep an independent backup, especially before using
either replacement operation under **CAUTION: Replacement Operations**.

## What Gets Synced?

- **Notebooks**: notebooks, notebook order, and notebook settings
- **Pages**: pages, handwriting, drawings, and embedded image records
- **Images & backgrounds**: managed images and managed page backgrounds
- **Folders**: your folder organization structure, including notebook moves
- **Deletions**: notebook deletions

Not synced:

- standalone Quick Pages that are not inside a notebook;
- the file behind a linked external PDF path (only the file *reference* travels with a managed
  background, not an externally-linked PDF); and
- folder deletions reliably — a folder deleted on one device can reappear after another device
  syncs, although notebooks inside it are not deleted merely because the folder reappears.

When the same notebook changes on two devices, Notable now handles it in one of two ways: edits to
**different pages** merge automatically without losing anything, while a real clash — the **same page**
edited on both devices, or a structural change like adding/reordering/renaming pages on both — is
**flagged for you to resolve** rather than silently overwritten. See
[Conflict Resolution](#conflict-resolution). There is still no stroke-level merge within a single
page: resolving a same-page clash means choosing one whole version of that page.

## Prerequisites

You'll need access to a WebDAV server that allows Basic authentication and supports `HEAD`, depth-1
`PROPFIND`, `MKCOL`, `GET`, `PUT`, and `DELETE`. `MOVE` support improves manifest publication but is
not required.

### Popular WebDAV Providers

1. **Nextcloud** (recommended for self-hosting)
   - Free and open source, full control over your data
   - Use the personal WebDAV URL shown in Files settings, normally:
     `https://your-nextcloud.com/remote.php/dav/files/username/`
   - Some installations instead expect (or also accept) the older, username-less endpoint:
     `https://your-nextcloud.com/remote.php/webdav/`. If the `dav/files/username` form doesn't
     connect, try this one before assuming something else is wrong.
   - Nextcloud recommends an app password for third-party WebDAV clients — see
     [Using Two-Factor Authentication](#using-two-factor-authentication-2fa) below and the
     [Nextcloud WebDAV user manual](https://docs.nextcloud.com/server/latest/user_manual/en/files/access_webdav.html).

2. **ownCloud**
   - Similar to Nextcloud
   - URL format: `https://your-owncloud.com/remote.php/webdav/`

3. **Other providers**
   - Many NAS devices (Synology, QNAP) support WebDAV
   - Some web hosting providers offer WebDAV access
   - Copy the exact WebDAV URL shown by the product's own settings page instead of guessing its
     path — providers vary more than the examples above suggest

Box.com is intentionally not listed here: its old WebDAV endpoint is no longer a safe compatibility
assumption, so we can't recommend a URL format for it.

Use HTTPS unless the server is on a network you fully trust. Notable accepts HTTP but does not
protect credentials or note data from interception on an unencrypted connection.

## Setup Instructions

### 1. Get Your WebDAV Credentials

From your WebDAV provider, you'll need:
- **Server URL**: the WebDAV endpoint URL (see [Prerequisites](#prerequisites))
- **Username**: your account username
- **Password**: your account password or an app-specific password

**Important**: Notable automatically appends `/notable` to your server URL to keep your data
organized. For example:
- You enter: `https://cloud.example.com/remote.php/dav/files/alex/`
- Notable uses: `https://cloud.example.com/remote.php/dav/files/alex/notable/`

Don't add the trailing `/notable` yourself, or Notable will create `…/notable/notable/`.

#### Using Two-Factor Authentication (2FA)

If your Nextcloud account has two-factor authentication enabled, your regular password will not
work for WebDAV. You'll need to create an app-specific password:

1. Log in to Nextcloud via your browser
2. Go to **Settings** → **Security**
3. Under **Devices & sessions**, click **Create new app password**
4. Give it a name (e.g., "Notable")
5. Nextcloud will generate a username and password for this app
6. Use these generated credentials (not your regular login) when configuring Notable

Other WebDAV providers with 2FA may have a similar app password mechanism — check your provider's
documentation.

### 2. Configure Notable

1. Open **Settings → Sync**
2. Enter your **Server URL**, **Username**, and **Password** (or app password)
3. Choose **Save Credentials**

### 3. Test Your Connection

1. Choose **Test Connection**
2. This verifies that an authenticated `HEAD` request to the URL you entered succeeds, and shows any
   clock difference read from the server's `Date` header
3. It does **not** test file creation, available quota, the `/notable` directory, or every WebDAV
   method sync needs — a successful test can still be followed by a sync failure, and the Sync Log
   will contain the useful operation-level error
4. If the test fails, double-check your credentials and URL, and see
   [Troubleshooting](#troubleshooting)

Normal sync also stops if the device clock differs from the server by more than 30 seconds; enabling
automatic date and time is the easiest fix.

### 4. Enable Sync

1. Toggle **Enable WebDAV Sync**
2. Run **Sync Now** once and inspect the Sync Log before relying on automatic sync

## Sync Options

### Manual Sync

**Sync Now** queues a full two-way sync through Android WorkManager. It merges folders, applies
notebook deletion markers, uploads or downloads existing notebooks based on their timestamps,
downloads notebooks that are new to this device, and propagates local notebook deletions.

The "Last synced" time is only updated after a completely successful full sync. A run can continue
past one failed notebook so the rest still transfer, then finish as failed overall.

### Automatic Sync

**Auto sync every … minutes** creates periodic background sync (15–240 minutes). Android treats this
interval as a minimum and may delay it for battery, network, or system scheduling reasons — don't
expect sync exactly on the dot.

### Sync on App Start

Enable **Sync on app start** to queue a full sync automatically when Notable's main screen starts.
Turn it off if you'd rather sync only manually or on a schedule.

### Sync on Note Close

Enable **Sync when closing notes** to sync just the notebook you were editing when you close it, so
your latest changes upload promptly. This runs in-app rather than through WorkManager, syncs only
that one notebook (not a full sync), and quietly skips itself if another guarded sync is already
running.

### Check on Open

Enable **Check for newer version when opening a notebook** to have Notable do a quick, read-only
manifest check when you open a notebook: if the server timestamp is more than one second newer, it
offers a **Sync now** button (see [Sync Status Badges](#sync-status-badges)) that closes the editor
and syncs before you can edit a stale copy. An absent warning doesn't prove the server is current —
authentication, network, and other check errors are intentionally treated as "no warning" rather than
blocking you from opening the notebook.

### Wi-Fi Only

Requires an Android network reported as unmetered. This usually includes unmetered Wi-Fi and Ethernet
and excludes metered mobile data or metered Wi-Fi.

### Upload Only / Download Only

These two modes are mutually exclusive — turning one on turns the other off.

- **Upload only (skip remote changes)** pushes your local changes to the server without ever
  downloading. It **never modifies your local notebooks** and **never overwrites a newer copy on the
  server** — notebooks that are newer on the server are simply left alone and shown with the
  **Newer on server** badge. Remote deletions are not applied while upload-only is on. It still
  merges and writes `folders.json`, so it isn't a blind overwrite of every server file.

- **Download only (skip local changes)** is the mirror image: it pulls changes from the server
  without ever pushing uploads or local deletions. Local edits stay on this device and normally keep
  the **Not synced** badge. One exception: routine tombstone cleanup (pruning notebook-deletion
  markers older than 90 days) can still touch the server in this mode, so don't treat it as a
  strictly read-only client.

### Cancel a Running Sync

While a sync is running, a **Cancel** button asks WorkManager to cancel all work tagged for sync and
resets visible progress. A blocking network request in flight may not stop immediately.

Known limitation: the periodic schedule shares that same tag, so Cancel also removes automatic
periodic sync for the rest of the current app session — restarting Notable recreates it from your
saved settings. Sync-on-close and check-on-open run outside WorkManager and aren't reliably covered
by Cancel either.

## Sync Status Badges

Each notebook cover in the library shows a small icon reflecting its sync status:

| Icon | Status | Meaning |
|------|--------|---------|
| ☁️✓ (cloud-check) | **Synced** | Local timestamp matches the last recorded successful notebook commit. This is not a live server check. |
| ☁️✕ (cloud-off) | **Not synced** | No sync record exists, or the notebook has local changes beyond the one-second tolerance. |
| 🕐 (clock) | **Scheduled** | A full sync is running and this notebook is waiting its turn. |
| 🔄 (sync) | **Syncing** | This notebook is the current transfer item. |
| ☁️⬇ (cloud-download) | **Newer on server** | Upload-only mode found a newer server copy and did not download it. |
| ⚠️ (alert) | **Error** | The latest upload/download attempt for this notebook failed — check the Sync Log. |
| 🔄❗ (sync-problem) | **Conflict** | The same page (or the notebook's structure) was edited on two devices at once. **Tapping the notebook opens the resolution dialog instead of the editor** — see [Conflict Resolution](#conflict-resolution). The badge stays until you resolve it. |

The badges are informational and update automatically. A notebook with no badge simply has no
recorded sync state yet; the first sync after an app upgrade that introduced this state table
repopulates it for every notebook, so notebooks may briefly show **Not synced** until that sync
completes.

## Advanced Features

### Force Operations (Use with Caution!)

Located under **CAUTION: Replacement Operations**. Neither operation has an undo.

- **Upload All (Replace Server with Local Data)**: uploads every local notebook, then deletes server
  notebook directories that aren't present locally. It doesn't wipe the server before uploading, but
  a partial failure can still leave a mixture of old and new server content and finish with an error.
  `folders.json` is only written when your local folders are non-empty, and remote tombstones aren't
  cleared wholesale.

- **Download All (Replace Local with Server Data)**: checks that the server notebook directory
  exists and is non-empty, then deletes all local folders, notebooks, and sync-state rows before
  downloading. That check exists specifically so an empty or unreachable server can't wipe your
  device. It is **not transactional** — if the connection, parsing, storage, or database fails after
  local data has already been cleared, the device can end up with only a partial restore. Keep a
  backup and be prepared to run it again.

**Warning**: these operations replace one side wholesale. Make sure you know which copy of your data
is correct before using them.

## Conflict Resolution

### Notebook Deletion Conflicts

Notebook deletions use zero-byte marker files on the server. If a notebook was deleted on one device
but has a local edit timestamp later than that server deletion marker on another device, Notable
**resurrects** it — keeps it and re-uploads it — instead of deleting it. This prevents accidental
data loss, at the cost of an occasional deletion not "sticking."

### Timestamp-Based Sync

When both copies changed and one is clearly newer, Notable compares whole-notebook timestamps and the
newer one wins:
- local newer by more than one second → upload
- server newer by more than one second → download
- within one second → the two are compared more closely (see below)

### When Edits Overlap

If the timestamps are effectively tied but the copies genuinely differ, Notable looks at *what*
changed instead of just *when*:

- **Different pages on each device** — these don't actually conflict, so they're **merged
  automatically**: the changes from both sides are combined and nothing is lost.
- **The same page on both devices**, or a **structural change** (pages added, removed, reordered, or
  renamed, or notebook settings changed) on both — these can't be combined, so Notable **stops and
  asks you** instead of overwriting. The notebook gets the **Conflict** badge and is not synced
  further until you resolve it.

### Resolving a Conflict

Tap a notebook showing the **Conflict** badge. Instead of opening the editor, Notable shows a
**Resolve sync conflict** dialog:

- **Page conflicts** are listed one at a time, each with a thumbnail of your local version and its
  page number. For each, choose:
  - **Keep mine** — upload your version, replacing the server's copy of that page;
  - **Use server** — download the server's version over yours;
  - **Skip** — decide later; the page stays flagged and is asked again on the next sync.
- **Structural conflicts** are resolved for the whole notebook at once — **Keep mine** or **Use
  server** — which also settles any page edits in the same direction.

Once every conflict is resolved, Notable syncs and the badge clears. A failed resolution keeps the
dialog open and shows the error rather than losing your choice.

**Note:** conflicts can only be resolved in normal two-way sync. If you're in **Upload only** or
**Download only** mode, resolving is refused with a message to switch back to two-way sync first —
resolving has to move data in both directions, which a one-way mode can't do. The badge stays until
you switch and resolve.

There is still no merge *within* a single page: resolving a same-page clash keeps one whole version of
that page. To avoid conflicts entirely, sync before switching devices and avoid leaving the same
notebook open on two devices at once.

## Sync Log

The **Sync Log** section shows real-time information about sync operations: which notebooks were
synced, upload/download activity, and any errors that occurred. It holds only the latest 50
in-memory entries, so copy it promptly if you need to report a problem. Choose **Clear** to clear it.

## Troubleshooting

### Connection Succeeds but Sync Fails

**Problem**: Test Connection passes, but **Sync Now** still fails.

**Solutions**:
1. Confirm the entered URL is the writable WebDAV files endpoint, not a normal website page
2. Confirm the account can create a folder and upload a file outside of Notable
3. Use an app password when your provider requires one (see [2FA](#using-two-factor-authentication-2fa))
4. Check server quota and Android device storage
5. Confirm the server supports `HEAD` and depth-1 `PROPFIND`
6. Inspect and copy the Sync Log immediately — it contains only the latest 50 entries

### Notebooks Not Appearing on Other Device

**Problem**: Synced on one device but not showing on another.

**Solutions**:
1. Run **Sync Now** on the source device and wait for success
2. Run **Sync Now** on the destination device
3. Confirm both devices use exactly the same base URL and account
4. Look under `/notable/notebooks/{id}/` in the provider's file UI
5. Check for **Error**, **Not synced**, or **Newer on server** status
6. Confirm the notebook isn't a standalone Quick Page — those never sync

### A Notebook Is Missing an Image or Background

**Problem**: A synced notebook opens, but an image or page background is blank on another device.

**Why**: If a remote image or background 404s, Notable logs it, keeps downloading the rest of the
notebook, and still marks the notebook synced — the missing item just appears blank and is not
automatically fetched later even if it later appears on the server. The same leniency applies in the
other direction: a locally referenced media file that can't be found is logged but doesn't stop an
upload, so the rest of the notebook isn't held hostage by one missing file.

**Solutions**:
1. On the device that still has the file, open the affected notebook, make a small edit, and sync —
   this re-uploads the media
2. Linked external PDFs are intentionally not copied by design — make the same file available
   separately on each device

### Very Slow Sync

**Problem**: Sync takes a long time to complete.

**Why**: The first sync uploads every page and media file. Later syncs are incremental — they use
ETags to skip unchanged manifests, and both upload and download move **only the pages that actually
changed**, so editing one page of a large notebook transfers just that page rather than the whole
book. A changed notebook still performs existence checks for its media and lists remote directories
for cleanup. Large page and media transfers are buffered in memory, so very large individual notes or
files can still use substantial memory.

**Solutions**:
1. This is normal for the first sync with many notebooks — subsequent syncs are faster
2. Check your internet connection speed
3. Consider reducing auto-sync frequency
4. Expect larger images or backgrounds to take longer

### A Deletion Reappears

**Problem**: A notebook you deleted comes back after syncing.

Run a normal full sync on the device that deleted the notebook, before its deletion tombstone ages
out (90 days). Folder deletions specifically are not propagated reliably and can reappear by design
of the current folder merge, even though notebooks inside a reappeared folder aren't deleted along
with it.

### Safe Recovery Order

When something looks wrong and you're not sure which side to trust:

1. Stop editing the affected notebook on all devices
2. Make an external backup of the known-good side
3. Test the connection and inspect the Sync Log
4. Try an ordinary **Sync Now**
5. Only use a replacement operation once you're certain which side is authoritative

## Data Format

Notable stores your data on the WebDAV server in the following structure:

```
/notable/
├── folders.json             # Folder hierarchy
├── deletions/              # Tracks deleted notebooks (zero-byte files)
│   └── {notebook-id}
└── notebooks/
    ├── {notebook-id-1}/
    │   ├── manifest.json    # Notebook metadata
    │   ├── pages/
    │   │   └── {page-id}.json
    │   ├── images/
    │   │   └── {image-file}
    │   └── backgrounds/
    │       └── {background-file}
    └── {notebook-id-2}/
        └── ...
```

### Efficient Storage

- **Strokes**: Base64-encoded inside page JSON, using Notable's current binary stroke encoding
  (format version 2), optionally LZ4-compressed
- **Images/backgrounds**: stored as plain files
- **JSON files**: human-readable metadata (notebook manifest and folder hierarchy)

This is a storage encoding, not encryption.

## Privacy & Security

- **Credentials**: your password is encrypted at rest in Notable's local Room database, using an
  AndroidKeyStore-backed AES-GCM key
- **Data in transit**: use HTTPS for secure communication — Notable does not enforce it, but strongly
  recommends it outside a fully trusted network
- **Data at rest on the server**: notebook data is not end-to-end encrypted by Notable; server-side
  encryption, retention, backups, and account security depend entirely on your provider
- **No third-party cloud service**: your data only goes to the WebDAV server you specify

## Best Practices

1. **Use HTTPS**: always use `https://` URLs unless you fully trust the network
2. **Regular syncs**: enable automatic sync, or sync deliberately before switching devices, to avoid
   discarding edits
3. **Backup**: keep a separate backup, especially before an Upload All / Download All operation
4. **Test first**: use Test Connection before enabling sync, but don't treat a pass as a full
   sync-readiness check
5. **Monitor logs**: check the Sync Log occasionally, and copy it promptly after a failure — it only
   keeps 50 entries
6. **Dedicated folder**: the `/notable` subdirectory Notable creates keeps things organized; don't
   add it to the URL yourself

## Getting Help

If you encounter issues:

1. Check the Sync Log for error details
2. Verify your WebDAV server is accessible and writable outside of Notable
3. Try the troubleshooting steps above
4. Report issues at: https://github.com/Ethran/notable/issues

## Technical Details

For developers interested in how sync works internally, see
[WebDAV Sync Technical Documentation](webdav-sync-technical.md) — architecture, sync protocol, data
formats, and conflict resolution.

---

**Version**: 3.0
**Last Updated**: 2026-08-08 — documented the conflict-resolution flow: automatic merging of edits to
different pages, the **Conflict** badge and the tap-to-resolve dialog (Keep mine / Use server / Skip),
whole-notebook resolution for structural conflicts, and the refusal to resolve in upload-only /
download-only modes. Also updated the "What Gets Synced?" and slow-sync notes to reflect per-page
(incremental) transfer.
