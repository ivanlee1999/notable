# Changelog

## 0.15.0

Notable runs on the Palma Pro 2. And the notebooks you deleted before 0.13.0, which are still
sitting in your iPad's library, are finally sent on their way.

### The pen could move without anything being written down

- **On a BOOX without the raw-pen channel, no stroke was ever recorded.** Notable draws through
  ONYX's firmware pen path, and being a BOOX was taken as proof that path was available. It is
  not — the Palma takes a capacitive stylus rather than the digitizer built into the Tab series,
  and the channel can be absent or fail to open. Nothing reported this: the editor opened, the
  page was there, the pen moved, and every stroke was dropped.
- Notable now checks whether the channel actually opened, and falls back to reading the pen
  through Android when it did not. Ink, shapes, selection and erase all work on that path,
  because it feeds the same place the firmware does.
- **On the fallback, a stroke appears when you lift the pen** rather than under the tip as you
  write. The under-the-tip ink is drawn by the firmware itself, and nothing outside it can match
  that on e-ink. Where the firmware is available — every Tab, Note and Air — nothing changes.

### One hand, on a screen the size of a phone

- **The tool rail starts at the bottom** on a pocket-sized device, where a thumb already is,
  instead of the top. Only on a new install: if you have already put the rail somewhere, it stays
  there.
- **The pen's colour is now visible on a rail docked top or bottom.** It never was — the strip of
  inks only fitted down the side of a vertical rail, so on a horizontal one the colour in hand was
  simply not on screen. A single swatch now shows it, and tapping opens the rest over the page.
- The library already dropped to one column with the shelf as a list on a screen this size.

### Deletions from before 0.13.0 were stranded on this device

- **Until 0.13.0, throwing a notebook away here only hid it here.** The Trash was this device's own
  bookkeeping and was never sent anywhere, so the notebook left your library on the BOOX and stayed
  in the iPad's. 0.13.0 fixed that for anything deleted from then on.
- **It did not fix the ones already thrown away, and nothing was ever going to.** The iPad had
  already been told about those notebooks once, and the safety net that catches unsent work only
  looks for things the other device has never seen at all. A notebook in the Trash is also hidden
  from your library, so there was no way to open it and change something to nudge it along. It
  would have sat there, deleted on one device and listed on the other, indefinitely — the only way
  out was to restore it and delete it a second time.
- Updating now sends those deletions, once. Anything sitting in your Trash leaves the iPad's
  library shortly after you open the app, and can still be restored from either device.

A note on what this can get wrong: these deletions are sent as though you made them just now,
because that is what it takes for them to take effect on a device that has been treating the
notebook as live ever since. If you have been writing in one of these notebooks on the iPad —
precisely because it never disappeared — that writing is not lost, but the notebook does go to the
Trash. Restore it from either device and it comes back everywhere, ink and all.

Database version 46. It changes no tables, only which notebooks are marked as needing to be sent.

## 0.14.0

Sync could skip a change from the other device and never mention it again. Nothing here is a
feature; it is the set of ways sync could go wrong quietly, and what each one cost you.

### A change from the other device could be skipped for good

- **A truncated or unfamiliar answer from the server was accepted as if it were complete.** Sync
  reads a list of what changed and then records how far it has read. If anything went missing from
  that list on the way — a proxy that cut the response short, a server that phrased something in a
  way this app did not expect — the app recorded the position anyway and moved on. The server only
  ever offers a change once, so whatever was in the gap was never offered again: a page written on
  the iPad that simply never appeared here, with nothing reporting a problem.
- A response that does not make sense is now refused whole and asked for again, which costs one
  retry rather than the change.
- **Two syncs running at once could put an older answer on top of a newer one.** A catch-up while
  the app was waiting for news left it holding a description of a moment that had passed. The stale
  half is now discarded rather than half-applied, which stops the needless re-uploads and false
  clashes it caused.

### Coming back after a long time away could take the app down

