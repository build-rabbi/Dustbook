package com.dustbook.app.utils

/**
 * Puts the content we hold into the stored Facebook page.
 *
 * What `m.facebook.com` server-renders is the shell plus grey placeholder
 * blocks; the stories themselves are filled in afterwards by Facebook's own JS
 * over the network. Storing the document therefore stores the skeleton, which
 * is exactly what the user sees offline.
 *
 * The posts and reels are already on disk - [OfflineFeed] holds them with
 * captions and media - they were simply never shown, because the stored
 * document won. This injects them into the feed container of that same
 * document, so the result is Facebook's own page with real content in it
 * rather than a screen of our own design.
 *
 * Safeguards, because a duplicated or mismatched feed would be worse than an
 * empty one:
 *
 *  - injected only into a document served from the offline store, never a live
 *    page, so it cannot appear online
 *  - the container is marked `data-db-offline`; a second pass sees the marker
 *    and stops, so nothing is ever added twice
 *  - the saved cards are appended FIRST, and the document's own copy of the
 *    first page is removed only afterwards, and only when a card's id exactly
 *    matches a saved card's id (the same posts, served twice). Nothing is
 *    ever removed on a guess. If there is nothing to inject, nothing is
 *    removed at all — the page can never be left blank by this script.
 *  - the grey placeholder blocks Facebook's JS would have filled are removed
 *    in the same operation, so the skeleton and the real posts cannot both be
 *    on screen
 */
object OfflineInject {

    /** The saved-id set as JS, quoted safely. */
    private fun List<String>.asJsSet(): String {
        if (isEmpty()) return ""
        return take(2000).joinToString("") { id ->
            val safe = id.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ")
            "SAVED[\"" + safe + "\"]=1;"
        }
    }

