package com.dustbook.app.utils

/**
 * Saves Facebook's own markup, card by card.
 *
 * Earlier versions captured *fields* - author, caption, reaction counts - and
 * rebuilt a card from them. That approach cannot ever be complete: a rebuilt
 * card only has what was thought to capture, which is why Like, Comment and
 * Share were missing, and why the gaps kept being filled with things the app
 * drew itself.
 *
 * So nothing is rebuilt. Each story's real `outerHTML` is stored - Facebook's
 * own nodes, their own buttons, their own layout - and put back into the real
 * container when offline. Nothing can be missing, because it is the original
 * markup.
 *
 * Size was the reason this was rejected once, but that was for the whole
 * document. A single card is a few kilobytes; fifty of them is well under a
 * megabyte.
 *
 * The script only reads the DOM. It never mutates it, so it cannot interfere
 * with the ad remover.
 */
object OfflineCapture {

    /** The known-id set as JS, quoted safely. */
    private fun List<String>.asJsSet(): String {
        if (isEmpty()) return ""
        return take(1000).joinToString("") { id ->
            val safe = id.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ")
            "KNOWN[\"" + safe + "\"]=1;"
        }
    }

    /** A card larger than this is a container, not a story. */
    private const val MAX_CARD_CHARS = 120_000

    /**
     * @param reelTarget how many items to keep per section.
     * @param syncMode   true in the background sync WebView, where scrolling
     *                   the page ourselves is allowed.
     */
    /**
     * @param knownIds identities already in the store. Cards matching one are
     *                 skipped, so each pass reaches content the user has not
     *                 seen instead of re-capturing what is already held.
     */
    fun script(
        reelTarget: Int,
        syncMode: Boolean = false,
        knownIds: List<String> = emptyList()
    ): String = """
        (function(){
          if (window.__dbCapture) return;
          window.__dbCapture = true;

          var TARGET = $reelTarget;
          var KNOWN = {};
          ${knownIds.asJsSet()}
          var SYNC = ${if (syncMode) "true" else "false"};
          var MAX = $MAX_CARD_CHARS;
          var bridge = window.FBPro;
          if (!bridge || !bridge.onOfflineItems) return;

          function section() {
            var p = location.pathname.replace(/^\/+|\/+${'$'}/g, '').split('/')[0];
            if (p === 'stories' || p === 'story') return 'stories';
            if (p === 'reel' || p === 'reels' || p === 'watch') return 'reels';
            if (p === '' || p === 'home.php') return 'feed';
            return null;
          }

          function fbMedia(u) {
            if (!u || u.indexOf('https://') !== 0) return null;
            if (u.indexOf('fbcdn.net') < 0 && u.indexOf('fbsbx.com') < 0) return null;
            return u;
          }

          /** Visible text, never the contents of <style> or <script>. */
          function textIn(el) {
            if (!el) return '';
            if (el.tagName === 'STYLE' || el.tagName === 'SCRIPT') return '';
            if (typeof el.innerText === 'string' && el.innerText) return el.innerText;
            var out = '', kids = el.childNodes || [];
            for (var i = 0; i < kids.length; i++) {
              var n = kids[i];
              if (n.nodeType === 3) out += n.nodeValue;
              else if (n.nodeType === 1) out += ' ' + textIn(n);
            }
            return out;
          }

          /**
           * Every media URL a card references, so the app can download them.
           * Covers plain sources, srcset and CSS backgrounds.
           */
          function mediaIn(card) {
            var out = [], seen = {};
            function add(u) {
              u = fbMedia(u);
              if (!u || seen[u]) return;
              seen[u] = 1;
              out.push(u);
            }
            var i, n;
            var imgs = card.querySelectorAll('img');
            for (i = 0; i < imgs.length; i++) {
              add(imgs[i].currentSrc || imgs[i].src);
              var ss = imgs[i].getAttribute('srcset');
              if (ss) {
                var parts = ss.split(',');
                for (n = 0; n < parts.length; n++) {
                  add(parts[n].trim().split(/\s+/)[0]);
                }
              }
            }
            var vids = card.querySelectorAll('video, source');
            for (i = 0; i < vids.length; i++) {
              var vsrc = vids[i].currentSrc || vids[i].src;
              if (vsrc && vsrc.indexOf('blob:') !== 0) add(vsrc);
              var ds = vids[i].getAttribute('data-src');
              if (ds && ds.indexOf('blob:') !== 0) add(ds);
            }
            // The renderer puts the real file on the MVideo wrapper, which is
            // the only playable URL when the element carries a blob:.
            var mv = card.querySelectorAll('[data-video-url]');
            for (i = 0; i < mv.length; i++) add(mv[i].getAttribute('data-video-url'));
            if (card.getAttribute && card.getAttribute('data-video-url')) {
              add(card.getAttribute('data-video-url'));
            }
            var styled = card.querySelectorAll('[style]');
            for (i = 0; i < styled.length; i++) {
              var st = styled[i].getAttribute('style') || '';
              if (st.indexOf('url(') < 0) continue;
              var re = /url\(\s*["']?(https:[^"')\s]+)/g, m;
              while ((m = re.exec(st)) !== null) add(m[1]);
            }
            return out;
          }

          /**
           * Rewrite what the stored markup points at.
           *
           * A card is replayed at a different URL from the one it was captured
           * on, so root-relative sources would resolve somewhere else and miss
           * the cache. Absolute URLs are left exactly as they are, which is
           * what lets the offline store answer them.
           */
          /**
           * Remove every <div> carrying attr="value", including its children.
           *
           * A lazy regex cannot do this: it ends at the first closing tag,
           * which for a wrapper element is the wrong one, and the leftover
           * </div> corrupts the markup. Counting opens and closes from the
           * match forwards finds the real end of the element.
           */
          function removeTag(html, attr, value) {
            // The quote characters are built from char codes so this stays
            // readable inside a Kotlin raw string, where a backslash reaches
            // JavaScript untouched and an escaped quote would not parse.
            var q = String.fromCharCode(34) + String.fromCharCode(39);
            var open = new RegExp(
              '<div[^>]*\\b' + attr + '\\s*=\\s*[' + q + ']' +
              value + '[' + q + '][^>]*>', 'i');
            // Bounded, so malformed markup cannot spin here.
            for (var guard = 0; guard < 200; guard++) {
              var m = open.exec(html);
              if (!m) break;
              var start = m.index;
              var i = start + m[0].length;
              var depth = 1;
              var tag = /<\/?div\b[^>]*>/gi;
              tag.lastIndex = i;
              var t;
              while (depth > 0 && (t = tag.exec(html)) !== null) {
                if (t[0].charAt(1) === '/') depth--; else depth++;
                i = tag.lastIndex;
              }
              if (depth > 0) {
                // Unbalanced markup: drop from the opening tag to the end
                // rather than leaving a half-removed element behind.
                html = html.slice(0, start);
                break;
              }
              html = html.slice(0, start) + html.slice(i);
            }
            return html;
          }

          function markupOf(card) {
            var html = card.outerHTML || '';
            if (!html || html.length > MAX) return '';
            html = html
              .replace(/src="\//g, 'src="https://m.facebook.com/')
              .replace(/href="\//g, 'href="https://m.facebook.com/');
            // Facebook puts the real MP4 URL on data-video-url; the
            // <video> tag itself often has no src (set by JS) or a dead
            // blob:. Rewrite it so the stored markup actually plays
            // without Facebook's own runtime.
            // The attribute is usually on a child wrapper (MVideo), not on
            // the card root — the URL collector above already searches for it
            // that way. Reading only the root meant a reel's <video> kept its
            // dead blob: src, so offline it showed the poster and a play
            // button that did nothing.
            var dv = (card.getAttribute && card.getAttribute('data-video-url'));
            if (!dv && card.querySelector) {
              var holder = card.querySelector('[data-video-url]');
              if (holder) dv = holder.getAttribute('data-video-url');
            }
            if (dv && dv.indexOf('https://') === 0) {
              // Strip <source> children so the browser does not try
              // their dead URLs before the cached video src.
              html = html.replace(/<source\b[^>]*>/gi, '');
              // Only patch existing <video> tags. Never inject a new
              // <video> element — raw elements break Facebook's flex
              // layout and collapse the reels section.
              html = html.replace(
                /<video\b([^>]*)>/gi,
                function(all, attrs) {
                  var clean = attrs.replace(
                    /\s*src\s*=\s*["'][^"']*["']/gi, '');
                  return '<video' + clean +
                    ' src="' + dv + '" preload="auto">';
                });
            }
            // Strip Facebook's audio/video overlay elements.
            //
            // These "Tap to unmute" labels are dismissed by Facebook's own
            // JS on interaction. Offline that JS never runs, so the label
            // sits on top of the video for good.
            //
            // The previous attempt looped a lazy regex ending at the first
            // </div>. Facebook's overlay wraps nested divs, so that match
            // stopped inside the overlay and left its trailing </div>
            // behind. The browser then re-balanced the broken markup and
            // the label came back — which is why the recursion never
            // finished the job. Depth has to be counted instead.
            html = removeTag(html, 'data-sigil', 'm-video-overlay');
            return html;
          }

          /** A stable id, so the same story is not stored twice. */
          function idOf(card) {
            var keys = ['data-video-id', 'data-successful-render-id',
                        'data-tracking-duration-id', 'data-comp-id'];
            for (var i = 0; i < keys.length; i++) {
              var v = card.getAttribute && card.getAttribute(keys[i]);
              if (v) return keys[i] + ':' + v;
            }
            var a = card.querySelector &&
                    card.querySelector('a[href*="/posts/"],a[href*="/reel/"],' +
                                       'a[href*="story_fbid"],a[href*="/videos/"]');
            if (a) return 'href:' + a.getAttribute('href');
            // Fall back to a hash of the text, which is stable enough to
            // recognise the same story on a later pass.
            var t = textIn(card).replace(/\s+/g, ' ').trim().slice(0, 120);
            return t ? 'text:' + t : '';
          }

          // Every story on the mobile renderer is a direct child of the feed
          // scroller. That is a lookup, not a guess - the same fact the ad
          // remover relies on.
          function cards() {
            var scroller = document.querySelector('[data-type="vscroller"]');
            if (scroller && scroller.children.length) {
              return Array.prototype.slice.call(scroller.children);
            }
            return Array.prototype.slice.call(
              document.querySelectorAll('[data-tracking-duration-id]'));
          }

          /**
           * Not everything in the scroller is a story. The composer, the story
           * tray and navigation chrome are children of the same container.
           */
          function isChrome(c) {
            if (!c.querySelector) return true;
            // Composer box
            if (c.querySelector('input,textarea,[role="textbox"]')) return true;
            // Facebook bottom tab bar — aria-labels from device captures
            var label = (c.getAttribute('aria-label') || '').toLowerCase();
            if (/^(home|reels|watch|notifications|menu|profile|search|create|messages|marketplace|friends|groups|gaming)$/i.test(label)) return true;
            // Facebook's own bottom nav container
            if (c.getAttribute('data-mcomponent') === 'MContainer') {
              var al = (c.getAttribute('aria-label') || '').toLowerCase();
              if (al) return true;
            }
            // Section headers / dividers / "See more"
            var low = textIn(c).replace(/\s+/g, ' ').trim().toLowerCase();
            if (low.indexOf("what's on your mind") >= 0) return true;
            if (low.indexOf('what is on your mind') >= 0) return true;
            if (low.indexOf('create room') >= 0) return true;
            if (/^(\s*home\s*|\s*reels\s*|\s*notifications\s*|\s*menu\s*|\s*profile\s*)\s*$/.test(low)) return true;
            if (/^(see more|see all|view more|show more|load more)$/i.test(low)) return true;
            // Empty div with no text and no media = spacer/divider/chrome
            var imgs = c.querySelectorAll ? c.querySelectorAll('img') : [];
            var vids = c.querySelectorAll ? c.querySelectorAll('video') : [];
            if (imgs.length === 0 && vids.length === 0) {
              var t = (c.innerText || c.textContent || '').replace(/\s+/g, '').trim();
              if (t.length < 6) return true;
            }
            return false;
          }

          function collectFeed() {
            var out = [];
            var list = cards();
            for (var i = 0; i < list.length; i++) {
              var c = list[i];
              if (isChrome(c)) continue;

              // Already stored: skip it entirely rather than capturing it
              // again. This is what makes each pass reach new content.
              var cid = idOf(c);
              if (cid && KNOWN[cid]) continue;

              var media = mediaIn(c);
              var text = textIn(c).replace(/\s+/g, ' ').trim();
              // A story has either media or something to read. Anything with
              // neither is a spacer or a divider.
              if (!media.length && text.length < 12) continue;

              // Advertising is never saved: a sponsored card carries the
              // label in its text or its aria-label.
              var al = (c.getAttribute && c.getAttribute('aria-label') || '').toLowerCase();
              if (text.toLowerCase().indexOf('sponsored') >= 0 ||
                  al.indexOf('sponsored') >= 0) continue;

              var html = markupOf(c);
              if (!html) continue;

              out.push({ id: cid, h: html, m: media });
              if (out.length >= TARGET + 20) break;
            }
            return out;
          }

          /**
           * A story is one full screen, not a list, so the scroller walk finds
           * nothing there. Take the screen itself.
           */
          function collectStory() {
            var root = document.querySelector('[data-mcomponent="MScreen"]') ||
                       document.getElementById('screen-root');
            if (!root) return [];
            var media = mediaIn(root);
            if (!media.length) return [];
            var html = markupOf(root);
            if (!html) return [];
            var sid = 'story:' + location.pathname;
            if (KNOWN[sid]) return [];
            return [{ id: sid, h: html, m: media }];
          }

          function collect() {
            return section() === 'stories' ? collectStory() : collectFeed();
          }

          var lastCount = 0;
          var pageSent = false;

          /**
           * Hand the document to the app so it can be stored.
           *
           * Facebook answers m.facebook.com with HTTP 400 to anything that is
           * not a browser, so the app cannot fetch these pages itself - this
           * WebView is the only place they exist. Sent once per screen.
           */
          function sendPage() {
            if (pageSent) return;
            var s = section();
            if (!s) return;
            if (!bridge.onOfflinePage) return;
            var html = document.documentElement.outerHTML;
            // Too small to be a real screen: still loading.
            if (!html || html.length < 20000) return;
            pageSent = true;
            try { bridge.onOfflinePage(s, html); } catch (e) { pageSent = false; }
          }

          function report(done) {
            var s = section();
            if (!s) return 0;
            var items = collect();
            if (!items.length) return 0;
            try {
              bridge.onOfflineItems(s, JSON.stringify(items), !!done);
            } catch (e) {
              // A payload too large for the bridge: send it in halves rather
              // than losing the pass entirely.
              try {
                var half = Math.ceil(items.length / 2);
                bridge.onOfflineItems(s, JSON.stringify(items.slice(0, half)), false);
                bridge.onOfflineItems(s, JSON.stringify(items.slice(half)), !!done);
              } catch (e2) { return 0; }
            }
            return items.length;
          }

          if (!SYNC) {
            // Normal browsing: follow the user, never drive the page. Whatever
            // they scroll past is what gets saved.
            var t = null;
            function schedule() {
              if (t) clearTimeout(t);
              t = setTimeout(function(){
                report(false);
                // Store the screen the user is actually on. The app cannot
                // fetch it over HTTP - Facebook returns 400 - so this is the
                // only opportunity to capture it.
                sendPage();
              }, 1200);
            }
            window.addEventListener('scroll', schedule, true);
            window.addEventListener('load', schedule);
            document.addEventListener('visibilitychange', function(){
              if (document.visibilityState === 'hidden') report(true);
            });
            var lastPath = location.pathname;
            setInterval(function(){
              if (location.pathname !== lastPath) {
                lastPath = location.pathname;
                pageSent = false;   // a different screen: store that one too
                schedule();
              }
            }, 1000);
            schedule();
            return;
          }

          // Background sync: scroll the offscreen page ourselves, so the user
          // does not have to watch fifty reels for fifty reels to be saved.
          var stalls = 0, passes = 0;
          var timer = setInterval(function(){
            passes++;
            // Store the page itself on the second tick, by which point the
            // renderer has filled in the feed rather than a skeleton.
            if (passes >= 2) sendPage();
            var n = report(false);
            // On stories every screen reports at most one card, and zero once
            // it is already held, so comparing counts would stall instantly.
            // Progress there is measured by whether anything new was seen.
            if (section() === 'stories') {
              if (n === 0) stalls++; else stalls = 0;
            } else {
              if (n <= lastCount) stalls++; else stalls = 0;
              lastCount = n;
            }

            if (n >= TARGET || stalls >= 6 || passes > 60) {
              clearInterval(timer);
              report(true);
              try { if (bridge.onSyncDone) bridge.onSyncDone(n); } catch (e) {}
              return;
            }

            try {
              if (section() === 'stories') {
                // A story is one screen, not a list, so scrolling achieves
                // nothing - the tray has to be advanced. Press whatever
                // Facebook uses to move to the next story, so the whole tray
                // is saved without the user opening any of it.
                var next = document.querySelector(
                  '[aria-label="Next card" i],[aria-label="Next story" i],' +
                  '[aria-label="Next" i]');
                if (next) {
                  next.click();
                } else {
                  // No control found: tap the right-hand side, which is how
                  // the viewer advances to the next story.
                  var w = window.innerWidth || 360;
                  var h = window.innerHeight || 640;
                  var el = document.elementFromPoint(w * 0.9, h * 0.5);
                  if (el) el.click();
                }
              } else {
                var sc = document.querySelector('[data-type="vscroller"]');
                if (sc && sc.scrollHeight > sc.clientHeight) {
                  sc.scrollTop = sc.scrollHeight;
                } else {
                  window.scrollTo(0, document.body.scrollHeight);
                }
              }
            } catch (e) {}
          }, 1800);
        })();
    """.trimIndent()
}