- **The first sync after a long gap asked for everything that had changed at once**, in a single
  response, with every page's ink inside it. After a week off, or behind a proxy that had been
  holding things back, that is a large amount of data to hold in memory at once on this device —
  the difference between slow and dead. It now arrives in pieces and is written down as it goes, so
  an interrupted catch-up resumes where it stopped.

### Sync that kept trying, or stopped trying, for the wrong reasons

- **A note the server had refused on its merits was retried forever** — a page too large for the
  server to accept, a request it would never take — on the same schedule as a lost connection, and
  the one message that would have told you what to fix scrolled past between attempts. Those now
  stop and say what happened, and a page the server considers too large says exactly that.
- One document the server will not take no longer holds up everything queued behind it.
- **A server asking for a pause is now obeyed** rather than argued with, and repeated attempts to
  send are spaced out properly instead of resetting to full speed every time a read succeeded.

### Pictures that had not arrived looked like everything was fine

- **An image that failed to download was silently forgotten.** The page and its ink arrived,
  sync reported itself finished, and the picture was simply absent — with nothing to say whether it
  was still coming, missing from the server, or damaged.
- Those are now told apart and reported beside the status: *"2 images still downloading. Notes and
  ink are synced."* It stays a note rather than a failure, because the writing really is synced, and
  it clears itself when the images land.

### Work done just before closing the app

- **The last strokes before you left the app were the most likely to be left behind.** The final
  send was started and then abandoned as soon as Android stopped the app, and nothing else was
  scheduled to finish it — the next attempt could be a quarter of an hour later.
- Leaving the app now queues that work durably, so it survives the app being closed and is finished
  in the background.

### The server being rebuilt is now noticed

- **A database recreated under the same name looked identical to the original one.** The app would
  carry on from a position describing history that server never had, and treat genuine changes as
  its own echoes.
- The database now carries an identity, so the two can be told apart. This release only records and
  reports it; the iPad app has to understand it too before either can act on it.

No database change.

## 0.13.0

Deleting a notebook here left it sitting in the library on your iPad. The Trash now travels, so
"delete" means the same thing on both devices.

### The Trash was private to each device

- **Moving a notebook or folder to the Trash published nothing.** It disappeared from the library
  here and stayed exactly where it was on the other device, and the two only agreed again when
  somebody emptied the Trash. Deleting therefore meant "hide it from me" rather than "delete it".
- Trashing now takes the item out of the library **everywhere**, and restoring it from either device
  brings it back on both. Nothing is destroyed until you empty the Trash, which is still the only
  irreversible step and still the only one that deletes anything.
- A trashed notebook goes on syncing, ink and all. Someone drawing in it on the other device loses
  nothing, and an edit written *after* the trashing takes the notebook back out of the Trash — the
  same rule that has always protected work done after a deletion.
- **A trashed notebook could climb back out of the Trash on its own.** Anything arriving from the
  other device — a rename, a stroke — rebuilt the notebook's row without its Trash mark, so the next
  sync quietly put it back in the library.

No database change: the Trash has been recorded here since 0.11.0. It just never went anywhere.

## 0.12.0

The BOOX and the iPad were quietly re-uploading each other's work. Nothing was ever lost to it, but
every exchange cost a round trip that achieved nothing, and a page's title or background could settle
the wrong way.

### Each device claimed to have written the other's notes

- **Every page, notebook and folder the BOOX read back was stamped with the BOOX's own name**,
  whoever had actually written it. Nothing recorded the real author, so sync had to assume one, and
  it always assumed "me". The iPad's copy therefore looked like a change worth sending back, the
  BOOX's copy looked the same way to the iPad, and both devices wrote a fresh revision of
  byte-identical content. A single page could reach twelve revisions where five would have done.
- The author now travels with the note and is stored beside it, so a document that arrived from the
  other device is recognised as theirs and left alone.
