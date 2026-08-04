# Session Status — Dustbook Offline Sync (2026-08-05, round 2)

## ✅ Pushed + CI green
- Branch `arena/019fce78-dustbook` — 3 code commits + status file, all pushed.
- CI run for the latest commit: all steps green (jsdom suite, lint, unit tests,
  debug + release APK, blocklist-in-APK, signing cert, uploads).

## Round 2 — what broke and what was fixed (commit `1a0b1f2`)

### Bug 1 (owner report): "10 posts download na hoye agei reels download start hoi"
Root cause: `runUntilTarget` short-circuited when the store already held
≥ target playable items — the store had 100+ posts from before, so step A
(10 posts) was skipped instantly and reels started immediately. The count was
also judged right after the capture pass while media was still downloading,
and an 18-round budget silently lowered every amount.
Fix:
- Posts steps now ADD their fresh batch: goal = held count + asked amount
  (`before + target`), so 10 fresh posts are always captured before reels.
- Reels keep-count stays the goal (store capped at exactly the user's count).
- After every round the download queue is drained (`awaitPrefetch`) BEFORE the
  count is judged — "complete" means playable, playable means media on disk.
- Round budget removed: no silent lowering; next step only starts when the
  previous one is actually complete.
- `OfflineSync.run` gained `storeLimit` so a step's store cap can be raised to
  the goal instead of silently truncating the batch.

### Bug 2 (owner report): "offline ui full venge"
Previous round's injector removed anything not recognised as "chrome" and set
`overflow:visible` on the scroller — too aggressive; the offline feed broke.
Fix (surgical, minimal):
- Only elements carrying story-card markers (the same ones the capture uses:
  data-tracking-duration-id / data-video-id / data-story-id / data-comp-id /
  data-successful-render-id, or links to posts/reels/videos/stories) are
  removed — the document's own copy of the first page, so nothing is
  duplicated. Composer, story tray, tabs, headers, dividers: untouched.
- The page (html/body) and the scroller are un-clipped (`overflowY:auto`) so
  the appended saved cards are reachable by scrolling. No other layout
  touched.

### Verification
- Full jsdom suite: **839 passed, 0 failed** (was 834; flow + inject tests updated).
- Brace balance OK; no dangling refs (isChrome in OfflineCapture/OfflineFeed is
  a separate pre-existing function, unrelated).
- CI: all steps green.

## Still open (needs a device report)
- If the offline feed still shows only a handful of posts on a real device,
  a screenshot of the offline home screen is needed — the exact container
  structure of the live m.facebook.com page cannot be fetched from the sandbox
  (HTTP 400), so DOM-level diagnosis needs eyes on the device.
