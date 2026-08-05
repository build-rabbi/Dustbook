# Session Status — Dustbook Offline Sync (2026-08-05, round 4)

## ✅ Pushed + CI green
- Branch `arena/019fce78-dustbook` — 5 code commits + status file, all pushed.
- CI for the latest commit: all steps green (jsdom suite, lint, unit tests,
  debug + release APK, blocklist-in-APK, signing cert, uploads).

## Round 4 — owner's device findings (commit `d4f2b0e`)

### Finding 1: ad blocker ON = black offline reels; OFF = content shows
Root cause: the network ad blocker (`shouldInterceptRequest`) and the cosmetic
ad-remover scripts (MFacebookAds / CosmeticFilters) ran on EVERY page,
including offline-served ones. They blocked/hid things the offline page
needed, leaving a black feed.
Fix (MainActivity):
- `shouldInterceptRequest` blocks only when `isOnline`.
- Cosmetic ad scripts are injected only when `isOnline` (both onPageStarted
  and injectAll). Offline pages are saved content, never advertising.

### Finding 2: 10 posts saved but only 1 visible; no scrolling on home/reels
Root cause: the saved cards were injected into the stored Facebook document,
whose own skeleton is dead offline and whose scroller is a fixed-height
window — appended content was out of reach or invisible.
Fix (OfflineDocs): **the cards-only shell page is now the primary offline
page.** Whenever a section has playable saved cards, `serve()` returns the
shell (all cards in normal document flow → natural scrolling; media
`max-width:100%`; OfflineNav tab bar; OfflineBanner; VideoHelper assist for
reels; storyViewer for stories; built-cache like the document path). The
stored Facebook document is served only when nothing is saved. Injection into
the skeleton is gone (`OfflineInject` no longer called from `serve()`).

### Finding 3: video ads saved in the offline library
Fix (OfflineCapture): a card whose text or aria-label contains "sponsored"
is never captured — advertising is not content.

### Verification
- Full jsdom suite: **838 passed, 0 failed** (sections rewritten for the
  shell architecture + 3 new sections: shell serving, ad-block-off-offline,
  sponsored-skip).
- Brace balance OK on all modified files.
- CI: all steps green.

## Notes
- `OfflineInject.kt` is retained (now unused by serve()) — its own unit tests
  still pass; it can be deleted in a later cleanup if the owner wants.
- Stored documents (home.html etc.) are still refreshed in the background and
  used as the fallback when the store is empty.
- Device test still needed: install the new build, go offline, confirm home
  feed shows ALL saved posts with working scroll, reels play, no black
  screens, no video ads in the library.