- The same guess also decided **which title, background or page size won** when two edits shared a
  timestamp — the tie was broken on a name that was always this device's. Those now settle on
  whichever device really made the edit.
- A device that loses its place in the sync history replays it from the beginning. That replay used
  to re-upload everything it held; it now sends nothing.

Your drawings were never at risk from this: ink merges stroke by stroke and was always kept in full.
Schema 44 -> 45, in one automatic step.

## 0.11.0

Deleting a folder no longer takes everything inside it away for good, and an edit made on the BOOX
now reaches the server whatever kind of edit it was. Both were ways of losing work quietly, which is
what most of this release is about.

### Deleting a folder was one tap away from losing everything in it

- **A folder deletion was unconfirmed, immediate and permanent.** Every descendant folder, notebook
  and page went with it, only the folder itself was recorded as deleted, and there was no way back.
  The subtree was gone here while the server still held the notebooks, so the next sync either
  brought them back under a folder that no longer existed or left the two devices disagreeing
  forever.
- Deleting a folder or a notebook now moves it to a **Trash**, which appears in the Library as soon
  as anything is in it. Nothing is published while an item sits there: the other devices keep their
  copy, and restoring is one tap. Emptying the Trash is what actually deletes, everywhere, and it
  says so before it does it.
- The folder dialog now asks first and counts what is inside. Restoring an item whose folder was
  trashed too puts it somewhere you can actually see it, rather than back into the dark.
- **Deleting the last page of a notebook offered to delete the notebook, then deleted only the local
  copy.** The server was never told, so the notebook came back on the next sync and offered itself
  for deletion all over again.

### Edits that never left the device

- **Reordering pages, renaming a page, or changing only a background could sit on the BOOX
  indefinitely.** Six mutations wrote to the database without recording that the document had
  changed, and sync's "never sent" scan cannot see a new edit to a document the server already
  holds — so those edits were invisible to it.
- There is now a durable outbox, written in the same transaction as the change itself. An edit
  survives a restart the way a deletion always has, and the app can no longer be killed in the gap
  between saving your work and remembering to send it.
- Anything queued while offline now goes out as soon as a sync succeeds, instead of waiting for you
  to write something else or tap Sync now. A flush that stops early reports everything still
  waiting, rather than only the one document it tried.
- "Upload everything" queues the library in one write instead of one per notebook — a moment rather
  than a stall.

### A deleted notebook could reappear as an untitled copy of itself

- **A notebook you deleted came back as an empty "New notebook" holding its old pages** — and then
  republished itself to the server, undoing the deletion for every device. Pages carry no deletion
  record of their own, so a device replaying sync history from the beginning met the leftover pages
  and invented a notebook to hang them on.
- The record of a deletion is now kept after the server has been told, rather than dropped at that
  moment, so the leftovers are recognized as leftovers whichever order they arrive in. A device that
  only heard about a deletion, rather than making it, now has that memory too.

### The mass-deletion warning had no answer

- **Sync holds a large batch of notebook deletions back**, because a wiped database looks exactly
  like a deliberate clear-out, and the warning told you to confirm in a setting that did not exist.
  The only way through was to make the same deletions on your other device.
- Sync settings now offers both answers with the consequences spelled out: delete them on the server
  too, behind a confirmation, or keep them there — which stops this device claiming the deletion and
  brings the notebooks back here on the next sync. Keeping them replays the change history once,
  which is slower than usual exactly once.
- An approval is spent when the deletions it covers are sent, so one tap cannot wave through the
  batch that follows an accident.

### Ink that looked saved and wasn't

- **A failed write was logged and forgotten.** The stroke stayed on screen for the rest of the
  session and was gone after a restart, with nothing to say the page was unsafe. Writes now retry
  with backoff for up to a minute, so a disk that recovers is picked up on its own, and the editor
  says "Not saved — retrying" while it holds. Process death can still lose unsaved ink; what it can
  no longer do is lose it silently.
