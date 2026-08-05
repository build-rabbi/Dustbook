# Session Status — Dustbook Offline Sync (2026-08-05, round 6)

## ✅ Pushed + CI green
- Branch `arena/019fce78-dustbook` — commit `e56cce9` (this round) pushed.
- CI run 30987054941: **ALL GREEN** — jsdom suite (845 checks) · Lint · Unit tests ·
  Debug + Release APK · blocklist-in-APK · signing · uploads.

## Round 6 — the fix that finally matches the device reality
Reported: offline home flashed the stored skeleton for ~1 second then went
blank; reels never played or scrolled.

Root cause: the stored Facebook skeleton's own scripts are dead offline and
its scroller is a fixed-height window — injecting the saved cards into it
failed on the real layout (appended content out of reach, then the dedupe
pass left nothing visible). This injection approach had failed repeatedly
across rounds 3-5.

The fix (OfflineDocs.kt): the offline page is now, whenever a section holds
playable saved cards, a **plain saved-cards page that reuses Facebook's own
stylesheet <link> tags extracted from the stored document** (those CSS files
are already in the offline cache via the asset prefetch), so:
- the cards look exactly as they did online (real CSS — no raw markup);
- every saved card is in normal document flow — the page scrolls naturally,
  nothing is hidden in a fixed-height scroller;
- offline nav, offline banner, video assist (reels/watch/stories), story
  viewer (stories), the sponsored-card hider and the built cache all stay;
- injection into the stored skeleton is gone from the serve path; when
  nothing is saved, the stored document is served untouched.

Still in place from round 5 (unchanged): per-load `servingOffline` flag keeps
online ad blocking at full strength while offline pages get no ad blocking
(they are saved content); capture + display + served-page hider keep
sponsored/spam posts out of the library.

## Verification
- Full jsdom suite: **845 passed, 0 failed**.
- Brace balance OK. CI all green.

## Device test checklist (build e56cce9)
1. Offline home: saved posts appear immediately (no flash-then-blank),
   Facebook look, scrolling reaches every saved post.
2. Offline reels: cards listed and playable, page scrolls.
3. Online: reels still ad-free with the ad blocker on.
4. Library: no sponsored/spam posts.
