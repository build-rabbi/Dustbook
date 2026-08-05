# Session Status — Dustbook Offline Sync (2026-08-05, round 5)

## ✅ Pushed + CI green
- Branch `arena/019fce78-dustbook` — commit `d7984b3` (this round) pushed.
- CI run 30964408206: **ALL GREEN** — jsdom suite (849 checks) · Lint · Unit tests ·
  Debug + Release APK · blocklist-in-APK · signing · uploads.

## Round 5 — the real root causes (owner's feedback: ads back online, offline UI broken, ads offline)

### 1. Online ads returned (ad blocker weaker than v5.1.0)
Root cause: previous round gated ad blocking on the connectivity flag
(`isOnline`). That flag races with page loads — a page that loaded fine could
have been started while the flag was momentarily false, so the cosmetic ad
scripts never injected and requests were never blocked → ads on online reels.
Fix: a **per-load `servingOffline` flag**, set synchronously in
`shouldInterceptRequest` when (and only when) `OfflineDocs.serve()` answers the
main frame. Online pages get full v5.1.0-strength blocking again; offline
pages get none. No connectivity race.

### 2. Offline UI broken ("puray venge")
Root cause: the previous cards-only shell page had no Facebook CSS, so saved
cards rendered as raw unstyled HTML.
Fix: the offline page is **the stored Facebook document again** (real CSS,
header, tab bar — the v5.1.0 look), with the saved cards injected into its
feed container (append-first, dedupe by exact id, no-blank guard, overflowY
unclip so every card is reachable). The black screens this design hit earlier
were caused by issue 1 (the cosmetic ad remover hiding saved content offline)
— that is now fixed at the root, so the injection works.

### 3. Ads in the offline library (cv666.com / fbzdd.com spam posts)
Three layers now:
- Capture skips any card labelled "Sponsored".
- `realPlayableItems` filters saved cards whose markup says "Sponsored" — they
  neither count toward the total nor display (applies to previously captured
  items too).
- The served document runs a tiny offline ad-hider that hides sponsored cards
  captured earlier inside the stored page itself.

## Verification
- Full jsdom suite: **849 passed, 0 failed**.
- Brace balance OK; no dangling `isOnline &&` ad gates left.
- CI: all steps green.

## Notes / options for the owner
- If the owner wants a guaranteed-good baseline while testing, the last
  released tag **v5.1.0** is still there (`git checkout v5.1.0`). The arena
  branch is the one with all offline-flow work.
- Device test checklist for the new build (d7984b3):
  1. Online: reels feed — no ads (adblock ON).
  2. Offline: home feed — Facebook look, ALL saved posts, scrolling works.
  3. Offline: reels play, no black screen, adblock setting irrelevant.
  4. Offline library: no sponsored/spam posts (old ones need a one-time
     "Clear offline" to purge stored items, or they are filtered at display
     anyway).
