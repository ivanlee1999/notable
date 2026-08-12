# WebDAV Sync — Technical Documentation

This document describes Notable's WebDAV architecture, protocol, data formats, and design tradeoffs.
For setup and usage, see the [user guide](webdav-sync-user.md).

## 1. Architecture overview

`SyncOrchestrator` owns the process-wide mutex and normal-flow sequencing. Focused services handle
preflight checks, folder sync, notebook reconciliation, transfers, and replacement operations.
`WebDavClientFactoryPort` abstracts client construction, but transfer services still depend on the
concrete `WebDAVClient` and broad `AppRepository`. This coupling limits executor-level testing.

Transfer is page-based. The `page_sync_state` table identifies pages changed since the last committed
sync; a page is the smallest transferable unit.

Reconciliation has two regimes:

- **Clear winner.** When one `Notebook.updatedAt` is more than one second later, that side wins.
- **Concurrent edits.** When the timestamps tie within tolerance but the manifest ETag differs, the
  engine distinguishes independent page edits from same-page or structural conflicts. Independent
  edits merge; conflicts receive a `CONFLICT` badge and require user resolution. `ASK` is the only
  implemented conflict strategy.

The protocol has commit points but no atomic notebook snapshot. Pages precede the manifest on upload;
page replacements precede the notebook-row commit on download. This leaves several gaps:

- upload writes live page files before publishing `manifest.json`; if a page id is unchanged, an
  interrupted update can expose new page content through an *old* manifest;
- download replaces pages one at a time before committing the notebook row; a failed download is
  retried on the next sync because the timestamp stays stale, but partially replaced pages can
  already be visible locally in the meantime;
- media 404s and missing local media are deliberately treated as non-fatal, so a notebook can commit
  while still referring to a missing image or background.
## 2. Components

All sync code lives in `com.ethran.notable.sync`.

```
UI / lifecycle triggers
├── WorkManager path
│   ├── manual sync, app-start sync, periodic sync
│   ├── force upload/download
│   └── targeted notebook deletion
│       SyncScheduler → SyncWorker → SyncOrchestrator
└── in-process path
    ├── sync on editor close
    └── check on notebook open
        EditorViewModel → SyncOrchestrator

SyncOrchestrator
├── SyncPreflightService
├── FolderSyncService
├── NotebookReconciliationService → NotebookSyncPlanner
├── NotebookSyncService → PageSyncSelector
└── SyncForceService

conflict resolution (user-driven, holds the sync mutex)
SyncOrchestrator.resolvePageConflict / resolveNotebookConflict
    → NotebookSyncService (rebaseline sync rows)
    → NotebookReconciliationService (the follow-up transfer)

WebDavClientFactoryPort → WebDAVClient → shared OkHttpClient
AppRepository → Room repositories and notebook_sync_state
SyncProgressReporter → settings progress UI and notebook badges
```