- **Fast page switching could leave the canvas showing one page while the toolbar named another**,
  and the next stroke was written onto whichever one the loader finished with. Switches now take
  turns, and a switch you have already moved past costs nothing.
- Undo held five operations. A lasso move, a resize and a colour change spend three without a word
  being written, so recovering from a mistaken clear was routinely impossible. It is now budgeted by
  memory instead — deep for ordinary strokes, still safe after a select-all move.
- Deleting a page from the editor's page menu now asks, the way the page overview always did.

### Finding a notebook in a library of eighty

- **The Library had no search and no order but database insertion, backwards.** You can now search
  the whole library by name — not just the folder you are standing in, since not knowing where
  something is is the reason to search — and sort by last edited, date created or title, in either
  direction. The choice is remembered.
- A notebook's **Copy** button said "Not implemented!". It now makes a real copy, with new
  identities throughout so sync does not confuse it with the original.
- **Folders could not be moved at all**, so a tree built in the wrong shape had to be rebuilt by
  hand. A folder now moves, and refuses to move inside itself.

### Rectangles, ellipses and arrows

- The shape tool drew straight lines and nothing else. It now draws a rectangle, an ellipse and an
  arrow as well, each as ordinary ink carrying the pressure and tilt of the drag — so a shape erases,
  moves, exports and syncs like anything else you draw, on the BOOX and on the iPad alike.
- The shape button shows the shape it will draw, and tapping it while selected opens the picker, the
  way the eraser already worked.

### Smaller things

- **PDF page breaks are now drawn by default.** Export has always split the canvas at sheet
  boundaries; the editor simply never showed where. The lines only appear when pagination is
  actually on.
- **An image placed below or to the right of the sheet could not be scrolled to.** The scrollable
  area was measured from strokes alone, so an image moved out there was stored, drawn and
  unreachable. One rule now measures the page's whole contents.
- Sync now notices when this device's clock disagrees with the server's by two minutes or more, and
  says which way it is wrong. Every merge decision runs on those timestamps, so a fast clock lets
  stale work win. It is a warning beside the sync status, not a refusal to sync.
- A server address Android blocks outright now stops the whole upload with one message telling you
  to fix the URL, instead of failing once per queued document with the same sentence.
- Saving a stroke read the entire page row to pick one column out of it — a cost paid per stroke on
  a BOOX, not per session.

### Upgrading

The database moves from 41 to 44, to hold the outbox, the Trash and the fuller record of a deletion.
That happens by itself when you open the app; nothing is rewritten and nothing moves. Everything
already on the device reads as "not in the Trash", and any deletion you had made but not yet synced
is still waiting to go out.

## 0.10.0

A page pulled in from another device now shows up without leaving the editor. Paper has edges you
can't pinch or pan past, and a new notebook asks what kind of paper it wants before it exists.

### A page that changed elsewhere stayed stale until you left it

- **Ink synced in from another device landed in the database but not on screen.** The Library
  thumbnail picked it up right away, since it reads straight from Room; the editor did not, because
  it caches a page's strokes once and never re-reads them. A pull that landed while you were sitting
  in the Library, or the manual Refresh command, changed nothing you could actually see.
- The cache now hears which pages a sync rewrote: the page you have open repaints in place, and a
  page you're not drawing just reads fresh the next time you open it. Ink drawn while a pull is still
  landing is kept rather than clobbered by it.
- WebDAV downloads now report the pages they replace the same way CouchDB sync always did.

### A self-hosted CouchDB server can be reached over plain HTTP again

- **Syncing to a server on your own network could fail before a socket even opened**, and the app
  reported it as "Offline — changes are saved and will sync when you reconnect" — wrong on every
  count, since Android blocks cleartext HTTP by default and nothing was ever sent. Cleartext is now
  permitted for a named self-hosted server, and a connection actually blocked by policy says so
  instead of pretending to be an outage.
- The one LAN address the app happened to allow before belonged to nobody's real server. A local
  CouchDB now wants either an `https://` endpoint or its exact host added on purpose.

