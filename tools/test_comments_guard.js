#!/usr/bin/env node
/**
 * Regression guard: the blockers must never remove comment content.
 *
 * Reported as issue #1: on a post's comment page the thread renders, then a
 * second later every comment vanishes and reloading does not help. Two
 * mechanisms were responsible:
 *
 *  1. A sponsored unit sitting inside the comment thread. cardOf()/hideStory()
 *     climbed to the thread's vscroller child and removed the whole thread
 *     with the ad.
 *  2. Published filter rules shaped like "the rounded card inside the card"
 *     matched comment cards, which use the same border-radius shell as ads.
 *
 * This test asserts the two directions that matter:
 *   - comment threads and comment items survive every blocker, even when a
 *     sponsored unit lives inside the thread (only the unit itself may go),
 *   - real feed ads are still removed, including a feed card that happens to
 *     carry inline comment previews.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const ROOT = path.join(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

// ---- extract the JS payloads exactly as the CI tests do --------------------
function extract(ktFile, fnName, flags) {
  const src = read(ktFile);
  const i = src.indexOf('fun ' + fnName);
  if (i < 0) throw new Error('function not found: ' + fnName + ' in ' + ktFile);
  const start = src.indexOf('return """', i) + 'return """'.length;
  const end = src.indexOf('""".trimIndent()', start);
  return src.slice(start, end)
    .replace(/\$\{'\"'\}/g, '"')
    .replace(/\$flagsJs/g, flags)
    .replace(/\$blockAds/g, 'true')
    .replace(/\$blockAppPromos/g, 'true');
}

const KT_COSMETIC = 'app/src/main/java/com/dustbook/app/utils/CosmeticFilters.kt';
const KT_ADBLOCK = 'app/src/main/java/com/dustbook/app/utils/AdBlocker.kt';
const KT_MFB = 'app/src/main/java/com/dustbook/app/utils/MFacebookAds.kt';

const asset = read('app/src/main/assets/fb_cosmetic.txt');
const plain = asset.split('[plain]')[1].split('[procedural]')[0].trim().split('\n').filter(Boolean);
const proc = asset.split('[procedural]')[1].trim().split('\n').filter(Boolean);

const cosmeticEngine = extract(KT_COSMETIC, 'proceduralScript', '')
  .replace('$list', proc.map((r) => `'${r.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`).join(','));
const adBlockerCosmetic = extract(KT_ADBLOCK, 'getCosmeticScript',
  'stories:false,reels:false,rooms:false,marketplace:false,groups:false,watch:false,events:false,gaming:false,memories:false,birthdays:false,pymk:false,pages:false');
const mfbScript = extract(KT_MFB, 'script', '');

const FLAGS = { cosmetic: cosmeticEngine, adblock: adBlockerCosmetic, mfb: mfbScript };

function run(html, url, which) {
  const dom = new JSDOM(html, {
    runScripts: 'outside-only', pretendToBeVisual: true, url,
  });
  const w = dom.window;
  w.requestIdleCallback = undefined;
  w.requestAnimationFrame = (f) => setTimeout(f, 0);
  try { w.HTMLMediaElement.prototype.pause = function () {}; } catch (e) {}
  const styled = new Set();
  for (const sel of plain) {
    try { for (const el of w.document.querySelectorAll(sel)) styled.add(el); } catch (e) {}
  }
  for (const k of which) w.eval(FLAGS[k]);
  return new Promise((r) => setTimeout(() => r({ w, styled }), 900));
}

const HIDE_ATTRS = ['data-db-hidden', 'data-fbpro-hidden', 'data-db-ad'];

function hidden(ctx, sel) {
  const el = ctx.w.document.querySelector(sel);
  if (!el) return 'MISSING';
  let p = el;
  while (p && p.getAttribute) {
    for (const a of HIDE_ATTRS) if (p.getAttribute(a) === '1') return true;
    if (ctx.styled.has(p)) return true;
    p = p.parentElement;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Fixture A: m.facebook.com post page - story card + comment thread as
// separate vscroller children. One sponsored unit sits INSIDE the thread,
// exactly the shape that used to take the whole thread with it.
// ---------------------------------------------------------------------------
const MOBILE_POST_PAGE = `<body id="app-body"><div id="screen-root">
<div data-mcomponent="MScreen" data-type="container" class="m bg-s30 dark-mode">
 <div data-type="vscroller" data-mcomponent="MContainer" class="m">
  <div data-mcomponent="MContainer" data-type="container" id="STORY_CARD" class="m">
    <div data-mcomponent="MContainer" data-type="container" class="m bg-s2">
      <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Rafi Hasan</span></div></div>
      <div data-mcomponent="TextArea"><div class="native-text"><span>2h</span></div></div>
    </div>
    <div data-mcomponent="TextArea"><div class="native-text">কী সুন্দর দিন! সবাই ভালো আছেন?</div></div>
    <div data-mcomponent="MContainer" data-type="container" class="m">
      <div role="button" data-action-id="1">Like</div>
      <div role="button" data-action-id="2">Comment</div>
      <div role="button" data-action-id="3">Share</div>
    </div>
  </div>
  <div data-mcomponent="MContainer" data-type="container" id="COMMENTS_THREAD" class="m">
    <div data-sigil="comment" id="C1" class="m">
      <div class="m bg-s2">
        <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Nusrat Jahan</span></div></div>
        <div data-mcomponent="TextArea"><div class="native-text">দারুণ লাগলো!</div></div>
        <div class="m"><span role="button">Like</span><span role="button">Reply</span></div>
      </div>
      <div data-sigil="comment" id="C1R1" class="m">
        <div class="m bg-s2">
          <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Rafi Hasan</span></div></div>
          <div data-mcomponent="TextArea"><div class="native-text">ধন্যবাদ!</div></div>
        </div>
      </div>
    </div>
    <div data-sigil="comment" id="C2" class="m">
      <div class="m bg-s2">
        <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Tanvir Ahmed</span></div></div>
        <div data-mcomponent="TextArea"><div class="native-text">অপূর্ব ছবি 🔥</div></div>
      </div>
    </div>
    <div data-mcomponent="MContainer" data-type="container" id="THREAD_AD" class="m">
      <div class="m bg-s2">
        <div data-mcomponent="TextArea"><div class="native-text"><span>Sponsored</span></div></div>
        <div data-mcomponent="TextArea"><div class="native-text">Eid Sale - 50% off</div></div>
        <a href="/ads/about/?entry_product=ad_preferences">Why this ad</a>
      </div>
    </div>
    <div data-sigil="comment" id="C3" class="m">
      <div class="m bg-s2">
        <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Mim Akter</span></div></div>
        <div data-mcomponent="TextArea"><div class="native-text">শেয়ার করেছেন সবাইকে</div></div>
      </div>
    </div>
  </div>
 </div></div></div></body>`;

// ---------------------------------------------------------------------------
// Fixture B: m.facebook.com feed - an ad story card WITH inline comment
// previews. The card must still be removed; the previews are part of the ad.
// ---------------------------------------------------------------------------
const MOBILE_FEED = `<body id="app-body"><div id="screen-root">
<div data-mcomponent="MScreen" data-type="container" class="m bg-s30 dark-mode">
 <div data-type="vscroller" data-mcomponent="MContainer" class="m">
  <div data-dcm-id="1" data-mcomponent="MContainer" data-type="container" id="FEED_AD" class="m">
    <div data-mcomponent="MContainer" data-type="container" class="m bg-s2">
      <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Regal Emporium</span></div></div>
      <div data-mcomponent="TextArea"><div class="native-text"><span>Ad</span></div></div>
    </div>
    <div data-testid="story-photo-0"><img src="https://scontent.fdac1-1.fna.fbcdn.net/v/t39/x.jpg"></div>
    <div data-sigil="comment" class="m">
      <div class="m bg-s2"><div data-mcomponent="TextArea"><div class="native-text">বাহ! কিনবো</div></div></div>
    </div>
  </div>
  <div data-mcomponent="MContainer" data-type="container" id="FEED_REAL" class="m">
    <div data-mcomponent="MContainer" data-type="container" class="m bg-s2">
      <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Zihan Khan</span></div></div>
      <div data-mcomponent="TextArea"><div class="native-text"><span>1d</span></div></div></div>
    <div>a genuine post from a friend</div>
    <div data-sigil="comment" class="m">
      <div class="m bg-s2"><div data-mcomponent="TextArea"><div class="native-text">দারুণ!</div></div></div>
    </div>
  </div>
 </div></div></div></body>`;

// ---------------------------------------------------------------------------
// Fixture C: desktop www.facebook.com post page - article with a comment
// section whose cards share the border-radius shell of ad units.
// ---------------------------------------------------------------------------
const DESKTOP_POST_PAGE = `<body><div id="mount_0_0">
<div role="region" id="TOPNAV"><div>facebook</div><div>Home</div></div>
<div role="main" id="MAIN">
 <div>
  <div role="article" id="DESKTOP_POST">
   <div style="border-radius: max(0px, min(8px, ((100vw - 4px) - 100%) * 9999)) / 8px;">
    <div>Rafi Hasan</div>
    <div>2h</div>
    <div>কী সুন্দর দিন! সবাই ভালো আছেন?</div>
    <div><span>Like</span><span>Comment</span><span>Share</span></div>
   </div>
   <div data-testid="comment_section" id="DESKTOP_COMMENTS">
    <div role="article" id="DC1">
     <div style="border-radius: max(0px, min(8px, ((100vw - 4px) - 100%) * 9999)) / 8px;">
      <div>Nusrat Jahan</div>
      <div>দারুণ লাগলো!</div>
      <div><span>Like</span><span>Reply</span></div>
     </div>
    </div>
    <div role="article" id="DC2">
     <div style="border-radius: max(0px, min(8px, ((100vw - 4px) - 100%) * 9999)) / 8px;">
      <div>Tanvir Ahmed</div>
      <div>অপূর্ব ছবি</div>
     </div>
    </div>
   </div>
  </div>
 </div>
</div></div></body>`;

// ---------------------------------------------------------------------------
// Fixture D: desktop feed - a sponsored post (article with ads/about link).
// The post is an ad: it must still be removed even though it has comments.
// ---------------------------------------------------------------------------
const DESKTOP_FEED = `<body><div id="mount_0_0">
<div role="feed" id="FEED">
 <div role="article" id="DESKTOP_AD">
  <div style="border-radius: max(0px, min(8px, ((100vw - 4px) - 100%) * 9999)) / 8px;">
   <div>Regal Emporium</div>
   <div>Sponsored</div>
   <a href="/ads/about/?entry_product=ad_preferences">Why this ad</a>
  </div>
  <div data-testid="comment_section">
   <div role="article"><div>some comment</div></div>
  </div>
 </div>
 <div role="article" id="DESKTOP_REAL">
  <div style="border-radius: max(0px, min(8px, ((100vw - 4px) - 100%) * 9999)) / 8px;">
   <div>Rafi Hasan</div>
   <div>2h</div>
   <div>a genuine post</div>
  </div>
  <div data-testid="comment_section">
   <div role="article"><div>a real comment</div></div>
  </div>
 </div>
</div></div></body>`;

let pass = 0, fail = 0;
const check = (n, got, want) => {
  const ok = got === want;
  ok ? pass++ : fail++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${n}${ok ? '' : ` (got ${got}, want ${want})`}`);
};

(async () => {
  const ALL = ['cosmetic', 'adblock', 'mfb'];
  const url = 'https://m.facebook.com/story.php?story_fbid=1&id=2';

  console.log('A) m.site post page: comments survive a sponsored unit in the thread');
  for (const which of [['cosmetic'], ['adblock'], ['mfb'], ALL]) {
    const ctx = await run(MOBILE_POST_PAGE, url, which);
    const tag = which.join('+');
    check(`thread survives (${tag})`, hidden(ctx, '#COMMENTS_THREAD'), false);
    check(`comment 1 survives (${tag})`, hidden(ctx, '#C1'), false);
    check(`reply survives (${tag})`, hidden(ctx, '#C1R1'), false);
    check(`comment 2 survives (${tag})`, hidden(ctx, '#C2'), false);
    check(`comment 3 survives (${tag})`, hidden(ctx, '#C3'), false);
    check(`story card survives (${tag})`, hidden(ctx, '#STORY_CARD'), false);
  }

  console.log('B) m.site feed: an ad card with inline comment previews is still removed');
  {
    const ctx = await run(MOBILE_FEED, 'https://m.facebook.com/', ALL);
    check('ad card removed', hidden(ctx, '#FEED_AD'), true);
    check('real post survives', hidden(ctx, '#FEED_REAL'), false);
  }

  console.log('C) desktop post page: comment cards survive every blocker');
  for (const which of [['cosmetic'], ['adblock'], ['mfb'], ALL]) {
    const ctx = await run(DESKTOP_POST_PAGE, 'https://www.facebook.com/story.php?story_fbid=1&id=2', which);
    const tag = which.join('+');
    check(`comments section survives (${tag})`, hidden(ctx, '#DESKTOP_COMMENTS'), false);
    check(`comment 1 survives (${tag})`, hidden(ctx, '#DC1'), false);
    check(`comment 2 survives (${tag})`, hidden(ctx, '#DC2'), false);
    check(`post survives (${tag})`, hidden(ctx, '#DESKTOP_POST'), false);
  }

  console.log('D) desktop feed: a sponsored post is still removed');
  {
    const ctx = await run(DESKTOP_FEED, 'https://www.facebook.com/', ALL);
    check('sponsored post removed', hidden(ctx, '#DESKTOP_AD'), true);
    check('real post survives', hidden(ctx, '#DESKTOP_REAL'), false);
  }

  console.log(`\n${pass} passed, ${fail} failed`);
  if (fail) {
    console.log('\n::error::comment guard regression');
    process.exit(1);
  }
})();