| File | Role |
|---|---|
| [`SyncScheduler.kt`](../app/src/main/java/com/ethran/notable/sync/SyncScheduler.kt) | Creates unique one-time work and one unique periodic job. Periodic work uses a minimum 15-minute interval and a `CONNECTED` or `UNMETERED` constraint. |
| [`SyncWorker.kt`](../app/src/main/java/com/ethran/notable/sync/SyncWorker.kt) | `CoroutineWorker` for WorkManager integration. Performs connectivity/settings checks, decodes `SyncRequest`, invokes the orchestrator, and maps `DomainError` to WorkManager success/failure/retry. |
| [`SyncOrchestrator.kt`](../app/src/main/java/com/ethran/notable/sync/SyncOrchestrator.kt) | Owns normal-flow sequencing, the process-wide sync mutex (companion-object `Mutex`), settings reads, progress transitions, and the successful full-sync timestamp. |
| [`SyncPreflightService.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPreflightService.kt) | Enforces the unmetered constraint, checks server clock skew, and ensures the root collections exist. |
| [`FolderSyncService.kt`](../app/src/main/java/com/ethran/notable/sync/FolderSyncService.kt) | Reads, merges, applies, and conditionally writes `folders.json`. |
| [`NotebookReconciliationService.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookReconciliationService.kt) | Fetches remote manifest metadata and executes the planner's decision for local notebooks. |
| [`NotebookSyncPlanner.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncPlanner.kt) | Pure per-notebook decision — `Upload` / `Download` / `Skip` / `Reconcile` / `SkipUploadOnly` / `SkipDownloadOnly` (unit-tested). |
| [`PageSyncSelector.kt`](../app/src/main/java/com/ethran/notable/sync/PageSyncSelector.kt) | Pure per-page dirty-selection: which pages changed since their last committed sync (unit-tested). |
| [`NotebookSyncService.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt) | Notebook/page/media transfer, per-page conflict detection and resolution rebaselining, notebook tombstones, new-notebook discovery, and local/remote garbage collection. |
| [`SyncConflictStrategy.kt`](../app/src/main/java/com/ethran/notable/sync/SyncConflictStrategy.kt) | The `ASK` / `SERVER_WINS` / `LOCAL_WINS` strategy enum (only `ASK` implemented) plus the resolution value types (`PageConflictResolution`, `NotebookConflictResolution`, `NotebookConflict`). |
| [`ETag.kt`](../app/src/main/java/com/ethran/notable/sync/ETag.kt) | Value type for a WebDAV ETag: parses the server's spelling (weak `W/` prefix, quotes), compares by validator rather than raw string, and derives the `If-Match` write guard. |
| [`SyncForceService.kt`](../app/src/main/java/com/ethran/notable/sync/SyncForceService.kt) | "Replace server with local" and "replace local with server" flows. |
| [`WebDAVClient.kt`](../app/src/main/java/com/ethran/notable/sync/WebDAVClient.kt) | Synchronous OkHttp WebDAV operations: `HEAD`, `GET`, conditional `GET`, `PUT`, `MKCOL`, `DELETE`, `MOVE`, and depth-1 `PROPFIND`, funneled through one private `execute()` helper. |
| [`WebDavXml.kt`](../app/src/main/java/com/ethran/notable/sync/WebDavXml.kt) | PROPFIND XML parsing (hrefs, entries) and UUID validation. |
| [`SyncPorts.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPorts.kt) | DI port/adapter for WebDAV client creation, plus the shared singleton `OkHttpClient` — one connection pool for all sync operations. |
| [`SyncPaths.kt`](../app/src/main/java/com/ethran/notable/sync/SyncPaths.kt) | Centralized server path construction (root, notebooks, deletions, per-notebook manifest/pages/images/backgrounds, tombstones). |
| [`NotebookSyncStatusStore.kt`](../app/src/main/java/com/ethran/notable/sync/NotebookSyncStatusStore.kt) | Derives per-notebook badges from Room state, notebook timestamps, and live progress. Nothing extra is stored — the badge is a pure function of those three sources. |
| [`SyncWorkUiBridge`](../app/src/main/java/com/ethran/notable/sync) | Converts completed WorkManager jobs into terminal snack messages. |
| [`SyncProgressReporter.kt`](../app/src/main/java/com/ethran/notable/sync/SyncProgressReporter.kt) / [`SyncState.kt`](../app/src/main/java/com/ethran/notable/sync/SyncState.kt) | Owns the `SyncState` `StateFlow` consumed by the settings UI and the badge store. |
| [`serializers/NotebookSerializer.kt`](../app/src/main/java/com/ethran/notable/sync/serializers/NotebookSerializer.kt) | Serializes/deserializes notebooks, pages, strokes, and images to/from JSON. |
| [`serializers/FolderSerializer.kt`](../app/src/main/java/com/ethran/notable/sync/serializers/FolderSerializer.kt) | Serializes/deserializes the folder hierarchy to/from `folders.json`. |
| `notebook_sync_state` (Room, `data/db`) | Per-notebook commit bookkeeping table — see [section 5.10](#510-per-notebook-and-per-page-sync-state). |
| `page_sync_state` (Room, `data/db`) | Per-page commit bookkeeping (ETag + change anchor) that drives dirty-page selection and per-page conflict detection — see [section 5.10](#510-per-notebook-and-per-page-sync-state). |
| `KvProxy` + [`CryptoHelper`](../app/src/main/java/com/ethran/notable/data/db) | Credentials are persisted to the app key-value Room table and encrypted with an AndroidKeyStore-backed AES-GCM key. |
| [`ConnectivityChecker.kt`](../app/src/main/java/com/ethran/notable/sync/ConnectivityChecker.kt) | Queries Android `ConnectivityManager` for network/Wi-Fi availability. |
| [`SyncLogger.kt`](../app/src/main/java/com/ethran/notable/sync/SyncLogger.kt) | Maintains a ring buffer of the last 50 log entries, exposed as a `StateFlow`, for the sync UI. |

## 3. Sync protocol

### 3.1 Full sync flow (`syncAllNotebooks`)

`SyncOrchestrator.syncAllNotebooks()` uses `tryLock()` on the companion-object `Mutex`. If it cannot
acquire the lock, it returns `SyncInProgress` rather than queueing behind the running sync.

```
1. INITIALIZE
   ├── Read persisted settings; require sync enabled and username/password non-blank
   ├── If Wi-Fi-only is enabled, require an unmetered active network
   ├── Build a client from the configured base URL and credentials
   ├── HEAD the base URL, parse its HTTP Date header, reject absolute clock skew over 30s
   ├── Ensure /notable, /notable/notebooks, and /notable/deletions exist (MKCOL)
   └── One depth-1 PROPFIND of /notable/notebooks; the id set is shared by steps 4 and 5

2. SYNC FOLDERS
   ├── If folders.json exists, fetch it with its ETag
   ├── Merge: a local folder replaces its remote counterpart only when its updatedAt is later
   ├── Apply the merged set locally, unless upload-only is enabled
   └── Write it back with If-Match, unless download-only is enabled
       (a weak or absent server ETag drops the precondition rather than failing; the merge is a
        union, so an unguarded write cannot drop a remote folder)
       (if the file is absent, non-empty local folders are uploaded unless download-only)

3. APPLY REMOTE NOTEBOOK TOMBSTONES  (skipped in upload-only mode)
   ├── List /notable/deletions with getlastmodified
   ├── For each UUID tombstone: delete the local notebook, UNLESS its updatedAt is after the
   │   tombstone time (resurrection — see section 5.7)
   ├── Remove the notebook's notebook_sync_state row after local deletion
   ├── Best-effort delete tombstones older than 90 days — this still runs in download-only mode,
   │   so that mode is not strictly read-only on the server
   └── Return the tombstoned-id set, used to suppress re-downloading those ids in step 5

4. SYNC EXISTING LOCAL NOTEBOOKS
   └── For each local notebook (per-item progress carries the notebook id):
       ├── Existence = "id in the shared PROPFIND set?" (no per-notebook HEAD)
       ├── If remote absent → upload, unless download-only is enabled
       └── If remote present:
           ├── With a stored ETag, GET manifest.json with If-None-Match; a 304 means "unchanged"
           ├── Otherwise GET the manifest and its ETag
           └── NotebookSyncPlanner.decide(...) returns Upload / Download / Skip /
               SkipUploadOnly / SkipDownloadOnly (see section 5.9 for the exact rule)
   A missing manifest inside an existing remote directory is treated as an interrupted remote
   upload: normal/upload-only mode re-uploads the local notebook; download-only mode skips it.
   Per-notebook errors are accumulated; other notebooks still continue, but the run finishes as
   failed if any error remains.

5. DOWNLOAD NEW REMOTE NOTEBOOKS  (skipped in upload-only mode)
   Candidate ids = remote ids − ids present before download − tombstoned ids
                   − all ids still present in notebook_sync_state
   (the last subtraction stops a locally-deleted, previously-synced notebook from being
   re-downloaded before its tombstone uploads)

6. UPLOAD LOCAL NOTEBOOK DELETIONS  (skipped in download-only mode)
   deletedLocally = notebook_sync_state ids − local ids captured before downloads
   For each id: delete the remote notebook directory, PUT a zero-byte tombstone, then drop the
   local sync-state row once the tombstone PUT succeeds

7. FINALIZE
   ├── Bidirectional mode only: best-effort delete orphan remote notebook directories that have
   │   no manifest, aren't local, and whose directory lastModified is older than seven days
   └── On full success, persist SyncSettings.lastSyncTime = now (not updated by single-notebook
       sync, deletion work, or force operations)
```

### 3.2 Per-notebook upload

1. Ensure the `pages`, `images`, and `backgrounds` collections exist.
2. Upload only the **dirty** pages — the ones `PageSyncSelector` reports changed since their
   `page_sync_state` anchor. Editing one page of an 800-page notebook PUTs one page, not 800; skipped
   pages keep their existing row. For each dirty page: load its strokes and image rows, stream compact
   page JSON to a cache file one element at a time (bounding upload memory), stream that file to
   `pages/{pageId}.json`, and upload each managed image/background only when a preceding `HEAD` says it
   is absent.
   A page unchanged *locally* can still be **missing on the server** (another device deleted its file
   or the whole remote directory). One depth-1 `PROPFIND` of `pages/` — read once for names and ETags —
   force-uploads any manifest page absent from that listing, guarded with `If-None-Match: *` so a page
   another device recreated first 412s instead of being overwritten. If the listing can't be read, every
   page is re-uploaded (safe and self-healing).
3. If any page transfer failed, do **not** publish the manifest — the old commit marker stays in
   place and the notebook is retried on the next sync.
4. Otherwise publish the manifest last: PUT `manifest.json.tmp`, then `MOVE` it over `manifest.json`,
   using the destination `If` header when an ETag was read. On a non-412 MOVE failure, fall back to a
   direct guarded PUT; a 412 is a real concurrency conflict and is propagated.
5. On manifest success: mark the notebook `SYNCED`, best-effort remove a resurrection tombstone, then
   list and prune unreferenced remote page/media files.

The code does not verify that every id in `Notebook.pageIds` was actually returned from Room, so a
missing local page can leave a manifest reference with no uploaded page file.

### 3.3 Per-notebook download

1. GET and deserialize `manifest.json`, retaining its ETag.
2. If the notebook is new locally, insert it with `updatedAt = Date(0)` — a sentinel that guarantees
   a partial download still reads as "remote newer" and gets retried.
3. List `pages/` once for its ETags and fetch **only the pages whose ETag differs** from our stored
   `page_sync_state` row (a page unchanged on the server is skipped and keeps its local content).
   A page absent from a failed/empty listing is treated as "can't tell" and fetched. For each fetched
   page: GET and fully deserialize the page JSON, download absent images and managed backgrounds, then
   replace that page, its strokes, and its image rows in one Room transaction.
4. If every fetched page completed without a transient/DB error, commit in one transaction: write the
   notebook with the remote timestamp, upsert the fetched pages' `page_sync_state` rows, drop rows for
   departed pages, mark the notebook `SYNCED`, and best-effort prune local pages no longer in the
   manifest. A killed download writes no rows, so the next sync re-fetches the same pages.

A media 404 is logged and dropped — it does not block the notebook commit. Other media failures do.
Corrupt individual stroke/image DTOs are skipped by the serializer rather than failing the whole
page.

### 3.4 Single-notebook sync and targeted deletion

Sync-on-close and check-on-open run in-process (application `CoroutineScope`), not through
WorkManager. Sync-on-close reuses the same timestamp-comparison logic as step 4 above but on a single
notebook; if the orchestrator's mutex is already held by a full/periodic sync, it quietly returns
success rather than racing it. Check-on-open is a read-only conditional manifest `GET` that never
takes the mutex and treats every error/ambiguity as "not newer." Targeted notebook deletion routes
through WorkManager but does not take the orchestrator's mutex either.

### 3.5 Triggers, cancellation, and UI notifications

| Trigger | Execution path | Scope | Notes |
|---|---|---|---|
| Sync Now | WorkManager | Full | One-time unique work, `ExistingWorkPolicy.KEEP`. |
| App start | WorkManager | Full | Runs only when `syncEnabled && syncOnAppStart`. |
| Periodic | WorkManager | Full | Approximate WorkManager schedule, 15–240 minutes in the UI, `ExistingPeriodicWorkPolicy.UPDATE`. |
| Note close | In-process, application scope | One notebook | Skips successfully if the sync mutex is busy; no WorkManager retry/output handling. |
| Check on open | In-process, application scope | Read-only manifest check | Does not take the sync mutex. |
| Notebook deletion | WorkManager | One tombstone/delete | Does not take the orchestrator's mutex. |
| Force upload/download | WorkManager | All data on one side | Uses the mutex, but does not publish normal progress steps. |

All work currently carries the same `sync-work` tag. The settings **Cancel** button calls
`cancelAllWorkByTag`, which therefore also cancels the periodic schedule for the rest of the current
app session; `MainActivity` recreates it on a later app start, but it is not immediately restored by
Cancel itself. Blocking OkHttp calls may not stop the instant Cancel is pressed, since coroutine
cancellation is not guaranteed to interrupt an in-flight synchronous request.

## 4. Data format

### 4.1 Server directory structure

All paths are relative to the user-entered WebDAV base URL. `WebDAVClient` trims the base URL's
trailing slash and appends the path, percent-encoding each path segment.

```
/notable/
├── folders.json
├── deletions/
│   └── {notebook-uuid}          # zero bytes; server lastModified is the deletion time
└── notebooks/
    └── {notebook-uuid}/
        ├── manifest.json
        ├── manifest.json.tmp    # transient publish path; may remain after an interruption
        ├── pages/{page-uuid}.json
        ├── images/{basename}
        └── backgrounds/{basename}
```

### 4.2 manifest.json

```json
{
  "version": 1,
  "notebookId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Example",
  "pageIds": ["89a61d25-f4ea-4a68-b52b-8b85504a16d4"],
  "parentFolderId": null,
  "defaultBackground": "blank",
  "defaultBackgroundType": "native",
  "linkedExternalUri": null,
  "defaultPageWidth": 1400,
  "defaultPageHeight": 1980,
  "createdAt": "2026-07-27T10:00:00Z",
  "updatedAt": "2026-07-27T10:05:00.123Z",
  "serverTimestamp": "2026-07-27T10:05:01.456Z"
}
```

- `pageIds` is an ordered list, defining page order within the notebook.
- `serverTimestamp` is actually the uploading device's `Instant.now()` at serialization time. The
  sync decision does **not** use it — comparisons are always against `updatedAt`.
- `openPageId` (which page the notebook last had open) is **device-local navigation state and is not
  serialized** — it never travels between devices, and a download preserves the local value rather
  than overwriting it. Older manifests that still carry the key are tolerated and ignored
  (`ignoreUnknownKeys = true`).

`defaultPageWidth`/`defaultPageHeight` are the sheet new pages in the notebook are created with, in
**page units** — the space stroke and image geometry is already expressed in, one unit being exactly
0.15 mm (so A4 is 1400 x 1980 and converts to the standard 595 x 842 pt PDF box). Sizes are stored
portrait; orientation is a fit, not a size. Absent, null or non-positive means the notebook was
created before page sizes existed, and its pages fall back to the device's own screen width the way
everything used to. See `PageSizes.kt`, and the normative table in bopa's
`docs/notable-sync-protocol.md` §3.1 — both apps have to agree on it exactly.

### 4.3 Page JSON (`pages/{uuid}.json`)

Page JSON schema version is `1`. It contains `id`, a nullable `notebookId` (`null` for standalone
Quick Pages, which are never synced), background fields, nullable `parentFolderId`, scroll position,
the page's own nullable `pageWidth`/`pageHeight` (§4.2 — this is the authoritative geometry for
laying the page out), timestamps, and arrays of strokes and images.

Each stroke carries geometry/style metadata plus `pointsData` — the current stroke binary encoding
returned by `encodeStrokePoints`, Base64-wrapped for JSON transport. The binary encoder currently
writes format version **2** (magic `SB`, version byte `2`) and can use LZ4 internally above a
size/ratio threshold; the decoder still supports the older v1 pressure encoding. It should not be
described as "SB1" without qualifying that decoding is backward-compatible, not the current write
format.

Image URIs are serialized as a relative `parentDirectory/basename` string. Transfer uses the basename
for the remote image path and rewrites downloaded image records to local absolute paths.

### 4.4 folders.json

```json
{
  "version": 1,
  "folders": [
    {
      "id": "folder-uuid",
      "title": "My Folder",
      "parentFolderId": null,
      "createdAt": "2026-06-15T10:30:00Z",
      "updatedAt": "2026-07-20T14:22:33Z"
    }
  ],
  "serverTimestamp": "2026-07-27T08:00:00Z"
}
```

`parentFolderId` references another folder's `id` for nesting, or `null` for root-level folders. The
merge does not use `serverTimestamp` — only each folder's own `updatedAt`.

### 4.5 Tombstone files (`deletions/{uuid}`)

Each deleted notebook has a zero-byte file at `/notable/deletions/{notebook-uuid}`. It has no
content; the server's own `lastModified` timestamp on the file provides the deletion time used for
conflict resolution (section 5.7). Independent per-notebook tombstone files mean two devices can each
write a deletion without racing over a shared file the way a single `deletions.json` would.

### 4.6 JSON configuration

All serializers use `kotlinx.serialization` with `ignoreUnknownKeys = true` for forward compatibility.
Manifest and folder JSON are pretty-printed; streamed page JSON is compact to keep upload memory
bounded.

## 5. Conflict resolution

### 5.1 Last-writer-wins and concurrent reconciliation

There is no CRDT or operational transform. The system handles divergence in two ways:

- **Clear timestamp winner:** upload or download the newer notebook (5.2).
- **Tied timestamps with a changed ETag:** reconcile pages, merging independent edits and flagging
  same-page or structural conflicts (5.3). `ASK` is implemented; `SERVER_WINS` and `LOCAL_WINS`
  remain placeholders for future automatic resolution.

The unit that flags, badges, and resolves is the notebook; the unit that actually transfers is the
page.

### 5.2 Timestamp comparison

When both local and remote versions of a notebook exist:

```
diffMs = local.updatedAt - remote.updatedAt

if diffMs > +1000ms  → local is newer  → upload
if diffMs < -1000ms  → remote is newer → download
if |diffMs| <= 1000ms → within tolerance → the ETag decides (see 5.3)
```

Within tolerance, an unchanged manifest ETag means the sides already agree (skip); a *changed* ETag
means concurrent edits that the tie cannot prove equal, so the planner returns `Reconcile`.

### 5.3 Concurrent edits: independent merge vs. genuine conflict

`NotebookReconciliationService.reconcileConcurrentEdits` classifies the divergence:

- **Independent edits** — the manifests match structurally and no single page was touched on both
  sides. Pull remote changes, then push local-only changes. Skip publication when nothing remains
  locally dirty, preventing manifest ETag ping-pong.
- **Genuine conflict** — either a *page conflict* (the same page edited locally **and** remotely since
  the last common sync) or a *structural conflict* (the manifests disagree on page set, order, title,
  parent folder, or defaults). Mark the notebook `CONFLICT` and leave both copies unchanged.

Conflicts are only *detected* in two-way sync, because that's the only mode that produces a
`Reconcile` (see 5.9).

### 5.4 The `ASK` conflict model

Under `ASK`, the resolution UI reads conflict details through the read-only
`SyncOrchestrator.notebookConflict` call:

- **Page conflicts** are resolved one page at a time via `PageConflictResolution`:
  - `REPLACE_WITH_SERVER` — take the server's copy of that page;
  - `UPLOAD_DB` — keep the local copy and overwrite the server's;
  - `SKIP` — leave the page diverged; it stays flagged and is asked again next sync.
- **Structural conflicts** are resolved whole-notebook via `NotebookConflictResolution`:
  - `TAKE_SERVER` — download the server notebook over the local one;
  - `KEEP_LOCAL` — keep the local structure and overwrite the server's.

The `CONFLICT` badge clears once the last conflict is resolved and its transfer commits.

### 5.5 Applying a resolution

A resolution reuses the normal transfer path in two phases:

1. **Rebaseline.** Rewrite the page or notebook sync-state row so reconciliation sees a normal change:
   - `REPLACE_WITH_SERVER` drops the stored page ETag and lifts the change anchor to the local page's
     own timestamp, causing a download.
   - `UPLOAD_DB` adopts the server's *current* page ETag as the base while leaving local content dirty
     so the locally dirty page uploads against the latest server version.
   - `KEEP_LOCAL` re-anchors the manifest and every page row to the server's current ETags **without**
     advancing the change anchor → the still-newer local copy uploads next, guarded by up-to-date
     ETags. The newer local notebook then uploads instead of receiving a 412. `TAKE_SERVER` downloads
     immediately and needs no second phase.
2. **Transfer.** `SyncOrchestrator.runResolutionTransfer` runs normal whole-notebook reconciliation.

Both phases hold the sync mutex. If transfer fails, the rebaselined state remains pending for the
next sync. Resolving one page therefore re-plans the whole notebook without duplicating transfer and
commit logic.

### 5.6 Conflicts and one-directional sync

Resolution may need either upload or download, so `SyncOrchestrator.resolutionPreflight` rejects it in
a one-directional mode with `SyncDirectionalConflict`. The `CONFLICT` badge remains until the user
returns to two-way sync. Directional sync does not create new conflicts because it never executes a
live `Reconcile` action.

### 5.7 Deletion vs. edit conflicts (resurrection)

Notebook deletions use zero-byte tombstone files rather than a shared deletions list. When applying a
remote tombstone: if the local notebook's `updatedAt` is **after** the tombstone's `lastModified`,
the notebook is kept and re-uploaded ("resurrected") instead of deleted, and the tombstone is removed
from the server on that notebook's next successful upload. Otherwise the local notebook is deleted
and its `notebook_sync_state` row is dropped. This favors not losing a post-deletion edit over a
deletion always sticking.

### 5.8 Folder merge

Folders use a simpler per-folder last-writer-wins merge: all remote folders load into a map, and a
local folder replaces its remote counterpart only when its `updatedAt` is later. There is no folder
tombstone, so an absent folder is indistinguishable from a new remote folder and can reappear after
being deleted on one device and synced from another — notebooks inside it are not deleted merely
because the folder reappears.

### 5.9 Reconciliation decision (`NotebookSyncPlanner`)

`NotebookSyncPlanner.decide(...)` is a pure, unit-tested function that takes the local `updatedAt`,
the stored sync-state anchor, and the remote manifest facts, and returns one of `Upload`, `Download`,
`Skip`, `Reconcile`, `SkipUploadOnly`, or `SkipDownloadOnly`:

- unchanged remote + local timestamp more than 1 second past the stored anchor → `Upload`;
- changed remote, one side clearly newer → `Upload` / `Download` per the 1-second rule in section 5.2;
- changed remote, timestamps tied within tolerance → `Reconcile` (a tie is not proof of page equality,
  so the executor merges per page rather than mark it synced over possibly-stale pages — see 5.3);
- upload-only suppresses downloads and records `REMOTE_AHEAD` instead of a misleading `SYNCED`, and
  degrades a `Reconcile` to that same skip (it can't pull the remote half);
- download-only suppresses uploads (keeps the `NOT_SYNCED` badge, since local changes genuinely aren't
  on the server) and degrades a `Reconcile` to its download half.

Because a one-directional mode never yields a live `Reconcile`, conflicts are only flagged in two-way
sync. `NotebookReconciliationService` does the I/O and executes the decision. Upload-only and
download-only are mutually exclusive in the settings UI.

### 5.10 Per-notebook and per-page sync state

Two Room tables (keyed by id, **no** foreign key to `Notebook`/`Page` — the rows must outlive local
deletion) are the source of truth for sync bookkeeping.

`notebook_sync_state` — per-notebook commit marker and badge state:

| Column | Meaning |
|---|---|
| `notebookId` | Primary key. |
| `state` | `SYNCED`, `ERROR`, `REMOTE_AHEAD`, or `CONFLICT`. |
| `lastSyncedAt` | Device time at the latest state write, including errors/remote-ahead/conflict. |
| `syncedLocalUpdatedAt` | Local notebook timestamp captured at the last committed sync — the dirty anchor. (Column is still named `localUpdatedAtAtSync` on disk.) |
| `remoteEtag` | Last known manifest ETag, sent as `If-None-Match` on the next reconcile. |
| `remoteUpdatedAt` | Last known remote manifest timestamp. |
| `lastError` | Last transfer error message, if any. |

`page_sync_state` — per-page commit marker driving dirty-page selection (5.9) and page-conflict
detection (5.3):

| Column | Meaning |
|---|---|
| `pageId` | Primary key. |
| `notebookId` | Indexed; groups a notebook's page rows. |
| `remoteEtag` | The page file's server ETag at the last committed sync, in the server's own spelling — parsed and compared through `ETag`, never `==`. |
| `syncedLocalUpdatedAt` | The change anchor: `Page.updatedAt` at that commit. A strict `>` against the current value answers "edited since?" (same clock on both sides, so no tolerance needed). |
| `lastSyncedAt` | Wall-clock of the last committed sync; informational, never compared. |

Both tables are written **only inside** the notebook's commit transaction (after the manifest is
published on upload / the atomic page swap on download). A killed sync writes no rows, so the next
sync re-transfers the same pages — "skip" is exactly as trustworthy as the notebook badge.

`NotebookSyncStatusStore` derives a badge for every notebook: `ERROR` if the row is in error;
`CONFLICT` if it's flagged; `NOT_SYNCED` if there is no row or local time is more than one second past
the anchor; `REMOTE_AHEAD` for a still-unedited upload-only skip; otherwise `SYNCED`; during a full
sync, the current item becomes `SYNCING` and other non-synced items become `SCHEDULED`. The store
never checks remote state itself — outside an active sync, badges describe local state relative to the
last recorded commit, not guaranteed present-day server equality. The first sync after an upgrade that
introduced these tables simply repopulates them; there is no migration path from a predecessor.

### 5.11 Media handling

Media (images and page backgrounds) is transferred alongside pages, but with rules that keep one
missing or unsyncable file from wedging a whole notebook:

- a media 404 is non-fatal: it's logged and dropped rather than accumulated, so the notebook still
  commits and stops being retried for that file; other media failures still block the commit;
- a locally referenced media file that can't be found is logged but does not stop the upload of the
  rest of the notebook;
- linked external PDFs are intentionally not copied — only managed backgrounds transfer.

A notebook that committed with a media 404 is **not** automatically re-fetched if that media later
appears on the server; a force download or a local edit that re-triggers an upload/download is the
current recovery path.

## 6. Security

### 6.1 Credential storage

Credentials are persisted via the app's key-value Room table (`kv`, through `KvProxy`). The password
is encrypted using an AES-GCM key stored in AndroidKeyStore; on read, a decryption failure returns
settings with a blank password rather than throwing.

### 6.2 Transport security

HTTPS is recommended but not enforced — HTTP base URLs are accepted, since some users run WebDAV on a
trusted local network. TLS uses OkHttp/system certificate validation; there is no custom CA or
certificate-pinning UI.

### 6.3 Logging

`SyncLogger` keeps a memory-only ring buffer of the last 50 entries, exposed as a `StateFlow` for the
UI. It omits credentials and is not a durable audit log.

### 6.4 Server-side data

Notebook data on the WebDAV server is **not** end-to-end encrypted by Notable. Server-side encryption,
retention, backups, and account security depend entirely on the provider.

## 7. Error handling and recovery

### 7.1 Error types

HTTP calls return `AppResult<…, DomainError>`. The variants relevant to sync:

```kotlin
DomainError.NetworkError(message)           // thrown request/URL exception
DomainError.SyncAuthError                    // credentials missing or rejected
DomainError.SyncConfigError                  // sync disabled / not configured
DomainError.SyncClockSkew(seconds)           // device clock differs from server by >30s
DomainError.SyncWifiRequired                 // wifiOnly set but not on an unmetered network
DomainError.SyncInProgress                   // another sync already holds the mutex
DomainError.SyncConflict                     // PUT/MOVE precondition failure (HTTP 412)
DomainError.SyncError(message, recoverable)  // generic non-success HTTP status
DomainError.RemoteMissing(path)              // GET 404; non-recoverable, non-fatal for media
```

Multiple per-notebook errors are aggregated into `MultipleErrors`, which currently inherits
`recoverable = true` regardless of its children — so an aggregate of otherwise-permanent errors can
still cause a retry of the entire full sync.

### 7.2 Concurrency control

The orchestrator's mutex covers full sync, single-notebook sync, and force operations. It does not
cover check-on-open or targeted notebook deletion (section 3.4). There is no cross-device locking —
WebDAV provides no atomic multi-file transaction, which is why the ordering guarantees in
[section 1](#1-architecture-overview) matter.

### 7.3 Retry strategy (`SyncWorker`)

- Network unavailable (pre-check): retried immediately through WorkManager.
- `NetworkError` during sync: retried up to 3 worker attempts, then failed.
- Auth/config/clock/Wi-Fi/conflict errors: never retried, fail immediately.
- Other errors: retried only when `recoverable`, else failed immediately.
- Disabled sync, missing credentials, or an unmet unmetered constraint: treated as a successful skip.
- `SyncInProgress`: treated as completed work with a non-success informational payload, not a hard
  failure — a manual Sync Now can legitimately collide with a running periodic sync.
- The worker also has a broad `catch (Exception)`, which includes cancellation exceptions.

### 7.4 WebDAV idempotency

`MKCOL` returning 405 is treated as success — per RFC 4918 that means the collection already exists
(only accepted on `MKCOL`; a 405 on any other operation is an error). `DELETE` returning 404 is
treated as success — the resource is already gone. Both are therefore safe to retry.

## 8. Integrations

| Dependency | Purpose |
|---|---|
| `com.squareup.okhttp3:okhttp` | HTTP client for all WebDAV operations (shared `OkHttpClient` instance). |
| AndroidKeyStore (via `CryptoHelper`) | AES-GCM keys for encrypting sync passwords at rest. |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | JSON serialization/deserialization for manifests, folders, and pages. |
| `androidx.work:work-runtime-ktx` | Background sync scheduling (one-time and periodic). |

## 9. Limitations and test coverage

- Same-page conflicts require choosing one whole page; there is no stroke-level merge.
- `SERVER_WINS` and `LOCAL_WINS` remain persisted placeholders. The engine always uses `ASK`.
- Conflict resolution requires two-way sync, and folder deletions do not propagate.
- Weak ETags cannot guard writes. When a server supplies them, `ETag.writeGuard` drops the
  precondition and conflict handling degrades to last-writer-wins.
- Conflict resolution relies on synchronized device clocks; normal preflight rejects skew over 30
  seconds.
- `WebDavClientFactoryPort` abstracts client construction only. Transfer services still accept the
  concrete `WebDAVClient`, and all of them reach through the broad `AppRepository` — the main
  obstacle to executor-level tests today.
- Unit tests cover serializers, the pure planner, request/path encoding, progress reporting, logger
  behavior, factory construction, HTTP-date parsing, and `finalizeSyncResult`.
- There are no executor-level tests for `NotebookSyncService`, `NotebookReconciliationService`,
  `FolderSyncService`, `SyncForceService`, scheduler cancellation, trigger integration, or remote JSON
  identity/path validation. The seam these need is a narrow transfer port in place of the concrete
  `WebDAVClient` and the broad `AppRepository`.