### The edge of the paper is now an edge

- **On a tablet wider than its pages, pinching out or panning right used to carry you past the
  sheet** into blank space beside it — and the pen wrote there just as happily, onto ink no other
  device could ever show. Zooming out now stops at the page's fit, and panning stops at the sheet's
  right edge, so whatever the pen is over is always actually on the paper.
- Opening a page, rotating the tablet, and "reset view" now all return to that fit rather than to
  1:1, which was an arbitrary zoom on a sheet with a real size. The toolbar's reset button only shows
  up once you've left the fit.
- Pages without a declared size are unchanged — they still pan out to the edge of their ink rather
  than to a bound.

### Creating a notebook now asks what its paper should be

- **A notebook's paper size is fixed the moment it exists, but choosing one meant leaving the create
  dialog** for Settings, changing the default there, and coming back. The create prompt now asks for
  page size and template up front, each starting on your usual default, so accepting the whole dialog
  is still one tap.

### Smaller things

- "Sync now" lives in the Library header now, where you're actually looking when you wonder about
  it, instead of buried in Settings or a single notebook's dialog.
- A large sync no longer rebuilds the Library's whole state on every page it touches — only whether
  something is syncing at all reaches the screen.
- Letter and Legal now report one rounded millimetre size everywhere. The settings picker and the
  new-notebook dialog used to truncate Letter to 215 mm while a label computed the same sheet a
  different way and rounded it to 216.

### Upgrading

No database change — the schema stays where 0.9.0 left it.

## 0.9.0

A page now has a size of its own. Until now it was as wide as the screen it was drawn on, which
meant a page was a different page on every device.

### Writing near the right edge went missing on the iPad

- **Ink written down the right-hand side of the tablet was not there on the iPad.** Not cut off —
  unreachable: there was nothing to scroll to. A page was as wide as the screen that drew it, and
  the iPad's page was narrower, so anything past its edge fell outside the page altogether. Both
  apps now read the page's own width from the page, and each one scales its screen to fit.
- **Pages you already have are left exactly as they are.** Their ink was drawn against the screen,
  and giving them a paper size now would move every stroke relative to the paper. They keep the
  geometry they were written with, and both apps still let you scroll to ink that sits outside the
  page — which is what makes that right-hand-side writing visible again.

### Choosing paper

- **New notebooks and quick pages take a paper size**, set in Settings › Default Page Size: A4, A5,
  A3, Letter or Legal. The size is fixed when the notebook is created, because ink is positioned
  against the paper from the first stroke — changing it later would slide everything on every page.
- Sizes are held in portrait. Turning the tablet changes how much of the page you can see, not how
  big the page is.

### The page behaves like a page

- **A page opens with the whole width of the paper on screen.** Pinching snaps either to that fit or
  to actual size, rather than to the shape of the screen; turning the tablet re-fits it.
- **Panning stops at the edge of your work** instead of drifting on into blank space.
- **The magenta line marks the real edge of the paper**, so it is plain when writing has left the
  page. It used to mark where the screen's edge would fall if you turned the tablet upright.

### Exports are the size they claim to be

- **A PDF or Xournal++ export of an A4 page is A4.** The old export stretched the screen's width onto
  A4 paper, which was only ever right on a tablet that happened to be A4-shaped.
- **Importing a Xournal++ file keeps the paper size the file declares** rather than fitting it to
  this tablet.

### Upgrading

The database moves from 40 to 41, to hold the paper size on notebooks and pages. That happens by
itself when you open the app. Nothing is rewritten and nothing moves: every page already on the
device is recorded as having no declared size, which is what keeps it looking the way it does.

## 0.8.1

A point release about the pictures on notebook covers. Nothing to do with sync, and the schema
stays at 40.

### A notebook you had just made never showed what was in it

