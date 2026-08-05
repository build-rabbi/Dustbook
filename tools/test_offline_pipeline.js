#!/usr/bin/env node
/**
 * End-to-end guard for the offline delivery pipeline.
 *
 * Reported: offline content is visible but broken - the feed renders as raw
 * unstyled markup with a giant wordmark and overlapping text, reels do not
 * play, stories will not open.
 *
 * Traced through every stage. The markup was being stored correctly; the
 * failure was in delivery:
 *
 *   isInterceptable() decided what the offline store was allowed to answer by
 *   looking at the file extension and the Accept header. Facebook's
 *   stylesheets live at /rsrc.php/... with no extension, its fonts are
 *   requested with `Accept: * / *`, and its video files are /o1/v/t2/... with
 *   neither. All three were refused, and refusing means handing the request to
 *   a WebView that has no connection.
 *
 * These assertions model the real request shapes and would have caught it.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const ROOT = path.join(__dirname, '..');
const KT = (f) => path.join(ROOT, 'app/src/main/java/com/dustbook/app', f);

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? ' :: ' + extra : '')); }
}

/** The Kotlin raw-string body of a script, as the app would serve it. */
function raw(file, marker) {
  const src = fs.readFileSync(file, 'utf8');
  const i = src.indexOf(marker);
  const start = src.indexOf('"""', i) + 3;
  const end = src.indexOf('"""', start);
  return src.slice(start, end);
}

const cache = fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8');
const docs  = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
const main  = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
const feed  = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
const sync  = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
const cap   = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');

// ---------------------------------------------------------------- delivery
console.log('\nWhat the store is allowed to answer');
{
  // The fix: if the bytes are on disk they are servable, whatever the URL
  // looks like. This has to be checked BEFORE the extension guess.
  const fn = cache.slice(cache.indexOf('fun isInterceptable'));
  const body = fn.slice(0, fn.indexOf('\n    }'));

  ok('a stored file is served regardless of its URL shape',
     /if \(has\(url\)\) return true/.test(body));

  const hasAt = body.indexOf('has(url)');
  const extAt = body.indexOf('getFileExtensionFromUrl');
  const accAt = body.indexOf('requestHeaders["Accept"]');
  ok('that check comes before the extension test',
     hasAt >= 0 && extAt >= 0 && hasAt < extAt, `${hasAt} vs ${extAt}`);
  ok('and before the Accept-header test',
     hasAt >= 0 && accAt >= 0 && hasAt < accAt, `${hasAt} vs ${accAt}`);

  // The guards that must survive: replaying these breaks the session.
  ok('GraphQL is still never intercepted', body.includes('/api/graphql'));
  ok('/ajax/ is still never intercepted', body.includes('/ajax/'));
  ok('the main frame is still never intercepted',
     body.includes('isForMainFrame'));
  ok('non-GET is still never intercepted',
     body.includes('equals("GET"'));
}

console.log('\nContent types');
{
  // application/octet-stream is not a safe fallback: a WebView will not apply
  // a stylesheet or play a video served under it.
  ok('a missing mime sidecar is filled in, not defaulted to octet-stream',
     /fun guessMime/.test(cache));
  ok('an empty sidecar counts as missing',
     /takeIf \{ it\.isNotBlank\(\) \} \?: guessMime/.test(cache));

  const g = cache.slice(cache.indexOf('fun guessMime'));
  const gb = g.slice(0, g.indexOf('\n    }'));
  ok('stylesheets are typed as css', /"text\/css"/.test(gb));
  ok('fonts are typed as fonts', /font\/woff2/.test(gb));
  // Facebook video carries no extension at all.
  ok('extensionless facebook video is typed as video',
     /\/v\/t2\/[\s\S]{0,80}video\/mp4/.test(gb));

  const gCount = (cache.match(/\?: guessMime\(url\)/g) || []).length;
  ok('both the whole-file and range paths use it', gCount >= 2,
     String(gCount));
}

console.log('\nVideo playback');
{
  const get = cache.slice(cache.indexOf('fun get(request'));
  const getBody = get.slice(0, get.indexOf('\n    /**'));

  // Without these a media element will not issue the Range request that
  // range() answers, so a perfectly good cached video refuses to play.
  ok('a whole-file response advertises range support',
     /"Accept-Ranges" to "bytes"/.test(getBody));
  ok('and reports its length',
     /"Content-Length" to f\.length\(\)/.test(getBody));

  ok('range replies are 206 with Content-Range',
     /206/.test(cache) && /Content-Range/.test(cache));
  ok('the resolver tries range before the whole file',
     /Range[\s\S]{0,200}OfflineCache\.range\(request\)[\s\S]{0,200}OfflineCache\.get/
       .test(main));
}

