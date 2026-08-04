# Session Status — Dustbook Offline Sync (2026-08-05)

## ✅ FINAL: Pushed + CI green (2 commits on arena/019fce78-dustbook)
- `309d31e` — Offline sync: follow the user's exact save flow (recreated from handoff)
- `6219df3` — Offline sync: exact sequential flow, all saved cards visible (this session's fixes)
- Both pushed to `origin/arena/019fce78-dustbook`. CI run 30959463937 (commit 6219df36):
  **ALL GREEN** — jsdom suite (834 checks) · Lint · Unit tests · Debug APK · Release APK ·
  blocklist-in-APK · signing cert · uploads.

## Important: the previous commit was LOST
The handoff said commit `dd65a73` was local on `arena/019fce78-dustbook`, and the
branch existed on the remote. **Neither existed** in this session's checkout
(fresh clone of `main` only). The previous session's sandbox did not carry over.
The changes were recreated from the handoff description (5 files, identical intent).

## This session's fixes (commit 6219df3) — owner's flow + display bug

### Flow — strict sequential, exact counts (never silently lowered)
Order: **10 posts → reels (user's exact count) → 300 posts → stories**.
- Steps now run in **rounds until the fully-downloaded count reaches the target**
  (`runUntilTarget` in BackgroundSyncManager, max 18 rounds, 12 s gap), then hand over.
  One capture pass used to stop short of the target and the pipeline moved on
  (12 of 30 reels, 40 of 300 posts).
- **Posts pause during reels**: live browsing capture of feed posts is skipped while
  the pipeline is on the reels step (`MainActivity.onOfflineItems` gate on
  `currentStep == "reels"/"wait-video"`), so posts are never downloaded during reels.
- Steps never overlap; next step starts only when the previous one finished.

### Display bug — 100+ saved, only 10-12 visible (posts AND reels)
Root cause: the offline page is the stored Facebook document, which carries its own
copy of the first page (~10-12 posts). The saved cards were appended AFTER it and,
on a virtualized scroller, out of reach — so the feed showed only the document's
own handful.
Fix (`OfflineInject.kt`): the injector now **clears the document's own cards and
placeholders** (keeping chrome: composer, tabs, dividers — nothing duplicated,
because those same cards are already in the store), **appends all saved cards**, and
**unfreezes the scroller** (`height:auto; overflow:visible`). Same fix covers reels.
jsdom behaviour test: 40 saved cards land on the page, zero duplicates, chrome kept.

### Verification
- Full jsdom suite: **834 passed, 0 failed** (was 821) — 13 new checks added.
- Brace balance on all modified Kotlin files OK; no dangling refs.
- CI (GitHub Actions): all steps green.

## Observations for the owner (NOT changed — outside scope)
- `MainActivity.kt` `onOfflineItems` still uses `offlineReelTarget.coerceAtLeast(30)`
  — a no-op in practice (settings options are 30-250), left as is.
- Live REELS capture during the posts steps is NOT gated (only feed-during-reels was
  asked for). Symmetric gate is one line away if wanted.
- `SESSION_STATUS.md` was committed with 6219df3 as a working record — delete if unwanted.