- **The cover of a new notebook stayed blank however much you drew in it.** A notebook appears in
  the Library the moment it is created, holding one empty page — and that is when its cover picture
  was taken. Nothing was allowed to replace it afterwards, because a cover only asked for a new
  picture when it had none at all, and it had one: of an empty page. Covers now ask every time the
  Library is shown, and any picture older than the last edit to the page it stands for is retaken.
- **Older notebooks had only been lucky** about when their picture happened to be taken. A cover
  showing a page as it looked several edits ago puts itself right the next time you open the
  Library.

### The picture taken as you close a note was thrown away

- **notable copies the page as you leave the editor**, to put on the cover and to show while
  flipping pages, and that copy was discarded as the editor closed — every time, for every page.
  Your drawing was never at risk; only the picture of it. That copy is now kept, as is a second one
  taken while you draw, which had never been kept either.

### Smaller things

- Opening the Library no longer announces "Generating previews" when it has nothing to generate.
- Keeping those pictures up to date no longer holds a processor core busy a second at a time while
  you draw.

### Upgrading

No database change: the schema stays at 40. Covers correct themselves as the Library loads — there
is nothing to do by hand.

## 0.8.0

CouchDB only. If you sync over WebDAV, nothing here changes for you.

### Deleting something no longer undoes itself

- **A page you deleted came back.** notable removed the page and then said nothing about it, so the
  other device — which still had it — offered it back on the next sync, and notable took it. A
  removal is now recorded as a fact of its own, so a page deleted here stays deleted everywhere.
- **An image you erased came back the same way**, for the same reason, and is now recorded too.
- **Deleting the same notebook on both devices jammed that notebook's sync.** The server refuses a
  second deletion of something already deleted, and notable read that refusal as "try again" — so
  the notebook sat in the queue and every later sync replayed the same doomed request.

### Ink drawn while a sync was running could disappear

- **Strokes drawn during a sync could be deleted.** Combining your page with the server's copy takes
  a moment, and anything drawn in that moment was not part of what was being combined — so it was
  treated as something you had removed: erased here, and never sent anywhere either. It is exactly
  the case syncing two devices exists for, drawing on one while the other has the same page open.

### Deletions that carried no date no longer win

- **A deletion recorded without a time deleted work done after it.** notable filled the gap with the
  current time, which is newer than any edit anyone has ever made, so the deletion always won and
  the rule that a later edit brings a notebook back could never apply. An unknown time now loses
  that comparison instead of winning it.

### Changing servers

- **Pointing notable at a different server — or a different database on the same one — reused the
  old one's place in the change history.** That does not announce itself: notable simply skips
  changes it believes it has already seen. Each server is remembered separately now.

### Catching up on a large library

- **A first sync asked for the whole library in one answer** and held all of it in memory before
  saving any of it. It now arrives in batches, each saved as it lands, so a catch-up that is
  interrupted carries on from where it stopped rather than starting again.

### Smaller things

- A large batch of deletions no longer holds up everything else while it waits to be confirmed —
  only the deletions wait, and your drawings keep syncing. (The confirmation itself is still not
  built, so those deletions stay queued.)
- That same safeguard had been quietly losing its ability to trigger as a library aged. It now
  measures against what is on the device rather than against everything it has ever seen.
- A document notable cannot read now produces one "Unreadable sync copy" notebook rather than
  another one each time it re-reads the change history, and an unreadable *folder* is copied too
  instead of being dropped without a word.

### Upgrading

The database moves from 39 to 40, to make room for recording deletions. That happens by itself when
you open the app.

Two things to expect once, on the first sync after upgrading:

- It will read the whole change history again, because sync state is now kept per server. Slower
  than usual, exactly once.
- **Pages and images you deleted before upgrading may come back.** Those removals were never
  recorded, so if your other device still has them, this is the last sync that can bring them back.
  Delete them again afterwards and they will stay gone.

## 0.7.3

CouchDB only. If you sync over WebDAV, nothing here changes for you.

### Sync now works while the app is open