    /**
     * @param cardsHtml Facebook's own markup for the saved stories, exactly
     *                  as it was served.
     * @param resumeId  when non-null and starts with "SCROLL:", it is a pixel
     *                  offset to restore on the feed scroller; otherwise it
     *                  is a reel/story id to scroll to.
     * @param savedIds  the ids of the cards in [cardsHtml], as produced by
     *                  the capture (e.g. "data-tracking-duration-id:123").
     *                  The document's own cards are removed only when their
     *                  id is in this set — i.e. only exact duplicates.
     */
    fun script(cardsHtml: String, resumeId: String? = null, savedIds: List<String> = emptyList()): String {
        // The HTML parser ends a <script> at the first "</script>" it sees,
        // wherever that appears — including inside a JS string. Facebook's
        // stored markup contains inline scripts, so one such card truncated
        // the whole block and every card after it was dropped on the floor as
        // raw text. That is why fifty saved posts showed as a handful.
        //
        // Breaking the sequence is the standard remedy: the parser no longer
        // recognises it, and the JS string still evaluates to the original
        // text because "<" + "/script>" is just concatenation at runtime.
        val cards = cardsHtml
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
            .replace("</script", "</scr` + `ipt")

        val main = """
        (function(){
          if (window.__dbOfflineInject) return;
          window.__dbOfflineInject = true;

          var CARDS = `$cards`;
          if (!CARDS) return;

          var SAVED = {};
          ${savedIds.asJsSet()}

          function feedContainer() {
            var sc = document.querySelector('[data-type="vscroller"]');
            if (sc) return sc;
            var alt = document.querySelector('[data-mcomponent="MScreen"]');
            return alt || document.body;
          }

          /** An empty element with no media — a grey skeleton block. */
          function isPlaceholder(el) {
            if (!el || el.nodeType !== 1) return false;
            if (el.querySelector('img,video')) return false;
            var t = (el.innerText || el.textContent || '').trim();
            return t.length === 0;
          }

          /**
           * The id of a story card, using exactly the same markers and the
           * same order as the capture script, so the id matches the one the
           * app stored. Returns '' for anything that is not a story card
           * (composer, story tray, tab bar, headers, dividers).
           */
          function cardIdOf(el) {
            if (!el || el.nodeType !== 1) return '';
            var keys = ['data-video-id', 'data-successful-render-id',
                        'data-tracking-duration-id', 'data-comp-id'];
            for (var i = 0; i < keys.length; i++) {
              var v = el.getAttribute && el.getAttribute(keys[i]);
              if (v) return keys[i] + ':' + v;
            }
            var a = el.querySelector &&
                    el.querySelector('a[href*="/posts/"],a[href*="/reel/"],' +
                                     'a[href*="story_fbid"],a[href*="/videos/"]');
            if (a) return 'href:' + a.getAttribute('href');
            return '';
          }

          function inject() {
            try {
              var box = feedContainer();
              if (!box || box.getAttribute('data-db-offline')) return;
              box.setAttribute('data-db-offline', '1');

              // Append the saved cards FIRST. If anything below fails, the
              // page still shows the document's own content — the offline
              // page can never be left blank by this script.
              var holder = document.createElement('div');
              holder.setAttribute('data-db-cards', '1');
              holder.innerHTML = CARDS;
              box.appendChild(holder);

              // The stored document carries its own copy of the first page —
              // the same posts the capture saved. Remove only the cards whose
              // id is exactly one of the saved ids (an exact duplicate of
              // what was just appended), plus pure skeleton placeholders.
              // Everything else — composer, story tray, tabs, headers,
              // dividers, and any card we do not hold — is left untouched.
              var kids = Array.prototype.slice.call(box.children);
              for (var i = 0; i < kids.length; i++) {
                var k = kids[i];
                if (k === holder) continue;
                if (isPlaceholder(k)) {
                  try { k.parentNode.removeChild(k); } catch (e) {}
                  continue;
                }
                var id = cardIdOf(k);
                if (id && SAVED[id]) {
                  try { k.parentNode.removeChild(k); } catch (e) {}
                }
              }

              // Facebook's feed scroller is a fixed-height window that hides
              // what overflows it; the saved cards appended beyond it were
              // unreachable — the reported handful of posts instead of the
              // hundreds that were saved. Un-clip the page so every saved
              // card is reachable by scrolling. Heights are not touched:
              // the page's own layout (fixed header, full-screen reels)
              // keeps its sizes.
              document.documentElement.style.overflowY = 'auto';
              document.body.style.overflowY = 'auto';
              if (box !== document.body) {
                box.style.overflowY = 'auto';
              }

              doResume(box);
            } catch (e) {
              // Never let a failure blank the page.
            }
          }

          function doResume(box) {
            if (!window.__dbResumeId || !window.__dbResumeId) return;
            var id = window.__dbResumeId;
            if (id.indexOf('SCROLL:') === 0) {
              var px = parseInt(id.slice(7), 10);
              if (px > 0) {
                // Feed scroll offset. The scroller height may change as
                // images load, so try several times.
                tryAt([300, 800, 1500, 2500], function(){
                  var c = feedContainer();
                  if (c) c.scrollTop = px;
                });
              }
              return;
            }
            // Reel/story: scroll to the card with matching id. The card's
            // position changes as its poster loads, then again as the video
            // frame decodes, so try multiple times to catch the settled layout.
            scrollToCard(id, [300, 800, 1500, 2500]);
          }

          function scrollToCard(id, delays) {
            tryAt(delays, function(){
              var box = feedContainer();
              if (!box) return;
              var cards = box.querySelectorAll(
                '[data-video-id],[data-story-id],[data-offline-id]');
              for (var i = 0; i < cards.length; i++) {
                var c = cards[i];
                var cid = c.getAttribute('data-video-id') ||
                          c.getAttribute('data-story-id') ||
                          c.getAttribute('data-offline-id');
                if (cid === id) {
                  var container = c.closest('[data-type="vscroller"]');
                  if (container) {
                    var off = c.offsetTop - container.offsetTop;
                    container.scrollTop = Math.max(0,
                      off - (container.clientHeight / 3));
                  } else {
                    c.scrollIntoView({block: 'center', behavior: 'instant'});
                  }
                  return;
                }
              }
            });
          }

          function tryAt(delays, fn) {
            // Call fn now, then at each delay, but only if the page is
            // still showing offline cards (has not navigated away).
            fn();
            for (var i = 0; i < delays.length; i++) {
              (function(d){
                setTimeout(function(){
                  if (document.querySelector('[data-db-cards]')) fn();
                }, d);
              })(delays[i]);
            }
          }

          inject();
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', inject);
          }
          setTimeout(inject, 600);
          setTimeout(inject, 1800);

          // Track position changes so the next session resumes here.
          var __lastReport = 0;
          function reportCurrent() {
            var now = Date.now();
            if (now - __lastReport < 2000) return;
            __lastReport = now;
            var box = feedContainer();
            if (!box) return;
            var mid = box.clientHeight / 2;
            var cards = box.querySelectorAll('[data-video-id],[data-story-id]');
            for (var i = 0; i < cards.length; i++) {
              var r = cards[i].getBoundingClientRect();
              if (r.top < mid && r.bottom > mid) {
                var vid = cards[i].getAttribute('data-video-id') ||
                          cards[i].getAttribute('data-story-id');
                if (vid && window.FBPro && FBPro.reportPosition) {
                  try { FBPro.reportPosition('reel', vid); } catch (e) {}
                }
                return;
              }
            }
            if (box.scrollTop > 0 && window.FBPro && FBPro.reportPosition) {
              try { FBPro.reportPosition('feed', String(box.scrollTop)); } catch(e){}
            }
          }
          var scroller = feedContainer();
          if (scroller) {
            scroller.addEventListener('scroll', function(){
              if (window.__dbResumeTimer) clearTimeout(window.__dbResumeTimer);
              window.__dbResumeTimer = setTimeout(reportCurrent, 600);
            });
          }
        })();
        """.trimIndent()

        // Set the resume target outside the template so tests can still eval
        // the raw Kotlin source without encountering an unresolved Kotlin
        // template variable.
        if (resumeId != null) {
            return "window.__dbResumeId=" + "\"$resumeId\";\n" + main
        }
        return main
    }
}
