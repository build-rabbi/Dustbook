# Session Status — Dustbook Offline Sync (2026-08-05, round 3)

## ✅ Pushed + CI green
- Branch `arena/019fce78-dustbook` — 4 code commits + status file.
- CI run 30961974283 (commit e0df223): **ALL GREEN** — jsdom suite (844 checks) ·
  Lint · Unit tests · Debug + Release APK · blocklist-in-APK · signing · uploads.

## Round 3 — black screen on offline home & reels (commit e0df223)
User report (with screenshots): offline home feed and reels tab show a black
screen — no content. Screenshot analysis: header renders faintly, feed area
pure black (screenshot 2 = 99.6% black).

Root cause: the injector removed the document's own cards and then appended
the saved cards; on the real Facebook DOM the appended content did not render,
leaving an empty dark feed area.

Fixes (OfflineInject.kt + OfflineDocs.kt):
- **Append FIRST, remove afterwards** — and remove ONLY cards whose id exactly
  matches a saved card's id (`cardIdOf` mirrors the capture's `idOf`; ids fed
  from `OfflineFeed.realPlayableItems` via a `SAVED` set built in Kotlin).
  Nothing is removed on a guess.
- **No-blank guard**: if `CARDS` is empty, nothing is removed at all; the whole
  inject body is wrapped in try/catch — the offline page can never be left
  blank by this script.
- Un-clipping is now **overflowY-only** (html/body/scroller); the previous
  `height:auto` changes are gone (could collapse FB's own layout, e.g. the
  full-screen reels pager).
- Grey skeleton placeholders are still removed (empty, media-less blocks).

Tests: behavioural coverage rewritten (40 cards injected; document duplicates
removed by id; a non-duplicate doc card is kept; no-blank guard verified).
Full suite **844 passed, 0 failed**.

## Still open
- Device test needed: after this build, offline home should show ALL saved
  posts (scrollable) and reels should play. If anything is still off, a fresh
  screenshot + which build was installed will pin it down.
- The app's own diagnostic toggle (Settings → About) can inspect the served
  page if needed.