- **Tapping Sync now did nothing until you restarted the app.** 0.7.2 stopped notable's request for
  server changes hanging forever, but it still waited on that request — up to a minute of a request
  whose whole job is to sit there until something happens — before it would do anything else. A
  manual sync asked its first question straight into that wait, which is why the log stopped at the
  heading and never went further. Waiting for the server no longer holds everything else up, so a
  sync you ask for starts when you ask for it.
- **The same wait no longer delays sending your edits.** Anything you drew or changed while that
  request was open had to queue behind it too.

### Notebooks no longer claim to be "local only" when they are not

- **A notebook already on your other device still showed the local-only badge.** The badge was read
  from a record only the WebDAV engine keeps, so with CouchDB selected it had nothing to go on and
  said "not synced" about everything. It now reads CouchDB's own record: synced once the server has
  accepted it, and not synced while there are changes still to send.

### Upgrading

No database change: the schema stays at 39.

Badges correct themselves as soon as the library loads — there is nothing to do by hand, and no
re-sync is needed.

## 0.7.2

CouchDB only. If you sync over WebDAV, nothing here changes for you.

### Sync stopped working a minute after opening the app

- **Nothing was sent to the server once the app had been open for a moment**, and quitting and
  reopening made it work again for another minute. notable asks the server to tell it about changes
  as they happen, and it was asking in a way that let the server hold that request open forever
  instead of answering within the minute it had asked for. Because everything else waits its turn
  behind that one request, a server with nothing new to report left this device unable to send
  anything at all — while a fresh start had not yet made the request, which is why restarting
  helped. It now returns when it should.
- **The sync status no longer sits on "Syncing…" indefinitely** for the same reason.

### Upgrading

No database change: the schema stays at 39.

Anything that was waiting to be sent goes out on the next sync — there is nothing to do by hand.

Two things this release does **not** fix, so you know what you are still looking at:

- Notebooks are still labelled **"local only"** in the library even when they are on the server and
  on your other device. That label is read from a record only the WebDAV engine keeps, so on
  CouchDB it never says anything else. The label is wrong, not the sync.
- After tapping **Sync now**, it can still take up to a minute to start, because it waits for the
  request above to come back before doing anything. It no longer waits forever.

## 0.7.1

All of this is the **CouchDB** backend. If you sync over WebDAV, nothing here changes for you.

### A notebook you made was never sent

- **Creating a notebook did not queue it for sync.** CouchDB is told about a change when something
  explicitly reports one, and the only thing that reported anything was drawing. A notebook
  travelled to the server as a side effect of ink being put on one of its pages — so a notebook you
  created and did not draw in was never sent, however many times sync ran. Its date kept updating
  on this device, which made it look like it had been picked up.
- **The same was true of renaming a notebook or folder, creating a folder, moving a notebook,
  changing its template, and importing.** Each of these now queues the change as it is made.
- **Every sync also asks the server what it has never seen** and sends it. This is the safety net
  under the above: if anything is ever missed again, the next sync catches it instead of losing it
  for good.

### You can now see what CouchDB sync is doing

- **The activity log was not shown at all on CouchDB.** It lived with the WebDAV settings, and it
  was hidden until sync was fully configured and switched on — which is backwards for the panel
  whose job is to tell you that sync is *not* configured. It now appears for whichever backend you
  have chosen.
- **The engine barely wrote anything down.** Syncs now report what they sent and received, and
  every document that failed is listed rather than only the first one.
- **The reasons nothing can sync now say so** — no backend chosen, no server address, an address
  that cannot be read. A mistyped URL in particular left **Sync now** looking like a working button
  that did nothing at all and said nothing.
- **"Upload everything on this BOOX" said it was queueing your notes when it was not**, if the
  server settings were not usable.

### Fixes

- **A mistyped server address could have been written into the log with its password.** A CouchDB
  address may contain a username and password, and the log is kept on disk, sent with crash
  reports, and copied by the Copy Log button. The address is now shown with the credentials
  replaced before it is written anywhere.