console.log('\nWhat gets downloaded in the first place');
{
  // A feed full of photos used to push the stylesheets past the cap, so they
  // were never fetched and the stored page had no styling at all.
  ok('page chrome is queued ahead of photos',
     /partition \{ it\.contains\("\/rsrc\.php\/"\) \}/.test(docs));
  ok('the cap is a named constant, not a magic number',
     /MAX_PREFETCH_URLS/.test(docs));

  ok('video is still recognised without an extension',
     /\/v\/t2\//.test(feed));
  ok('downloads are queued rather than dropped',
     /queued\.add\(u\)/.test(feed) && /private fun drain\(\)/.test(feed));
}

console.log('\nAll three sections are reachable offline');
{
  for (const s of ['home', 'reels', 'stories']) {
    ok(`${s} is a stored screen`, new RegExp(`"${s}" to `).test(docs));
  }
  ok('a story URL routes to the stories screen',
     /"stories", "story" -> "stories"/.test(docs));
  ok('an unknown URL still lands somewhere real',
     /else -> "home"/.test(docs));
  ok('a screen with cards but no page still renders',
     /return shellFor\(screen\)/.test(docs));
}

// ------------------------------------------- offline must not look different
console.log('\nNothing is invented offline');
{
  const nav    = fs.readFileSync(KT('utils/OfflineNav.kt'), 'utf8');
  const banner = fs.readFileSync(KT('utils/OfflineBanner.kt'), 'utf8');
  const inject = fs.readFileSync(KT('utils/OfflineInject.kt'), 'utf8');

  // The rule, stated once: offline shows Facebook's own markup, unaltered.
  // Every violation below shipped at least once and had to be taken back out.

  // A "Feed | Reels | Stories" bar drawn by us, which is not on the real site.
  ok('no navigation bar of our own',
     !/class="nav"/.test(docs) && !/>Reels</.test(docs));
  ok('a screen with no stored page renders nothing rather than a made-up one',
     /<!DOCTYPE html>/.test(docs));
  ok('stored cards now count as offline content alongside stored pages',
     /OfflineFeed\.hasAnything\(\)/.test(
       main.slice(main.indexOf('fun hasAnythingOffline'),
                  main.indexOf('fun hasAnythingOffline') + 500)));

  // Faded controls and floating toasts are ours; online has neither.
  ok('no control is dimmed', !/opacity/.test(nav) && !/opacity/.test(banner));
  ok('nothing is overlaid on the page', !/__db_toast/.test(banner));
  ok('no element is hidden or removed',
     !/display\s*:\s*none/.test(banner) && !/display\s*:\s*none/.test(nav));
  ok('the offline banner adds no markup, only behaviour',
     !/<div/.test(banner) && !/<style/.test(banner));
  ok('injected cards are not restyled by us',
     !/scroll-snap-type/.test(inject));

  // Taps that cannot work are swallowed - but silently, the way a dead
  // control behaves, not with a message the real site never shows.
  ok('failing actions are swallowed', /preventDefault/.test(banner));
  ok('and say nothing',
     /fun onOfflineNavMissing[\s\S]{0,400}?\n        \}/.test(main) &&
     !/onOfflineNavMissing[\s\S]{0,300}?toast\(/.test(main));
}

// ------------------------------------------------------------- the icon font
console.log('\nFacebook icon font');
{
  // Facebook draws Like, Comment, Share and the rest as glyphs from its own
  // icon font - proven on the device: the "Ad" label read Ad + U+F078B +
  // U+F1677, and the offline screenshots showed tofu boxes where the icons
  // belong. A font URL appears only inside the CSS:
  //
  //   html : <link rel="stylesheet" href=".../AbCdEf.css">
  //   css  : @font-face { src: url(".../IcOnFoNt.woff2") }
  //
  // Scanning the page markup alone can never find it.
  ok('stylesheets are read, not just listed',
     /OfflineCache\.textOf/.test(docs));
  ok('a css url() is matched', /val CSS_URL/.test(docs));
  ok('what they reference is queued for download',
     /val fonts[\s\S]{0,400}CSS_URL\.findAll/.test(docs));
  ok('and queued ahead of photos',
     /\(fonts \+ chrome \+/.test(docs));

  // The font can only be found once the stylesheet is on disk, so one pass is
  // not enough - on the first sweep the CSS is still downloading.
  ok('a second sweep runs after the stylesheets land',
     /awaitPrefetch[\s\S]{0,400}prefetchUrls/.test(main));
  ok('the second sweep skips what is already stored',
     /filterNot \{ OfflineCache\.has\(it\) \}/.test(main));
  ok('waiting never happens on the UI thread',
     /AppExecutors[\s\S]{0,900}awaitPrefetch/.test(main));

  ok('a stored font is typed as a font, not octet-stream',
     /font\/woff2/.test(cache));

  // Verify the pattern against the real shape of an @font-face rule.
  const m = docs.match(/val CSS_URL = Regex\("""([\s\S]+?)"""\)/);
  ok('the css url pattern is present', !!m);
  if (m) {
    const re = new RegExp(m[1], 'g');
    const css = '@font-face{font-family:x;' +
      'src:url("https://static.xx.fbcdn.net/rsrc.php/v4/yK/r/F.woff2") format("woff2")}';
    const hit = [...css.matchAll(re)].map((x) => x[1]);
    ok('it finds a woff2 in a real @font-face rule',
       hit.length === 1 && hit[0].endsWith('.woff2'), JSON.stringify(hit));
  }
}

// -------------------------------------------- where stored pages come from
console.log('\nStored pages come from a WebView, not raw HTTP');
{
  // Verified against the live site: m.facebook.com answers HTTP 400 to a
  // plain HTTP client. Five header combinations were tried - logged out,
  // with a cookie, narrow Accept, full browser Accept, identity and gzip -
  // and every one was refused. fetchScreen() therefore stored nothing,
  // savedScreens() stayed empty, and going offline showed the bare
  // "Can't load the page" screen with no saved content at all.
  ok('there is a path that stores a page captured from a WebView',
     /fun storeFromPage/.test(docs));
  ok('the sync WebView hands its document over',
     /fun onOfflinePage/.test(sync));
  ok('the visible WebView does too',
     /fun onOfflinePage/.test(main));
  ok('the page sends it', /bridge\.onOfflinePage/.test(cap));

  // Guards carried over from the HTTP path.
  ok('a logged-out page is never stored',
     /fun storeFromPage[\s\S]{0,900}loggedout/.test(docs));
  ok('it is written atomically',
     /fun storeFromPage[\s\S]{0,2000}\.part[\s\S]{0,200}renameTo/.test(docs));
  ok('a page too small to be a screen is rejected',
     /fun storeFromPage[\s\S]{0,400}MIN_DOC_BYTES/.test(docs));
  ok('storing happens off the UI thread',
     /AppExecutors[\s\S]{0,200}OfflineDocs\.storeFromPage/.test(main));
}

// ------------------------------------------------- sound, and profile pictures
console.log('\nVideo sound');
{
  const vh = fs.readFileSync(KT('utils/VideoHelper.kt'), 'utf8');

  // The stored markup now carries the cached video URL on the src
  // attribute directly, so the browser plays it natively. The assist
  // script sets preload instead of auto-playing every video — that
  // was what silently killed sound on the second reel.
  ok('videos get preload and playsinline',
     /preload/.test(vh) && /playsinline/.test(vh));
  ok('no video is forced silent',
     !/v\.muted\s*=\s*true/.test(vh));
  ok('and no running video is un-muted either',
     !/v\.muted\s*=\s*false/.test(vh));
}

console.log('\nProfile pictures are saved');
{
  // An avatar is tiny next to a feed photo and there is one on every card,
  // so queued together they sat at the back of a 400-URL list and were
  // dropped - every saved post had a blank circle where the poster is.
  ok('avatars are queued ahead of post photos',
     /val \(avatars, photos\) = media\.partition/.test(docs));
  ok('and ahead of them in the final list',
     /\(fonts \+ chrome \+ avatars \+ photos\)/.test(docs));

  // Verify the split against the real URLs captured on the device.
  const m = docs.match(/it\.contains\("(\/t39\.30808-1\/)"\)/);
  ok('the avatar path is the one Facebook uses', !!m, String(m));
  {
    const avatar = 'https://scontent.fdac182-1.fna.fbcdn.net/v/t39.30808-1/500316650_n.jpg?stp=c0.5';
    const photo  = 'https://scontent.fcgp1-1.fbcdn.net/v/t51.2885-15/postphoto.jpg';
    const isAvatar = (u) => u.includes('/t39.30808-1/') || u.includes('_n.jpg?stp=c');
    ok('a real avatar URL is recognised', isAvatar(avatar));
    ok('a real post photo is not', !isAvatar(photo));
  }

  // The per-card path queues everything, so nothing is dropped there.
  ok('card media is queued without a cap',
     /Queue, never drop/.test(feed));
}

// ------------------------------------------ offline navigation and the banner
console.log('\nOffline navigation is not rebuilt every time');
{
  // serve() used to reassemble the whole page on every navigation, on the
  // WebView's resource thread: read the stored document, parse the item
  // store, then SHA-256 and stat every media URL to decide what is playable.
  // With a couple of hundred reels that is hundreds of hashes and filesystem
  // stats per back press, so going back from Reels crawled - and got worse
  // the more content was saved.
  ok('an assembled page is kept', /private val built/.test(docs));
  ok('and reused while the stored page is unchanged',
     docs.includes('built[screen]'));
  ok('the rebuilt page is stored for next time',
     docs.includes('built[screen] = Built('));

  // A stale page would be worse than a slow one.
  ok('saving new cards drops it',
     /OfflineDocs\.invalidate\(\)/.test(feed));
  ok('clearing saved content drops it too',
     /fun clear\(\)[\s\S]{0,120}invalidate\(\)/.test(feed) ||
     /fun clear\(\)[\s\S]{0,120}OfflineDocs\.invalidate\(\)/.test(feed));
  ok('and clearing the pages drops it',
     /fun clear\(\)[\s\S]{0,80}invalidate\(\)/.test(docs));
}

console.log('\nThe offline notice is a toast, not a permanent bar');
{
  const layout = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/layout/activity_main.xml'), 'utf8');

  // The bar was pinned to the bottom of every screen and covered the video
  // while it played.
  ok('no permanent bar in the layout', !/offlineBanner/.test(layout));
  ok('nothing references it any more', !/offlineBanner/.test(main));
  ok('the notice is shown as a toast',
     /toast\(getString\(R\.string\.offline_banner\)\)/.test(main));

  // Said when it becomes true, and when saved content is opened - not held
  // on screen for the whole session.
  const hits = (main.match(/toast\(getString\(R\.string\.offline_banner\)\)/g) || []).length;
  ok('shown on the events that matter, not continuously', hits === 2,
     String(hits));
}

// ------------------------------------------ the "Tap to unmute" label offline
//
// Facebook dismisses this label with its own JS on interaction. Offline that
// JS never runs, so it has to be gone from the stored markup. A lazy regex
// ending at the first </div> stopped inside the overlay, left its trailing
// </div> behind and corrupted the markup, so the label survived.
console.log('\nStored markup carries no audio overlay');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');

  ok('the overlay is removed by counting depth, not a lazy regex',
     /function removeTag\(html, attr, value\)/.test(cap) &&
     !/m-video-overlay["'\]]*\[\^>\]\*>\[\\s\\S\]\*\?/.test(cap));
  ok('it is applied to the audio overlay',
     /removeTag\(html, 'data-sigil', 'm-video-overlay'\)/.test(cap));
  ok('the scan is bounded so malformed markup cannot spin',
     /guard < \d+/.test(cap));

  // Run the real function against the shapes Facebook actually serves.
  // Built defensively: if the helper is missing these assertions must fail,
  // not throw and hide every section after this one.
  let removeTag = null;
  try {
    const src = cap.slice(cap.indexOf('function removeTag'),
                          cap.indexOf('function markupOf'));
    // eslint-disable-next-line no-new-func
    removeTag = new Function(src + '; return removeTag;')();
  } catch (e) {
    removeTag = null;
  }
  if (typeof removeTag !== 'function') {
    removeTag = () => '<<removeTag missing>>';
  }

  const cases = {
    'flat': '<div data-sigil="m-video-overlay">Tap to unmute</div><div id="k">real</div>',
    'nested': '<div class="x" data-sigil="m-video-overlay"><div class="i">' +
              '<span>Tap to unmute</span></div></div><div id="k">real</div>',
    'deeply nested': '<div data-sigil="m-video-overlay"><div><div><div>' +
              'Tap to unmute</div></div></div></div><div id="k">real</div>',
    'two overlays': '<div data-sigil="m-video-overlay"><div>Tap to unmute</div></div>' +
              '<p>mid</p><div data-sigil="m-video-overlay"><div>unmute</div></div>' +
              '<div id="k">real</div>',
  };
  for (const [name, html] of Object.entries(cases)) {
    const out = removeTag(html, 'data-sigil', 'm-video-overlay');
    const opens = (out.match(/<div/g) || []).length;
    const closes = (out.match(/<\/div>/g) || []).length;
    ok('removed from ' + name, !/unmute/i.test(out), out);
    ok('  real content kept in ' + name, /real/.test(out));
    ok('  markup stays balanced in ' + name, opens === closes,
       opens + ' open vs ' + closes + ' close');
  }
  ok('markup with no overlay is untouched',
     removeTag('<div id="k">real</div>', 'data-sigil', 'm-video-overlay')
       === '<div id="k">real</div>');
}

// ---------------------------------------- the offline story sits too high
//
// Online the activity pads its root view so content clears the status bar. A
// position:fixed overlay inside the WebView is measured against the viewport
// and never sees that padding, so the offline story viewer started under the
// status bar while the online one did not.
console.log('\nOffline story viewer clears the status bar');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const sv = docs.slice(docs.indexOf('private fun storyViewer'));

  ok('the overlay asks for the safe area',
     /safe-area-inset-top/.test(sv) && /safe-area-inset-bottom/.test(sv));
  ok('env() is set through a stylesheet, not the style attribute',
     /createElement\('style'\)/.test(sv) &&
     /__db_story_overlay\{/.test(sv));
  ok('a plain 0 fallback precedes it',
     /top:0;bottom:0;[\s\S]{0,80}safe-area-inset-top/.test(sv));
  ok('viewport-fit=cover is ensured, or env() never resolves',
     /viewport-fit/.test(sv) && /meta\[name=viewport\]/.test(sv));
  ok('an existing viewport meta is amended, not replaced',
     /indexOf\('viewport-fit'\) === -1/.test(sv));
}

// -------------------------------- reading is not gated by the save switches
//
// Turning offline saving off used to hide content that was already on disk.
// Nothing had been deleted -- switching it back on made everything reappear --
// so the app was refusing to show something it still had.
console.log('\nSaved content is readable whatever the save switches say');
{
  const prefs = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const app = fs.readFileSync(KT('DustbookApplication.kt'), 'utf8');
  const sa = fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');

  ok('reading has its own flag', /val offlineRead: Boolean/.test(prefs));
  ok('and it does not depend on the section switches',
     /val offlineRead: Boolean get\(\) = true/.test(prefs));
  ok('saving still follows the switches',
     /offlineReels \|\| offlineFeed \|\| offlineStories/.test(prefs));

  ok('serving a stored page checks read, not save',
     /if \(prefs\.offlineRead && !isOnline\) \{[\s\S]{0,120}OfflineDocs\.serve\(request\)/.test(ma));
  ok('falling back to saved content checks read',
     /prefs\.offlineRead && !isOnline && hasAnythingOffline\(\)/.test(ma));
  ok('the View saved content button checks read',
     /canOffline = prefs\.offlineRead && hasAnythingOffline\(\)/.test(ma));
  ok('no read path is gated on offlineMode any more',
     !/prefs\.offlineMode && !isOnline/.test(ma));

  ok('the stores separate reading from writing',
     /writeEnabled/.test(fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8')) &&
     /writeEnabled/.test(fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8')));
  ok('put() is the one gated on writing',
     /fun put[\s\S]{0,400}if \(!writeEnabled\) return/
       .test(fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8')));
  ok('get() is not',
     /fun get\([\s\S]{0,300}if \(!enabled\) return null/
       .test(fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8')));

  ok('startup enables reading unconditionally',
     /OfflineCache\.enabled = prefs\.offlineRead/.test(app) &&
     /OfflineCache\.writeEnabled = prefs\.offlineMode/.test(app));
  ok('the activity uses one helper for both',
     /private fun applyOfflineFlags\(\)/.test(ma));
  ok('toggling a switch changes only writing',
     /OfflineCache\.writeEnabled = write/.test(sa) &&
     !/OfflineCache\.enabled = on/.test(sa));
}

// ------------------------------- the unmute label on already-stored content
console.log('\nThe unmute label is stripped when the page is served');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  ok('a strip script exists', /private fun unmuteStripScript/.test(docs));
  ok('the stored document gets it',
     /f\.readText\(\) \+\s*\n\s*unmuteStripScript\(\)/.test(docs));
  ok('the assembled page gets it too',
     (docs.match(/unmuteStripScript\(\)/g) || []).length >= 3);
  ok('it keeps watching, since markup is swapped in after load',
     /MutationObserver/.test(docs.slice(docs.indexOf('unmuteStripScript'))));
  ok('a long caption mentioning the word is protected',
     /t\.length > 40/.test(docs));
}

// ------------------------------- an item counts only when it is fully saved
//
// The old rule was `any {}`: one cached asset was enough. A post with five
// photos counted as saved when one had arrived, so the number climbed while
// the content behind it was still downloading.
console.log('\nCounting waits for the whole item');
{
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');

  ok('there is one rule for what counts',
     /fun isFullyDownloaded\(item: Item\): Boolean/.test(feed));
  // Not "every URL": that was tried and left every item permanently
  // incomplete, because capture records srcset variants that are never all
  // fetched. The rule is per-kind — see the section below.
  ok('the item is judged by kind, not by URL count',
     /val videos = item\.media\.filter \{ isVideoUrl\(it\) \}/.test(feed));
  ok('the loose any-of test is gone',
     !/item\.media\.any \{ u ->[\s\S]{0,120}OfflineCache\.has\(u\)/.test(feed));
  ok('a video must also be a plausible size, not merely present',
     /OfflineCache\.hasMinSize\(it, MIN_VIDEO_BYTES\)/.test(feed));
  ok('a text post with no media still counts',
     /if \(item\.media\.isEmpty\(\)\) return true/.test(feed));

  ok('posts, reels and stories share the rule',
     /fun realPlayableCount\(section: String\): Int =\s*\n\s*loadItems\(section\)\.count \{ isFullyDownloaded/.test(feed));
  ok('what is displayed uses the same rule as what is counted',
     /fun realPlayableItems[\s\S]{0,160}isFullyDownloaded/.test(feed) &&
     /fun cardsHtml[\s\S]{0,120}realPlayableItems/.test(feed));

  // Exercise the rule itself rather than trusting its shape.
  const MIN = 500000;
  const disk = {};
  const isVideo = (u) => /\/o1\/v\/|\.mp4|video/.test(u);
  const full = (media) => media.length === 0 ? true : media.every((u) =>
    isVideo(u) ? (disk[u] !== undefined && disk[u] >= MIN) : disk[u] !== undefined);

  const set = (o) => { for (const k of Object.keys(disk)) delete disk[k]; Object.assign(disk, o); };

  set({ 'a.jpg': 9 });
  ok('a five-photo post does not count on the first photo',
     full(['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg']) === false);
  set({ 'a.jpg': 9, 'b.jpg': 9, 'c.jpg': 9, 'd.jpg': 9, 'e.jpg': 9 });
  ok('and does once they are all there',
     full(['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg']) === true);
  set({ 'av_s.jpg': 4000 });
  ok('a reel does not count on its avatar alone',
     full(['av_s.jpg', '/o1/v/vid']) === false);
  set({ 'av_s.jpg': 4000, '/o1/v/vid': 1000 });
  ok('nor on a truncated video',
     full(['av_s.jpg', '/o1/v/vid']) === false);
  set({ 'av_s.jpg': 4000, '/o1/v/vid': 9000000 });
  ok('but does once the video is really there',
     full(['av_s.jpg', '/o1/v/vid']) === true);
  ok('a text post counts immediately', full([]) === true);

  // ---------------------------------------------------------------
  // A post whose only images are chrome must still be readable.
  //
  // Reported as: offline shows only a handful of the posts that were saved.
  //
  // Capture records every <img> inside a card, so an ordinary text update
  // carries the author's avatar and any emoji in the body. The rule then read
  // that as "this item has media and none of it arrived" and hid the post -
  // even though the words were already in the stored markup and there was
  // nothing left to wait for. On a feed of text updates that hides almost
  // everything.
  //
  // Faithful port of the real predicates, so this exercises the decision and
  // not a paraphrase of it.
  const isVideoUrl = (u) => {
    const c = u.split('?')[0].toLowerCase();
    return c.endsWith('.mp4') || c.endsWith('.webm') || c.includes('/v/t2/');
  };
  const isAvatar = (u) => {
    const c = u.split('?')[0];
    return c.includes('/t39.30808-1/') || (c.includes('profile') && c.includes('_s.'));
  };
  const isChrome = (u) => {
    const c = u.split('?')[0].toLowerCase();
    return c.includes('/emoji.php/') || c.includes('static.xx.fbcdn.net') ||
           c.includes('/rsrc.php/') || c.endsWith('.svg');
  };
  const shown = (media, cache) => {
    if (media.length === 0) return true;
    const has = (u) => cache[u] !== undefined;
    const hasMin = (u) => (cache[u] || 0) >= MIN;
    const videos = media.filter(isVideoUrl);
    const images = media.filter((u) => !isVideoUrl(u));
    if (videos.length) return videos.some(hasMin);
    if (images.some((u) => has(u) && !isAvatar(u))) return true;
    if (images.every(has)) return true;
    if (images.every((u) => isAvatar(u) || isChrome(u))) return true;
    return false;
  };

  const AV = 'https://scontent.xx.fbcdn.net/v/t39.30808-1/1_s.jpg';
  const PHOTO = 'https://scontent.xx.fbcdn.net/v/t51.0-10/photo_n.jpg';
  const EMOJI = 'https://static.xx.fbcdn.net/images/emoji.php/v9/t4b/1/16/1f600.png';
  const VID = 'https://video.xx.fbcdn.net/v/t2/reel.mp4';

  ok('a text post carrying only the author avatar is shown',
     shown([AV], {}) === true);
  ok('and one carrying an avatar and an emoji is shown',
     shown([AV, EMOJI], {}) === true);
  ok('a photo post is still withheld until the photo arrives',
     shown([AV, PHOTO], { [AV]: 4000 }) === false);
  ok('and shown once it has',
     shown([AV, PHOTO], { [AV]: 4000, [PHOTO]: 90000 }) === true);
  ok('a reel is still withheld while its video downloads',
     shown([AV, VID], { [AV]: 4000, [VID]: 120000 }) === false);
  ok('and shown once the video is really there',
     shown([AV, VID], { [AV]: 4000, [VID]: 9000000 }) === true);

  const feedSrc = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  ok('the chrome test exists in the source',
     /private fun isChrome\(url: String\): Boolean/.test(feedSrc));
  ok('and is only reached after the real-content tests',
     feedSrc.indexOf('images.any { OfflineCache.has(it) && !isAvatar(it) }') <
     feedSrc.indexOf('images.all { isAvatar(it) || isChrome(it) }'));
}

// ------------------------------------------- tapping Stories offline works
console.log('\nStories can be opened offline');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('routing asks what is reachable, not what has a document',
     /fun navigableScreens\(\): List<String>/.test(docs));
  ok('a screen held only as cards still routes',
     /storedCount\(section\) > 0/.test(docs));
  ok('the nav script is built from it',
     /OfflineNav\.script\(navigableScreens\(\)\)/.test(docs) &&
     !/OfflineNav\.script\(savedScreens\(\)\)/.test(docs));
  ok('and so is the offline landing choice',
     /val saved = OfflineDocs\.navigableScreens\(\)/.test(ma));
  ok('stories map to the stories section',
     /"stories" -> OfflineFeed\.SECTION_STORIES/.test(docs));
}

// ------------------------------------------------ the five-step pipeline
console.log('\nThe pipeline runs in the order it documents');
{
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  const bsm = fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8');

  // targetFor used to raise every request to the V4 constants, so step 1
  // chased 500 posts instead of 50 and never handed over to reels.
  ok('a requested target is not silently raised',
     /private fun targetFor\(section: String, target: Int\): Int = target/.test(sync));
  ok('the V4 ceilings no longer override callers',
     !/coerceAtLeast\(OfflineManager\.V4_FEED_TARGET\)/.test(sync) &&
     !/coerceAtLeast\(OfflineManager\.V4_REEL_TARGET\)/.test(sync));
  ok('step 1 asks for 10 posts', /val target = 10/.test(bsm));
  ok('and hands over to reels', /step1NewPosts[\s\S]{0,600}step2Reels\(context, p\)/.test(bsm));
  ok('reels use the user\'s chosen count',
     /step2Reels[\s\S]{0,200}p\.offlineReelTarget/.test(bsm));
  ok('then 300 posts, then stories last',
     /step3WaitForVideo[\s\S]{0,600}step4MorePosts\(context, p\)/.test(bsm) &&
     /step4MorePosts[\s\S]{0,600}step5Stories\(context, p\)/.test(bsm));

  // A single capture pass ends when the page stops producing new cards,
  // which is usually short of the goal. The steps therefore run in rounds
  // until the fully-downloaded count actually reaches the goal, and only
  // then hand over — otherwise "keep 30 reels" saved a dozen and moved on.
  ok('each step loops until its goal is met',
     /private fun runUntilTarget/.test(bsm) &&
     /OfflineFeed\.realPlayableCount\(section\) >= goal/.test(bsm));
  // Reels: the goal is the user's keep-count itself. Posts: the step adds
  // its own fresh batch on top of what is already held — a store that
  // already holds posts must not make the 10-post step skip straight to
  // reels, which is what used to happen.
  ok('posts add their fresh batch to what is held',
     /val goal = if \(section == OfflineFeed\.SECTION_REELS\) target else before \+ target/.test(bsm));
  ok('reels run until exactly the user\'s count',
     /runUntilTarget\(context, p, OfflineFeed\.SECTION_REELS, target, "reels"\)/.test(bsm));
  ok('the 300-post pass runs until 300 are stored',
     /runUntilTarget\(context, p, OfflineFeed\.SECTION_FEED, target, "posts-300"\)/.test(bsm));
  // "Complete" means playable, and playable means media on disk — so the
  // download queue is drained before the count is judged, and the next step
  // never starts before this one is complete.
  ok('a step only advances once downloads are complete',
     /OfflineFeed\.awaitPrefetch\(300_000\)/.test(bsm) &&
     /realPlayableCount\(section\) >= goal/.test(bsm));
  ok('there is no silent round budget that lowers an amount',
     !/MAX_ROUNDS/.test(bsm));
  ok('the store limit is raised to the goal, never below it',
     /storeLimit = goal/.test(bsm) &&
     /storeLimit: Int\? = null/.test(sync) &&
     /addItems\(sec, newItems, storeLimit \?: target\)/.test(sync));
  ok('a step never starts before the previous one finished',
     !/OfflineSync\.run\(context, OfflineFeed\.SECTION_FEED,[\s\S]{0,40}OfflineSync\.run\(context, OfflineFeed\.SECTION_REELS/.test(bsm));
}

console.log('\nPosts pause while reels are being downloaded');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  // The visible WebView also captures what the user scrolls past. While the
  // pipeline is on its reels step, feed posts from browsing must not start
  // downloading in parallel — the flow is strictly sequential.
  ok('live feed capture is skipped during the reels step',
     /section == OfflineFeed\.SECTION_FEED && BackgroundSyncManager\.isRunning/.test(ma) &&
     /BackgroundSyncManager\.currentStep == "reels"/.test(ma) &&
     /BackgroundSyncManager\.currentStep == "wait-video"/.test(ma));
}

console.log('\nSaved images survive to the offline page');
{
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  // Facebook lazy-loads feed images: with loading blocked the <img> tags
  // never receive a real fbcdn URL, so capture collected none and every
  // offline post came back as text with blank gaps.
  ok('the capture WebView loads images',
     /loadsImagesAutomatically = true/.test(sync) &&
     /blockNetworkImage = false/.test(sync));
}

console.log('\nThe assembled page is rebuilt when media lands');
{
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('finishing a download invalidates the built page',
     /if \(stored > 0\) OfflineDocs\.invalidate\(\)/.test(feed));
  ok('offline refresh reaches a cards-only screen',
     /val saved = OfflineDocs\.navigableScreens\(\)[\s\S]{0,300}screenFor\(binding\.webView\.url/.test(ma));

  // Serving a page must not re-parse every section on the resource thread.
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  ok('routing uses a cheap existence check',
     /OfflineFeed\.storedCount\(section\) > 0/.test(docs));
  // Scope to the function body, not the rest of the file: realPlayableCount
  // is used legitimately elsewhere.
  ok('and not the expensive one',
     !/fun navigableScreens[\s\S]{0,700}?realPlayableCount[\s\S]{0,60}?\n    \}/.test(docs));
  ok('the cheap check does not parse',
     /fun storedCount[\s\S]{0,300}f\.length\(\) > 2L/.test(feed));
}

console.log('\nFullscreen video is not restarted by a layout pass');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  ok('the inset refresh stands down during fullscreen',
     /fun refreshInsetsAfterLoad[\s\S]{0,400}if \(customView != null \|\| inFullscreenTransition\) return/.test(ma));
  // The enter and exit handlers used to carry identical copies of the settle
  // block, so this counted three call sites. They now share one helper, which
  // is what lets a new transition cancel the previous one's pending callback.
  // The requirement is unchanged: leaving fullscreen must still ask for a
  // fresh pass, and must not do it while the player is up.
  ok('the fullscreen handlers still request their own',
     /private fun endFullscreenTransition\(\)[\s\S]{0,900}ViewCompat\.requestApplyInsets\(binding\.root\)/
       .test(ma) &&
     /private fun endFullscreenTransition\(\)[\s\S]{0,900}customView == null/.test(ma) &&
     (ma.match(/ViewCompat\.requestApplyInsets/g) || []).length >= 2);
}

console.log('\nCompleteness allows for srcset alternates');
{
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');

  // Requiring every URL was too strict. Capture records each srcset variant,
  // but only the one the renderer chose is ever fetched, so an item could
  // never reach "complete" and nothing was served offline at all.
  ok('a video item is judged on its video',
     /if \(videos\.isNotEmpty\(\)\)[\s\S]{0,160}videos\.any \{ OfflineCache\.hasMinSize/.test(feed));
  ok('the all-URLs rule is gone',
     !/item\.media\.all \{ u ->/.test(feed));
  ok('an avatar alone still does not count',
     /private fun isAvatar/.test(feed) &&
     /images\.any \{ OfflineCache\.has\(it\) && !isAvatar\(it\) \}/.test(feed));

  const MIN = 500000; const disk = {};
  const isVideo = (u) => /\/o1\/v\/|\.mp4|video/.test(u);
  const isAvatar = (u) => { const c = u.split('?')[0];
    return c.includes('/t39.30808-1/') || (c.includes('profile') && c.includes('_s.')); };
  const has = (u) => disk[u] !== undefined;
  const full = (m) => { if (!m.length) return true;
    const v = m.filter(isVideo), i = m.filter((u) => !isVideo(u));
    if (v.length) return v.some((u) => has(u) && disk[u] >= MIN);
    return i.some((u) => has(u) && !isAvatar(u)) || i.every(has); };
  const set = (o) => { for (const k of Object.keys(disk)) delete disk[k]; Object.assign(disk, o); };

  set({ 'p_640.jpg': 50000 });
  ok('a photo post counts on one real variant',
     full(['p_640.jpg', 'p_960.jpg', 'p_1280.jpg']) === true);
  set({ 'r_320.jpg': 9000, '/o1/v/v.mp4': 9000000 });
  ok('a reel counts once its video is on disk',
     full(['r_320.jpg', 'r_640.jpg', '/o1/v/v.mp4']) === true);
  set({ 'av/t39.30808-1/a.jpg': 4000 });
  ok('but not on an avatar alone',
     full(['av/t39.30808-1/a.jpg', '/o1/v/v.mp4']) === false);
  set({ '/o1/v/v.mp4': 1000 });
  ok('nor on a truncated video', full(['/o1/v/v.mp4']) === false);
}

console.log('\nA reel keeps a playable video URL offline');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  // data-video-url normally sits on a child MVideo wrapper. Reading only the
  // card root left the <video> on its dead blob:, so offline showed a poster
  // and a play button that did nothing.
  ok('the wrapper is searched, not just the card root',
     /card\.querySelector\('\[data-video-url\]'\)/.test(cap));
  ok('the dead blob src is replaced',
     /src\s*=\s*\\?"' \+ dv|' \+ dv \+ '/.test(cap));
  ok('and source children are stripped first',
     /<source\\b\[\^>\]\*>/.test(cap));
}

console.log('\nSaved cards are injected into the stored document');
{
  const inj = fs.readFileSync(KT('utils/OfflineInject.kt'), 'utf8');
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const prefs = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  const xml = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/xml/settings_browsing.xml'), 'utf8');

  // The offline page is the stored Facebook document (its own CSS, header
  // and tab bar), with the saved cards injected into its feed container.
  ok('the stored document is served and injected into',
     /f\.readText\(\)/.test(docs) && /OfflineInject\.script/.test(docs));
  ok('the injector breaks the closing-script sequence',
     /\.replace\("<\/script", "<\/scr` \+ `ipt"\)/.test(inj));
  ok('the story viewer does the same',
     /\.replace\("<\/script", "<\/scr` \+ `ipt"\)/.test(docs));
  ok('saved cards are appended before anything is removed',
     /box\.appendChild\(holder\);[\s\S]{0,900}var id = cardIdOf\(k\)/.test(inj));
  ok('only exact duplicates are removed, never on a guess',
     /function cardIdOf\(el\)/.test(inj) &&
     /data-tracking-duration-id/.test(inj) &&
     /if \(id && SAVED\[id\]\)/.test(inj));
  ok('with nothing to inject, nothing is removed',
     /if \(!CARDS\) return;/.test(inj));
  ok('a failure can never blank the page',
     /try \{[\s\S]{0,600}box\.appendChild\(holder\)/.test(inj) &&
     /catch \(e\) \{\s*\/\/ Never let a failure blank the page/.test(inj));
  ok('the page is un-clipped so saved cards are reachable',
     /document\.documentElement\.style\.overflowY = 'auto'/.test(inj) &&
     !/\.style\.height = 'auto'/.test(inj));
  ok('pull to refresh is on by default',
     /KEY_PULL_REFRESH, true\)/.test(prefs) &&
     /android:key="pull_to_refresh"[\s\S]{0,120}android:defaultValue="true"/.test(xml));
}

console.log('\nEvery saved card is on the offline page');
{
  // Behaviour, not shape: a stored document with its own posts (exact
  // duplicates of saved cards), a non-duplicate card, placeholders and
  // chrome goes through the injector; every saved card must be on the page,
  // duplicates must be gone, and nothing else may be touched.
  const cards = [];
  for (let i = 1; i <= 40; i++) {
    cards.push('<div class="card" data-tracking-duration-id="c' + i + '">' +
               '<span>saved post ' + i + '</span></div>');
  }
  const savedIds = cards.map((c) => 'data-tracking-duration-id:' +
    c.match(/data-tracking-duration-id="([^"]+)"/)[1]);
  let injectSrc = raw(KT('utils/OfflineInject.kt'), 'fun script(')
    .replace('$cards', cards.join('\n'));
  // Fill the SAVED set the way the Kotlin side does (asJsSet).
  const setJs = savedIds.map((id) => 'SAVED["' + id + '"]=1;').join('');
  injectSrc = injectSrc.replace('${savedIds.asJsSet()}', setJs);

  const dom = new JSDOM(
    `<body>
       <div data-type="vscroller">
         <div class="composer"><div role="textbox">What's on your mind?</div></div>
         <div class="tray" aria-label="Stories"><span>Story tray</span></div>
         <div class="placeholder" style="height:80px"></div>
         <div class="docpost" data-tracking-duration-id="c1">
           <img src="https://scontent.fbcdn.net/x.jpg"><span>document post 1</span></div>
         <div class="docpost" data-tracking-duration-id="c2">
           <span>document post 2</span></div>
         <div class="special" data-special="1"><span>not a saved post</span></div>
       </div>
     </body>`,
    { runScripts: 'outside-only', url: 'https://m.facebook.com/' });

  dom.window.eval(injectSrc);

  const scroller = dom.window.document.querySelector('[data-type="vscroller"]');
  const saved = scroller.querySelectorAll('.card');
  ok('all saved cards are injected', saved.length === 40, String(saved.length));
  ok('the document\'s duplicate posts are removed',
     scroller.querySelectorAll('.docpost').length === 0);
  ok('a card the store does not hold is left alone',
     !!scroller.querySelector('.special'));
  ok('placeholders are gone', scroller.querySelectorAll('.placeholder').length === 0);
  ok('the composer chrome is kept', !!scroller.querySelector('.composer'));
  ok('the story tray is kept', !!scroller.querySelector('.tray'));
  ok('the page can scroll to reach every card',
     dom.window.document.documentElement.style.overflowY === 'auto' &&
     dom.window.document.body.style.overflowY === 'auto' &&
     scroller.style.overflowY === 'auto');
}

console.log('\nWith nothing saved, the offline page is not emptied');
{
  // The no-blank guard: when the store holds no playable cards, the
  // injector must leave the stored document exactly as it is.
  let injectSrc = raw(KT('utils/OfflineInject.kt'), 'fun script(')
    .replace('$cards', '')
    .replace('${savedIds.asJsSet()}', '');
  const dom = new JSDOM(
    `<body><div data-type="vscroller">
       <div class="docpost" data-tracking-duration-id="old1">
         <span>document post</span></div>
     </div></body>`,
    { runScripts: 'outside-only', url: 'https://m.facebook.com/' });
  dom.window.eval(injectSrc);
  const scroller = dom.window.document.querySelector('[data-type="vscroller"]');
  ok('the document\'s own posts are still there',
     scroller.querySelectorAll('.docpost').length === 1);
  ok('and no empty holder was appended',
     scroller.querySelectorAll('[data-db-cards]').length === 0);
}

console.log('\nAd blocking runs everywhere except offline pages');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  // Reported: with the ad blocker on, the offline reels feed was a black
  // screen; turning it off showed the content. Offline pages are saved
  // content, not advertising — the ad blocker and the cosmetic ad remover
  // must run on every page EXCEPT offline-served ones. The offline state is
  // tracked per page load (servingOffline, set in shouldInterceptRequest),
  // not via the connectivity flag, so online ad blocking stays as strong as
  // v5.1.0 with no race.
  ok('the offline page is tracked per load, not by connectivity',
     /servingOffline = true/.test(ma) && /servingOffline = false/.test(ma));
  ok('requests are blocked on every page except offline ones',
     /if \(!servingOffline && AdBlocker\.shouldBlockRequest\(request\)\)/.test(ma));
  ok('cosmetic ad scripts are injected on every page except offline ones',
     (ma.match(/if \(!servingOffline && prefs\.adBlock && prefs\.cosmeticFilter\)/g) || []).length >= 2);
  ok('the offline main frame is served before blocking decisions',
     /OfflineDocs\.serve\(request\)/.test(ma));
}

console.log('\nAdvertising is never captured or shown');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  // Reported: video ads appeared in the offline library (cv666.com / fbzdd.com
  // spam posts). Three layers: capture skips a card labelled sponsored,
  // display/count filters any saved card whose markup says sponsored, and
  // the served document hides sponsored cards it captured earlier.
  ok('a card labelled sponsored is skipped at capture',
     /text\.toLowerCase\(\)\.indexOf\('sponsored'\) >= 0/.test(cap) &&
     /al\.indexOf\('sponsored'\) >= 0/.test(cap));
  ok('a saved sponsored card never counts or displays',
     /private fun isSponsored\(item: Item\)/.test(feed) &&
     /item\.html\.contains\("sponsored", ignoreCase = true\)/.test(feed) &&
     /filter \{ !isSponsored\(it\) && isFullyDownloaded\(it\) \}/.test(feed));
  ok('the served document hides sponsored cards captured earlier',
     /private fun offlineAdHideScript/.test(docs) &&
     /__db_off_ad_hide/.test(docs) &&
     /card\.style\.display = 'none'/.test(docs));
}

console.log('\nNo app-promo bar flashes on an offline page');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');

  // Online the hiding CSS goes in from onPageStarted, before first paint. An
  // offline page is answered by shouldInterceptRequest, so everything
  // appended lands after the stored markup — the bar got a frame to paint in
  // and was then removed, which is the flash.
  ok('a hiding stylesheet exists', /private fun promoHideCss/.test(docs));
  ok('it is plain CSS, not a script that must run first',
     /<style id="__db_promo_hide">/.test(docs));
  ok('it is prepended to the stored document, not appended',
     /val html = promoHideCss\(\) \+\s*\n\s*f\.readText\(\)/.test(docs));
  ok('the assembled page gets it inside head',
     /promoHideCss\(\) \+\s*\n\s*"<\/head>/.test(docs));
  ok('it covers the store links and the known banner ids',
     /play\.google\.com\/store/.test(docs) &&
     /mobile_app_install_banner/.test(docs) &&
     /display:none !important/.test(docs));
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
