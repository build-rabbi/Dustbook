package com.dustbook.app.utils

/**
 * Ad removal for the current m.facebook.com renderer.
 *
 * Written from markup captured on a real device, not guessed. Two attributes
 * mark a sponsored story and neither appears on an organic post:
 *
 *   data-video-tracking='{"adid":"1202511...","is_sponsored":1, ...}'
 *   data-testid="sponsored-story-photo"
 *
 * The card boundary is equally exact. The feed is a vertical scroller and each
 * story is a direct child of it:
 *
 *   div[data-type="vscroller"]              the feed
 *     > div[data-mcomponent="MContainer"]   one story
 *
 * So the story to remove is the nearest ancestor that is a direct child of the
 * scroller. That is a lookup, not a heuristic, which is why this cannot climb
 * into the feed and blank the page the way the earlier guesswork did.
 */
object MFacebookAds {

    fun script(): String {
        return """
            (function() {
              'use strict';
              if (window.__dbMfb) { window.__dbMfb.run(); return; }

              var TAG = 'data-db-ad';

              // Attributes that only ever appear on sponsored content.
              // Matched against the real attribute value, so truncation in any
              // debug output is irrelevant.
              var AD_ATTR = [
                '[data-video-tracking*="\\"is_sponsored\\":1"]',
                '[data-video-tracking*="\\"adid\\""]',
                '[data-testid="sponsored-story-photo"]',
                '[data-testid*="sponsored"]',
                '[data-store*="\\"is_sponsored\\":true"]',
                '[data-ft*="quick_promotion"]',
                '[data-sigil*="AdStory"]'
              ].join(',');

              // Comment content is untouchable. On the post page the comment
              // thread is its own vscroller child, and a sponsored unit
              // sitting inside the thread used to make cardOf climb up to
              // that child and remove every comment with it - the "comments
              // show for a second and then disappear" report. A marker found
              // inside a comment thread now yields only the marker itself,
              // never the thread.
              function isCommentZone(el) {
                if (!el || !el.getAttribute) return false;
                var a = (el.getAttribute('data-sigil') || '').toLowerCase();
                if (a.indexOf('comment') !== -1) return true;
                a = (el.getAttribute('data-testid') || '').toLowerCase();
                if (a.indexOf('comment') !== -1) return true;
                a = (el.getAttribute('data-pagelet') || '').toLowerCase();
                if (a.indexOf('comment') !== -1) return true;
                a = (el.getAttribute('aria-label') || '').toLowerCase();
                if (a === 'comment' || a === 'comments' ||
                    a.indexOf('comment section') !== -1 ||
                    a.indexOf('comments on') !== -1) return true;
                return false;
              }

              function insideComments(el) {
                var n = el;
                for (var i = 0; i < 10 && n; i++) {
                  if (isCommentZone(n)) return true;
                  n = n.parentElement;
                }
                return false;
              }

              // A vscroller child that holds a comment thread rather than a
              // single story card. The thread container itself carries no
              // comment marker - its comment items do - so it has to be
              // recognised by its content: direct children that are comment
              // items. A feed card may carry one inline comment preview, so
              // the card is only treated as a thread when it is not itself
              // carrying ad identity (data-dcm-id, a sponsored marker).
              function isCommentThreadContainer(el) {
                if (!el || !el.children) return false;
                var kids = el.children;
                var n = kids.length;
                var commentKids = 0;
                for (var i = 0; i < n; i++) {
                  var a = (kids[i].getAttribute && kids[i].getAttribute('data-sigil')) || '';
                  if (a.toLowerCase().indexOf('comment') !== -1) { commentKids++; continue; }
                  var t = (kids[i].getAttribute && kids[i].getAttribute('data-testid')) || '';
                  if (t.toLowerCase().indexOf('comment') !== -1) commentKids++;
                }
                if (commentKids < 1) return false;
                // A feed card shows at most one inline comment preview and
                // carries its own ad identity; a thread wrapper carries
                // several comment items and no ad identity.
                if (commentKids === 1 && n > 3) return false;
                if (el.hasAttribute('data-dcm-id')) return false;
                var tt = (el.getAttribute('data-testid') || '').toLowerCase();
                if (tt.indexOf('sponsored') !== -1) return false;
                if ((el.getAttribute('data-sigil') || '').indexOf('AdStory') !== -1) return false;
                return true;
              }

              // The ad unit containing el, stopping before [stop] and before
              // any comment item. Used when a thread must survive: only the
              // unit between the comments is removed.
              function adUnitOf(el, stop) {
                var n = el;
                while (n && n.parentElement && n.parentElement !== stop) {
                  if (isCommentZone(n.parentElement)) break;
                  n = n.parentElement;
                }
                return n;
              }

              /**
               * The story card containing el: the nearest ancestor that is a
               * direct child of the feed scroller.
               *
               * When the climb passes through a comment thread the marker is
               * inside the comments, not in a feed card; returning the
               * original element hides just the unit itself.
               */
              function cardOf(el) {
                var scroller = el.closest('[data-type="vscroller"]');
                if (!scroller) {
                  // Reels and some surfaces use a plain container. Fall back to
                  // the outermost MContainer under the screen root.
                  scroller = el.closest('[data-mcomponent="MScreen"]');
                }
                if (!scroller) return null;

                var n = el;
                while (n && n.parentElement && n.parentElement !== scroller) {
                  if (isCommentZone(n.parentElement)) return el;
                  n = n.parentElement;
                  if (n === document.body) return null;
                }
                // n is now a direct child of the scroller, or null.
                var card = (n && n.parentElement === scroller) ? n : null;
                if (card && card !== el && isCommentThreadContainer(card)) {
                  // The vscroller child is the comment thread, not the ad.
                  // Remove only the ad unit inside it.
                  return adUnitOf(el, card);
                }
                return card;
              }

              function hide(el) {
                if (!el || el.nodeType !== 1) return;
                if (el.hasAttribute(TAG)) return;
                var t = el.tagName;
                if (t === 'BODY' || t === 'HTML') return;
                // Never remove the scroller itself.
                if (el.getAttribute('data-type') === 'vscroller') return;
                if (el.getAttribute('data-mcomponent') === 'MScreen') return;
                if (isCommentZone(el) || insideComments(el)) return;
                el.setAttribute(TAG, '1');
                el.style.setProperty('display', 'none', 'important');
              }

              function pauseMedia(el) {
                try {
                  var vids = el.querySelectorAll('video');
                  for (var i = 0; i < vids.length; i++) {
                    vids[i].pause();
                    vids[i].removeAttribute('src');
                  }
                } catch (e) {}
              }

              /**
               * Image ads carry none of the video markers - no adid, no
               * is_sponsored, and data-testid is the generic "story-photo-0"
               * that real posts use too. What they do carry is a header label
               * reading exactly "Ad" where an organic post shows a timestamp.
               *
               * Facebook renders every string through
               *   div[data-mcomponent="TextArea"] > .native-text > span
               * so an exact-match on that node is precise. Requiring an exact
               * match, not a substring, is what keeps a post about an "ad
               * campaign" or a page called "Dhaka Ad Agency" untouched.
               */
              var AD_WORDS = [
                'ad', 'ads', 'sponsored', 'বিজ্ঞাপন', 'স্পনসর্ড',
                'publicidad', 'anuncio', 'annonce', 'publicité',
                'gesponsert', 'werbung', 'sponsorizzato', 'reclame',
                'إعلان', 'विज्ञापन', 'iklan', 'quảng cáo', '広告', '广告', '광고'
              ];

              /**
               * Normalise a label before comparing.
               *
               * Facebook appends icon-font characters to the label, so the
               * node reads "Ad\u{F078B}\u{F1677}" rather than "Ad". Those
               * glyphs live in the supplementary Private Use Area, above
               * U+F0000, and are invisible in a screenshot - which is why
               * v3.5.1 compared "ad<glyph><glyph>" against "ad", never
               * matched, and let every image ad through.
               *
               * Strips Private Use Area characters (BMP U+E000-U+F8FF and the
               * supplementary planes), separators, and surrounding space.
               */
              function normLabel(raw) {
                if (!raw) return '';
                var out = '';
                for (var i = 0; i < raw.length; i++) {
                  var c = raw.codePointAt(i);
                  if (c > 0xFFFF) i++;                       // surrogate pair
                  if (c >= 0xE000 && c <= 0xF8FF) continue;  // BMP private use
                  if (c >= 0xF0000) continue;                // supplementary PUA
                  if (c === 0x00B7 || c === 0x2022) continue; // middot, bullet
                  if (c === 0x200E || c === 0x200F) continue; // bidi marks
                  if (c === 0x200B || c === 0xFEFF) continue; // zero width
                  out += String.fromCodePoint(c);
                }
                return out.trim().toLowerCase();
              }

              function isAdLabelNode(el) {
                var raw = (el.textContent || '');
                if (raw.length > 24) return false;
                var t = normLabel(raw);
                if (!t) return false;
                for (var i = 0; i < AD_WORDS.length; i++) {
                  if (t === AD_WORDS[i]) return true;
                }
                return false;
              }

              function runLabelPass() {
                var nodes;
                try { nodes = document.querySelectorAll('.native-text'); }
                catch (e) { return; }

                for (var i = 0; i < nodes.length && i < 2000; i++) {
                  var n = nodes[i];
                  if (!isAdLabelNode(n)) continue;

                  var card = cardOf(n);
                  if (!card || card.hasAttribute(TAG)) continue;

                  // Corroborate before removing. The label must sit in a card
                  // header, so the card should also carry a delivery id or a
                  // sponsor link. Without this a stray "Ad" anywhere on the
                  // page could take a real story with it.
                  var corroborated =
                    card.hasAttribute('data-dcm-id') ||
                    !!card.querySelector('[data-dcm-id]') ||
                    !!card.querySelector('a[href*="/ads/about"]') ||
                    !!card.querySelector('[data-video-tracking*="adid"]') ||
                    !!card.querySelector('[data-testid*="sponsored"]');

                  if (!corroborated) continue;

                  pauseMedia(card);
                  hide(card);
                }
              }

              function run() {
                runLabelPass();

                var hits;
                try { hits = document.querySelectorAll(AD_ATTR); }
                catch (e) { return; }

                for (var i = 0; i < hits.length; i++) {
                  var el = hits[i];
                  if (el.hasAttribute(TAG)) continue;

                  // Extra certainty for the tracking blob: require an ad id or
                  // the sponsored flag, so an ordinary video is never touched.
                  var vt = el.getAttribute('data-video-tracking');
                  if (vt && vt.indexOf('is_sponsored') === -1 &&
                            vt.indexOf('adid') === -1) continue;

                  var card = cardOf(el);
                  if (card) {
                    pauseMedia(card);
                    hide(card);
                  } else {
                    // No identifiable card: remove just the ad unit itself
                    // rather than risk taking something larger.
                    pauseMedia(el);
                    hide(el);
                  }
                }

                // Sponsored posts also carry the "why am I seeing this" link.
                var about;
                try {
                  about = document.querySelectorAll('a[href*="/ads/about"]');
                } catch (e) { about = []; }
                for (var j = 0; j < about.length; j++) {
                  var c2 = cardOf(about[j]);
                  if (c2) { pauseMedia(c2); hide(c2); }
                }
              }

              // Throttled, and never re-entrant.
              var queued = false, last = 0;
              function schedule() {
                if (queued) return;
                queued = true;
                var wait = Math.max(0, 200 - (Date.now() - last));
                setTimeout(function() {
                  queued = false;
                  last = Date.now();
                  (window.requestIdleCallback || window.requestAnimationFrame ||
                   function(f) { setTimeout(f, 0); })(run);
                }, wait);
              }

              var mo = new MutationObserver(function(muts) {
                for (var i = 0; i < muts.length; i++) {
                  if (muts[i].addedNodes && muts[i].addedNodes.length) { schedule(); return; }
                  if (muts[i].type === 'attributes') { schedule(); return; }
                }
              });

              function start() {
                if (!document.body) return;
                // Watch the tracking attribute too: the renderer sets it after
                // the node is inserted, so childList alone misses the ad.
                mo.observe(document.body, {
                  childList: true,
                  subtree: true,
                  attributes: true,
                  attributeFilter: ['data-video-tracking', 'data-testid', 'data-store']
                });
                run();
              }

              if (document.body) start();
              else document.addEventListener('DOMContentLoaded', start, { once: true });

              setTimeout(run, 400);
              setTimeout(run, 1200);
              setTimeout(run, 3000);

              window.__dbMfb = { run: run };
            })();
        """.trimIndent()
    }
}