### Correcting the 0.7.0 notes

The 0.7.0 entry said quick pages are not synced "on either backend". That is right for WebDAV, and
too absolute for CouchDB: a quick page you have **drawn on since setting up sync** is sent, but one
you have not is not, and a quick page arriving from another device may not appear in your library.
Quick pages remain outside proper sync on both backends — keep anything you want on both devices in
a notebook.

### Upgrading

No database change: the schema stays at 39.

If you have notebooks that never made it to the server, they go on the next sync — there is nothing
to do by hand. A notebook that reached the server before this release is unaffected.

## 0.7.0

### Sync says what it is doing

- **The activity log survives closing the app.** Scheduled syncs run in the background, often
  while notable is closed, so by the time you opened Settings to ask why nothing had happened the
  record was already gone. The log is now kept on disk and is there when you come back to it.
  Lines from before the last launch are shown dimmed, so it is clear which run you are reading.
- **A sync that does nothing now says why.** Turned off, waiting for WiFi, missing a password,
  no network, or already running — every one of these used to be a silent no-op. Each names the
  setting to change, and each run is separated in the log with the time it started.
- **Every run ends with what it did** — how many notebooks were uploaded, downloaded, merged,
  left unchanged, or held back by upload-only or download-only mode.
- **Warnings and errors stand out** rather than reading as ordinary lines, and Copy Log now hands
  over the whole kept history, including runs from before the last launch.

### Syncing one notebook

- **Long-press a notebook and choose Sync now.** Until now the only ways to get a notebook to the
  server were the scheduled run, the whole-library sync buried in settings, or closing the note
  with sync-on-close turned on — none of them reachable from the notebook you were looking at and
  wondering about.
- **A sync you asked for is no longer dropped** when another one happens to be running. It waits
  its turn instead of quietly doing nothing while reporting success.

### Also

- **Progress shows the page it is on.** A notebook with hundreds of pages used to hold a single
  line steady for minutes, which was hard to tell apart from a freeze.
- **Detailed logging**, off by default, under Settings → Sync → Activity Log. It records the
  decision made for each notebook and each page transferred. Worth turning on while you are
  chasing a specific problem, and worth turning off afterwards — it fills the log quickly.

### Upgrading

No database change: the schema stays at 39. Nothing about how your notes sync has changed, only
how much of it is written down and where you can ask for it.

One thing this release makes easier to notice rather than fixes: **quick pages are not synced**,
and never have been on either backend. They live outside any notebook, and sync works in units of
notebooks. If a page you expected on your other device is one of the loose pages on the home
screen, that is why — and there is no way to move it into a notebook today either. Keep anything
you want on both devices in a notebook. Closing this gap is the next thing to look at.

## 0.6.0

### Pictures sync

- **An image placed on one device now appears on the other.** The page already recorded that a
  picture was there — its position and size travelled correctly — but the bytes never did, so the
  other device knew an image belonged on the page and had nothing to draw. This affected CouchDB
  sync only; WebDAV has always copied the files.
- **The same picture is uploaded once, however many pages place it.** An image is stored under a
  fingerprint of its own contents, so two devices that hold the same photo agree on its name
  without comparing notes, and neither sends it twice.
- **Pictures are downloaded only for pages you have.** They are fetched when a page you hold
  refers to one, rather than pulling every image in the library, and a download interrupted by a
  flaky connection is retried on the next sync instead of leaving a blank space for good.

### Upgrading

No database change: the schema stays at 39.

Images already on your pages are sent the next time their page changes. To bring across pictures
on pages you are not about to edit, use "Upload everything on this BOOX" once in Settings → Sync.

The iPad app needs its matching release to receive these — until then, this BOOX uploads the
image data but the iPad has nothing that fetches it. Nothing is lost in the meantime: pages,
ink and image positions sync as before, and the iPad leaves its own copies of pictures alone.

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
