package com.dustbook.app.utils

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Ad & tracker blocking engine.
 *
 * Layer 1  network   - 97k domain blocklist (AdGuard + EasyList + EasyPrivacy
 *                      + uBlock Origin) via [BlockList], with a hard allowlist
 *                      so Facebook's own infrastructure is never touched.
 * Layer 2  GraphQL   - sponsored posts are removed from the API response
 *                      before React ever renders them. This is how uBlock
 *                      Origin actually kills Facebook feed ads; CSS alone
 *                      cannot, because the class names are randomised.
 * Layer 3  cosmetic  - precise selectors + an "ads/about" link probe, which is
 *                      the one stable marker every sponsored post carries.
 *
 * Never uses substring class matching such as [class*="ad"]; that matched
 * header, shadow, loading, thread and badge and destroyed the UI.
 */
object AdBlocker {

    @Volatile var enabled: Boolean = true
    @Volatile var cosmeticEnabled: Boolean = true

    fun shouldBlockRequest(request: WebResourceRequest): Boolean {
        if (!enabled) return false
        val uri = request.url
        val host = BlockList.normalizeHost(uri.host)
        if (host.isEmpty()) return false

        if (BlockList.isAllowed(host)) {
            // Still block Facebook's own ad endpoints.
            return host == "an.facebook.com" ||
                (uri.path ?: "").startsWith("/adnw_")
        }
        if (BlockList.blocksHost(host)) return true
        return BlockList.blocksPath((uri.path ?: "").lowercase(Locale.ROOT))
    }

    fun createEmptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )

    /**
     * Injected at document start, before Facebook's own scripts run.
     *
     * Hooks XMLHttpRequest and fetch so every /api/graphql response is
     * filtered: any object carrying `sponsored_data`, an `ad_id`, or a
     * `__typename` of an ad story is deleted from the JSON. The feed then
     * renders as if the ad was never served - no gap, no placeholder, and
     * nothing for Facebook's re-render logic to restore.
     */
    fun getEarlyScript(blockAds: Boolean, blockAppPromos: Boolean): String {
        return """
            (function() {
              'use strict';
              if (window.__fbproEarly) { window.__fbproEarly.set($blockAds, $blockAppPromos); return; }

              var BLOCK_ADS = $blockAds;
              var BLOCK_PROMOS = $blockAppPromos;

              /* Hard CSS kill for app-install notices, injected at document
                 start so the banner never paints even once. Uses a style
                 element written with single quotes to avoid the quoting bug
                 that silently killed the old style layer. */
              (function() {
                if (!BLOCK_PROMOS) return;
                function addCss() {
                  if (document.getElementById('fbpro-hardblock')) return;
                  var head = document.head || document.documentElement;
                  if (!head) return;
                  var st = document.createElement('style');
                  st.id = 'fbpro-hardblock';
                  st.textContent = [
                    '#header-notices',
                    'div[id^=\'header-notice\']',
                    '#mobile_app_install_banner',
                    '#appManifestBanner',
                    '#MComposerAppInstallBanner',
                    'div[id^=\'m_appinstall\']',
                    '[data-testid*=\'app_download\']',
                    '[data-testid*=\'install_app\']',
                    '[data-testid*=\'app_upsell\']',
                    '[data-nt=\'FB:APP_INSTALL\']',
                    'a[href*=\'play.google.com/store\']',
                    'a[href*=\'apps.apple.com\']',
                    'a[href*=\'itunes.apple.com\']',
                    'a[href*=\'utm_source=mobile_web\']',
                    /* relative install links on m.facebook.com */
                    'a[href^=\'/mobile/\']',
                    'a[href*=\'/mobile/download\']',
                    'a[href*=\'entry=login\'][href*=\'mobile\']',
                    'a[href*=\'?ref=dbl\']',
                    '[data-sigil*=\'mUpsellBanner\']',
                    '[data-sigil*=\'upsell\']',
                    'a[href^=\'market://\']',
                    'a[href^=\'itms-apps://\']',
                    /* app deep links and the Lite upsell */
                    'a[href^=\'fb://\']',
                    'a[href^=\'fb-messenger://\']',
                    'a[href^=\'intent://\']',
                    'a[href^=\'/lite/\']',
                    'a[href*=\'/lite/?entry\']',
                    'a[href*=\'entry=bookmark\']',
                    'a[href*=\'ref=bookmark\'][href*=\'mobile\']',
                    '[data-sigil*=\'m-promo-jewel\']',
                    '[data-sigil*=\'promo-jewel\']',
                    '#bottom_bar a[href*=\'mobile\']',
                    /* the full-width "Open app" bar pinned to the bottom */
                    '#bottom_action_bar',
                    'div[id*=\'bottom_action\']',
                    'div[data-sigil*=\'bottom_action_bar\']',
                    '#m_bottom_banner', '#mobile_bottom_banner',
                    'a[href*=\'entry_point=bottom\']',
                    'a[href*=\'/lite/\']',
                    '.smartbanner', '.smart-banner',
                    '.mobile-app-banner', '.app-install-banner',
                    '.app-download-banner', '.get-app-banner'
                  ].join(',') + '{display:none !important;height:0 !important;' +
                     'overflow:hidden !important;}';
                  head.appendChild(st);
                }
                addCss();
                document.addEventListener('DOMContentLoaded', addCss, {once: true});
                // Facebook can replace <head>; re-add if our style disappears.
                setInterval(addCss, 1500);
              })();


              // ---- GraphQL sponsored-content stripper -------------------------
              var AD_TYPES = [
                'MarketplaceFeedAdStory','SponsoredStory','AdCreative',
                'VideoHomeFeedUnitSectionComponent'
              ];

              function isAdNode(o) {
                if (!o || typeof o !== 'object') return false;
                if (o.sponsored_data) return true;
                if (o.__typename && AD_TYPES.indexOf(o.__typename) !== -1) return true;
                if (o.role === 'SEARCH_ADS') return true;
                if (o.is_sponsored === true) return true;
                if (o.ad_id) return true;
                return false;
              }

              // An array entry counts as an ad if the entry itself is an ad OR
              // its edge wrapper holds one. Without this the wrapper survives
              // as an empty {} and React renders a blank gap in the feed.
              function isAdEntry(o) {
                if (isAdNode(o)) return true;
                if (!o || typeof o !== 'object') return false;
                if (isAdNode(o.node)) return true;
                if (isAdNode(o.story)) return true;
                if (o.node && isAdNode(o.node.story)) return true;
                if (o.relay_rendering_strategy &&
                    o.relay_rendering_strategy.view_model &&
                    isAdNode(o.relay_rendering_strategy.view_model.story)) return true;
                return false;
              }

              // Depth-limited walk; removes ad entries from arrays and nulls
              // ad objects elsewhere. Bounded so it can never hang the page.
              function prune(obj, depth) {
                if (!obj || typeof obj !== 'object' || depth > 14) return obj;
                if (Array.isArray(obj)) {
                  for (var i = obj.length - 1; i >= 0; i--) {
                    if (isAdEntry(obj[i])) { obj.splice(i, 1); continue; }
                    prune(obj[i], depth + 1);
                  }
                  return obj;
                }
                for (var k in obj) {
                  if (!Object.prototype.hasOwnProperty.call(obj, k)) continue;
                  var v = obj[k];
                  if (v && typeof v === 'object') {
                    if (isAdNode(v)) { try { delete obj[k]; } catch (e) { obj[k] = null; } continue; }
                    prune(v, depth + 1);
                  }
                }
                return obj;
              }

              // Facebook streams GraphQL as newline-delimited JSON objects.
              function filterPayload(text) {
                if (!BLOCK_ADS || !text || text.length > 6000000) return text;
                if (text.indexOf('sponsored_data') === -1 &&
                    text.indexOf('MarketplaceFeedAdStory') === -1 &&
                    text.indexOf('SEARCH_ADS') === -1 &&
                    text.indexOf('"ad_id"') === -1) return text;
                var lines = text.split('\n');
                var changed = false;
                for (var i = 0; i < lines.length; i++) {
                  var s = lines[i];
                  if (!s || s.charAt(0) !== '{') continue;
                  try {
                    var o = JSON.parse(s);
                    prune(o, 0);
                    lines[i] = JSON.stringify(o);
                    changed = true;
                  } catch (e) {}
                }
                return changed ? lines.join('\n') : text;
              }

              // m.facebook.com does not use /api/graphql; it posts to
              // /api/graphqlbatch/ and several /ajax/ endpoints, so sponsored
              // posts were never filtered on the mobile site.
              function isGraphql(url) {
                if (typeof url !== 'string') return false;
                return url.indexOf('/api/graphql') !== -1 ||
                       url.indexOf('/api/graphqlbatch') !== -1 ||
                       url.indexOf('/graphql') !== -1 ||
                       url.indexOf('/ajax/bootloader-endpoint') !== -1 ||
                       url.indexOf('/feed/story') !== -1 ||
                       url.indexOf('/ajax/pagelet/generic.php/NewsFeed') !== -1;
              }

              // --- XHR hook ---
              // Only ever touch text responses. Facebook loads some payloads
              // with responseType 'arraybuffer' or 'json'; reading
              // responseText on those throws InvalidStateError, and the old
              // hook swallowed that and handed back an empty string. The page
              // then saw an empty response, the bootstrap never completed, and
              // the app sat on the splash screen before falling back to a
              // blank shell.
              var XHR = XMLHttpRequest.prototype;
              var _open = XHR.open, _send = XHR.send;
              var textDesc = Object.getOwnPropertyDescriptor(XHR, 'responseText');

              XHR.open = function(m, u) {
                this.__fbproUrl = u;
                return _open.apply(this, arguments);
              };

              XHR.send = function() {
                var xhr = this;
                if (BLOCK_ADS && isGraphql(xhr.__fbproUrl) && textDesc && textDesc.get) {
                  try {
                    var cache = null, cacheSrc = null;
                    Object.defineProperty(xhr, 'responseText', {
                      configurable: true,
                      get: function() {
                        // Defer to the native getter for non-text responses so
                        // it can raise the correct error itself.
                        var rt = xhr.responseType;
                        if (rt !== '' && rt !== 'text') return textDesc.get.call(xhr);

                        var raw = textDesc.get.call(xhr);
                        if (raw === cacheSrc) return cache;   // filter once
                        cacheSrc = raw;
                        try { cache = filterPayload(raw); } catch (e) { cache = raw; }
                        return cache;
                      }
                    });
                  } catch (e) { /* leave the response untouched */ }
                }
                return _send.apply(this, arguments);
              };

              // --- fetch hook ---
              // res.clone() buffers a second full copy of every GraphQL
              // response and the streamed body has to be fully read before
              // anything is handed back, which stalled the feed badly on a
              // slow connection. Read the original body once instead, and
              // only rebuild the response when something was actually
              // removed. Streaming responses are left completely alone.
              var _fetch = window.fetch;
              if (_fetch) {
                window.fetch = function(input, init) {
                  var p = _fetch.apply(this, arguments);
                  if (!BLOCK_ADS) return p;

                  var url = (typeof input === 'string') ? input : (input && input.url);
                  if (!isGraphql(url)) return p;

                  return p.then(function(res) {
                    if (!res || !res.ok || res.bodyUsed) return res;

                    // Skip anything too large to hold in memory safely.
                    var len = parseInt(res.headers.get('content-length') || '0', 10);
                    if (len > 4000000) return res;

                    return res.text().then(function(t) {
                      var f;
                      try { f = filterPayload(t); } catch (e) { f = t; }
                      return new Response(f, {
                        status: res.status,
                        statusText: res.statusText,
                        headers: res.headers
                      });
                    }).catch(function() { return res; });
                  }).catch(function(e) { throw e; });
                };
              }

              // ---- kill app-store navigation at the source --------------------
              if (BLOCK_PROMOS) {
                var BAD = /play\.google\.com\/store|apps\.apple\.com|itunes\.apple\.com|^market:|^fb:|^intent:|^itms-apps:|\/mobile\/download|messenger\.com\/download/i;
                // Block programmatic redirects to the store.
                var _assign = window.location.assign.bind(window.location);
                try {
                  window.location.assign = function(u) { if (BAD.test(String(u))) return; _assign(u); };
                } catch (e) {}
                // Swallow clicks on store links during the capture phase.
                document.addEventListener('click', function(ev) {
                  var a = ev.target && ev.target.closest && ev.target.closest('a[href]');
                  if (a && BAD.test(a.getAttribute('href') || '')) {
                    ev.preventDefault(); ev.stopPropagation();
                  }
                }, true);
              }

              window.__fbproEarly = {
                set: function(a, p) { BLOCK_ADS = a; BLOCK_PROMOS = p; },
                filter: filterPayload
              };
            })();
        """.trimIndent()
    }

    /**
     * Cosmetic pass, injected after the page loads.
     *
     * Sponsored posts are found by the one marker Facebook cannot randomise:
     * every sponsored story links to /ads/about/. Stories, Reels, Marketplace
     * and the rest are hidden via [hideFlags], driven by the hidden settings.
     */
    fun getCosmeticScript(
        blockAds: Boolean,
        blockAppPromos: Boolean,
        hideFlags: Map<String, Boolean>
    ): String {
        val flagsJs = hideFlags.entries.joinToString(",") { "${it.key}:${it.value}" }
        return """
            (function() {
              'use strict';
              var FLAGS = {$flagsJs};
              var BLOCK_ADS = $blockAds;
              var BLOCK_PROMOS = $blockAppPromos;

              if (window.__fbproCos) {
                window.__fbproCos.update(BLOCK_ADS, BLOCK_PROMOS, FLAGS);
                window.__fbproCos.run();
                return;
              }

              var TAG = 'data-fbpro-hidden';
              var PROTECT = {HTML:1,HEAD:1,BODY:1,SCRIPT:1,STYLE:1,MAIN:1};
              var busy = false;

              // Comment content is untouchable. Facebook's ad units and its
              // comment cards share the same rounded-card shell, so the
              // heuristics below (and the published rules above) have
              // repeatedly matched a comment thread and removed it whole -
              // the "comments vanish a second after they load" report. Any
              // node that is a comment item, a comment section, or lives
              // inside one is refused, whatever matched it.
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

              // A node that is a comment thread rather than a single card:
              // direct children include comment items, and the node carries
              // no ad identity of its own. A feed card with one inline
              // comment preview is NOT a thread (it has data-dcm-id or a
              // sponsored marker), so feed ads keep being removed whole.
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
                // several comment items and none.
                if (commentKids === 1 && n > 3) return false;
                if (el.hasAttribute('data-dcm-id')) return false;
                var tt = (el.getAttribute('data-testid') || '').toLowerCase();
                if (tt.indexOf('sponsored') !== -1) return false;
                if ((el.getAttribute('data-sigil') || '').indexOf('AdStory') !== -1) return false;
                return true;
              }

              function hide(el) {
                if (!el || el.nodeType !== 1 || PROTECT[el.tagName]) return;
                if (isCommentZone(el) || insideComments(el)) return;
                if (el.hasAttribute(TAG)) return;
                el.setAttribute(TAG, '1');
                el.style.setProperty('display', 'none', 'important');
              }

              // Walk up to the feed-story container so the whole card goes,
              // not just the inner link. Stops before it can swallow the feed.
              // Page-level containers. Climbing into one of these wipes the
              // whole screen - it is what blanked the login page and, on
              // m.facebook.com, the entire feed.
              var STOP_ID = {
                page:1, viewport:1, root:1, content:1, mount_0_0:1, globalContainer:1,
                login_form_wrapper:1, login_form:1, reg_form:1, main:1, container:1,
                'facebook':1, 'MComposer':1, 'header':1, 'footer':1,
                // m.facebook.com shells
                MRoot:1, rootcontainer:1, m_newsfeed_stream:1, mainContainer:1,
                screen_root:1, root_container:1, structured_composer_form:1
              };

              // Anything that looks like a feed or a list of stories. Hiding
              // one of these removes the whole page, so the walk must stop
              // before it and never target it.
              function isContainer(el) {
                if (!el || !el.getAttribute) return false;
                if (STOP_ID[el.id]) return true;

                // Facebook's lite renderer draws a whole screen inside one
                // MScreen node, and it is the only one on the page. Every
                // control the user taps - the comment box, the account
                // switcher, the tab bar - lives inside it. It carries no id,
                // no role and no data-sigil, so nothing above matched it and
                // the walk was free to take it.
                //
                // Measured against a captured m.facebook.com lite screen: the
                // "Open app" label sat 5 levels under MScreen, the walk
                // climbed all 5, and hiding MScreen removed 77 of the 77
                // nodes carrying a data-action-id. That is the whole screen -
                // which is why a dialog only ever showed its dimmed backdrop
                // and the comment control was missing entirely.
                var mc = el.getAttribute('data-mcomponent') || '';
                if (mc === 'MScreen') return true;

                // The screen root also carries these. Checked separately so a
                // renamed component cannot reopen the same hole.
                if (el.hasAttribute('data-screen-id') ||
                    el.hasAttribute('data-crash-screen-id') ||
                    el.hasAttribute('data-screen-keys')) return true;

                // Note: the pinned bars (class "fixed-container top|bottom")
                // are deliberately NOT stop nodes. A bar that is nothing but
                // an app promo has to be removable whole, or an empty strip
                // is left behind once its link is hidden. The header is
                // protected instead by the control count in hideStory, which
                // tells "a banner" from "the bar that happens to contain
                // one".

                var r = el.getAttribute('role');
                if (r === 'feed' || r === 'main' || r === 'navigation') return true;


                // Only 'feed' means a list. 'story_div' marks an individual
                // story, so treating it as a container stopped the walk one
                // level short and left every ad on screen.
                var ds = el.getAttribute('data-sigil') || '';
                if (ds.indexOf('feed') !== -1) return true;

                var pl = el.getAttribute('data-pagelet') || '';
                if (pl.indexOf('Feed') !== -1 || pl.indexOf('MainFeed') !== -1) return true;

                // Holds more than one story, so it is a list, not a banner.
                try {
                  if (el.querySelectorAll('[role="article"],article').length > 1) return true;
                } catch (e) {}

                // Holds page furniture we must never remove.
                try {
                  if (el.querySelector('input[type="password"],form#login_form')) return true;
                } catch (e) {}

                return false;
              }

              function isStopNode(el) { return isContainer(el); }

              // Walk up from a matched node to the card that should be removed.
              //
              // Every previous attempt tried to recognise the feed by its
              // shape - ids, class names, child counts, story markers. None of
              // those exist reliably on Facebook's obfuscated mobile DOM, so
              // each attempt either stopped too early (ad survives) or climbed
              // too far (page goes blank).
              //
              // This version does not try to recognise anything. It measures.
              // Before walking we record how much text the whole page holds.
              // A single ad card is a small fraction of that. So we climb only
              // while the candidate stays under a share of the page, and stop
              // the moment taking one more step would cross it. That works
              // whatever the markup looks like.

              function textLen(el) {
                if (!el) return 0;
                var t = el.innerText || el.textContent || '';
                return t.length;
              }

              // Text of the whole scrollable page, cached per sweep.
              var pageTextLen = 0;
              function refreshPageText() {
                try {
                  var main = document.querySelector('[role="main"],[role="feed"]') ||
                             document.body;
                  pageTextLen = textLen(main);
                } catch (e) { pageTextLen = 0; }
              }

              function isCard(el) {
                if (!el || !el.getAttribute) return false;
                if (el.getAttribute('role') === 'article') return true;
                if (el.tagName === 'ARTICLE') return true;
                if (el.getAttribute('data-sigil') === 'story_div') return true;
                if (el.hasAttribute('aria-posinset')) return true;
                return false;
              }

              function hideStory(el, maxUp) {
                if (!el) return;
                if (!pageTextLen) refreshPageText();

                // A card may hold at most this share of the page's text.
                // Anything larger is a list of posts, not one post.
                var limit = pageTextLen > 0 ? pageTextLen * 0.45 : 1e9;

                var cap = Math.min(maxUp || 8, 10);
                var n = el, d = 0;

                // How many controls the starting point owns. Climbing past a
                // node that brings in unrelated ones means we have left the
                // banner and reached the bar that merely contains it.
                function ctrls(x) {
                  try {
                    return x.querySelectorAll(
                      'a,button,[role="button"],[role="link"],[data-action-id]'
                    ).length;
                  } catch (e) { return 0; }
                }
                var startCtrls = ctrls(el);

                while (n && d < cap) {
                  if (isCard(n)) break;

                  var p = n.parentElement;
                  if (!p || PROTECT[p.tagName]) break;
                  if (isContainer(p)) break;
                  // Climbing into a comment thread is exactly the over-reach
                  // that wiped the comments on the post page. Stop here -
                  // the node below p (the ad unit) is what gets removed.
                  if (isCommentZone(p) || isCommentThreadContainer(p)) break;
                  if (isCard(p)) { n = p; break; }

                  // Taking p would cover too much of the page: stop here.
                  if (textLen(p) > limit) break;

                  // Taking p would take controls that are nothing to do with
                  // the banner.
                  //
                  // Facebook puts "Open app" inside the pinned header, next to
                  // the logo, the Log in button and the tab row. Every text
                  // test matches, and the walk then climbed out of the link
                  // and took the header: 8 controls on the live page. That is
                  // the header vanishing a second after the feed paints, with
                  // the app-download bar still visible underneath because it
                  // is a separate node.
                  //
                  // The text guard could not catch it - the header is a few
                  // dozen characters against a page of tens of thousands.
                  if (ctrls(p) > startCtrls + 1) break;

                  try {
                    if (p.querySelector('input[type="password"],input[type="email"],form')) break;
                  } catch (e) {}

                  n = p; d++;
                }

                var target = n || el;
                if (!target || PROTECT[target.tagName] || isContainer(target)) return;
                if (isCommentZone(target) || insideComments(target)) return;
                if (target === document.body || target === document.documentElement) return;
                if (!target.parentElement) return;

                // Never remove a node covering most of the page - that is the
                // feed, not a card. Two exceptions, or a feed holding a single
                // ad could never be cleaned:
                //   - the node is explicitly a card
                //   - it has no sibling content, so removing it loses nothing
                //     that is not already going away
                if (pageTextLen > 200 && textLen(target) > limit && !isCard(target)) {
                  var sib = target.parentElement.children;
                  var others = 0;
                  for (var i = 0; i < sib.length; i++) {
                    if (sib[i] !== target && textLen(sib[i]) > 20) others++;
                  }
                  if (others > 0) return;   // real content beside it: refuse
                }

                hide(target);
              }

              // ---- sponsored posts: the /ads/about/ probe ----------------------
              function killSponsored() {
                var links = document.querySelectorAll(
                  'a[href*="/ads/about"],a[href^="/ads/about/"],' +
                  'a[attributionsrc^="/privacy_sandbox/"]'
                );
                for (var i = 0; i < links.length; i++) hideStory(links[i]);

                var labelled = document.querySelectorAll(
                  '[aria-label="Sponsored"],[aria-label="Sponsorisé"],' +
                  '[aria-label="Patrocinado"],[aria-label="Gesponsert"],' +
                  '[data-pagelet^="FeedAdUnit"],[data-testid="fbFeedSponsoredContent"],' +
                  'div[data-ad-preview],div[data-ad-comet-preview],' +
                  'ins.adsbygoogle,div[id^="div-gpt-ad"],' +
                  'iframe[src*="doubleclick.net"],iframe[src*="googlesyndication"]'
                );
                for (var j = 0; j < labelled.length; j++) hideStory(labelled[j]);

                // Mobile marks sponsored posts with a small text label rather
                // than an aria-label, and it is localized. Match the label
                // only on short elements so a post that merely mentions the
                // word is never removed.
                // Facebook labels feed ads "Ad ·" on mobile and "Sponsored"
                // on desktop, in the user's language. Both were missed before:
                // the screenshots showed "Ad ·" and there was no rule for it.
                var SPONSOR_WORDS = [
                  'ad', 'ads', 'sponsored', 'sponsored ad',
                  'sponsorisé', 'publicité', 'annonce',
                  'patrocinado', 'publicidad', 'anuncio',
                  'gesponsert', 'werbung', 'anzeige',
                  'sponsorizzato', 'pubblicità', 'gesponsord', 'advertentie',
                  'sponsrad', 'annons', 'sponsoroitu', 'mainos',
                  'reklama', 'reklam', 'reclamă', 'hirdetés',
                  'বিজ্ঞাপন', 'স্পনসর্ড', 'স্পন্সর্ড', 'প্রযোজিত',
                  'विज्ञापन', 'प्रायोजित', 'ਵਿਗਿਆਪਨ', 'જાહેરાત',
                  'விளம்பரம்', 'ప్రకటన', 'ಜಾಹೀರಾತು', 'പരസ്യം',
                  'إعلان', 'مُموَّل', 'ممول', 'تبلیغات',
                  'โฆษณา', 'ได้รับการสนับสนุน', 'quảng cáo',
                  'iklan', 'disponsori', 'may sponsor', '广告', '広告', '광고',
                  'реклама', 'спонсоровано', 'διαφήμιση'
                ];

                // Exact-match only, on very short elements. "Ad" is a real
                // word, so anything longer than a label is left alone.
                function isAdLabel(tx) {
                  for (var i = 0; i < SPONSOR_WORDS.length; i++) {
                    var wd = SPONSOR_WORDS[i];
                    if (tx === wd) return true;
                    if (tx === wd + ' ·' || tx === wd + '·') return true;
                    if (tx === wd + ' ' || tx === '· ' + wd) return true;
                    // "Sponsored · " with the globe icon following
                    if (tx.length <= wd.length + 4 && tx.indexOf(wd) === 0 &&
                        /^[\s·•\-–|]*$/.test(tx.slice(wd.length))) return true;
                  }
                  return false;
                }

                var cand = document.querySelectorAll(
                  'span,a,div[data-sigil],abbr,em,strong,div[role="button"]'
                );
                for (var k = 0; k < cand.length && k < 4000; k++) {
                  var el = cand[k];
                  if (el.hasAttribute(TAG)) continue;
                  if (el.children.length > 2) continue;
                  var tx = (el.innerText || el.textContent || '');
                  // A label is tiny. 20 chars is generous.
                  if (!tx || tx.length > 20) continue;
                  tx = tx.trim().toLowerCase();
                  if (!tx) continue;
                  if (isAdLabel(tx)) hideStory(el, 12);
                }
              }

              // ---- app download / "get the app" -------------------------------
              // Three strategies, because Facebook keeps shipping new banner
              // variants and a fixed selector list always falls behind:
              //   A. known selectors and store hrefs
              //   B. known phrases
              //   C. STRUCTURAL - anything that looks like an app-install
              //      banner (store link, app-store schema, or a sticky bar
              //      whose text matches a generic app-promo pattern)
              var PROMO_SEL = [
                'a[href*="play.google.com/store"]',
                'a[href*="apps.apple.com"]', 'a[href*="itunes.apple.com"]',
                'a[href^="market://"]', 'a[href^="fb://"]', 'a[href^="intent://"]',
                'a[href^="itms-apps://"]', 'a[href^="fb-messenger://"]',
                'a[href*="/mobile/download"]', 'a[href*="messenger.com/download"]',
                'a[href*="facebook.com/mobile"]', 'a[href*="/install_app"]',
                'a[href*="app_landing"]', 'a[href*="get_the_app"]',
                'a[href*="appstore"]', 'a[href*="play_store"]',
                '[data-testid*="app_download"]', '[data-testid*="AppDownload"]',
                '[data-testid*="install_app"]', '[data-testid*="open_in_app"]',
                '[data-testid*="app_upsell"]', '[data-testid*="AppUpsell"]',
                '[data-testid*="smart_banner"]', '[data-testid*="app_banner"]',
                '[data-pagelet*="AppDownload"]', '[data-pagelet*="AppUpsell"]',
                '[data-nt="FB:APP_INSTALL"]', '[data-sigil*="app_install"]',
                '[data-sigil*="appinstall"]', '[id*="app_install" i]',
                '[id*="appinstall" i]', '[id*="install_banner" i]',
                '#mobile_app_install_banner', '#appManifestBanner',
                '#MComposerAppInstallBanner', 'div[id^="m_appinstall"]',
                '.mobile-app-banner', '.app-install-banner', '.app-download-banner',
                '.app_promotion', '.app-install-prompt', '.get-app-banner',
                '.smartbanner', '.smart-banner', '.app-smart-banner',
                'meta[name="apple-itunes-app"]', 'meta[name="google-play-app"]'
              ].join(',');

              var PROMO_TEXT = [
                'get the facebook app','get facebook for android','get facebook for ios',
                'download the facebook app','download the app','install the app',
                'install facebook','open in app','open in the app','continue in app',
                'continue in the app','use the app','get the app','switch to the app',
                'try the app','see more on the app','view in app','open in messenger',
                'get messenger','install messenger','download messenger',
                'better on the app','open with app','use facebook app','go to app',
                'open facebook','view in the facebook app','see this in the app',
                'the app is faster','faster in the app','more on the app',
                'keep browsing in the app','continue with the app','get our app',
                'download now','install now','open app','use app','app store',
                'google play','app ta download','app e dekhun'
              ];

              // Generic pattern for banners we have never seen before.
              // Verb ... "app" within a short window, in either order, so
              // "Try the app for a better experience" and "the app is faster"
              // both match without having to be listed literally.
              var PROMO_RE = new RegExp(
                '\\b(open|continue|view|see|browse|switch|use|get|download|install|try|keep)\\b' +
                '[\\s\\S]{0,30}?\\b(facebook |messenger |our |the )?app\\b' +
                '|\\bapp\\b[\\s\\S]{0,20}?\\b(is faster|is better|experience)\\b' +
                // Facebook's login banner never contains the word "app": it reads
                // "Get Facebook for Android and browse faster." Match the
                // product-name-plus-platform shape too, or it survives every pass.
                '|\\b(get|download|install|try)\\b[\\s\\S]{0,24}?' +
                '\\b(facebook|messenger|instagram)\\b[\\s\\S]{0,24}?' +
                '\\bfor\\s+(android|ios|iphone|mobile)\\b', 'i'
              );

              function isStoreHref(h) {
                if (!h) return false;
                return /play\.google\.com\/store|apps\.apple\.com|itunes\.apple\.com|^market:|^fb:|^intent:|^itms-apps:|^fb-messenger:|\/mobile\/download|messenger\.com\/download/i.test(h);
              }

              // C. Structural sweep: catches new banner variants by shape.
              function killPromosStructural() {
                // 1. Any link pointing at an app store, wherever it lives.
                var links = document.getElementsByTagName('a');
                for (var i = 0; i < links.length; i++) {
                  var a = links[i];
                  if (a.hasAttribute(TAG)) continue;
                  if (isStoreHref(a.getAttribute('href'))) hideStory(a, 6);
                }

                // 2. Sticky / fixed bars pinned to an edge that talk about an
                //    app. This is the shape every "open in app" bar shares.
                // Not div[class] only: a banner div may carry no class at all.
                var bars = document.querySelectorAll(
                  '[role="banner"],[role="dialog"],[role="complementary"],' +
                  'div,section,aside,header,footer'
                );
                for (var j = 0; j < bars.length && j < 4000; j++) {
                  var el = bars[j];
                  if (el.hasAttribute(TAG)) continue;
                  if (PROTECT[el.tagName]) continue;

                  // Cheap guards first: a banner is small and shallow, so we
                  // skip big containers before touching innerText, which is
                  // the expensive call.
                  if (el.children.length > 12) continue;

                  // Skip anything already removed, or containing something we
                  // removed. Without this the sweep re-matched a hidden card's
                  // text through its ancestors and climbed to the page wrapper,
                  // hiding the entire feed.
                  try {
                    if (el.querySelector('[' + TAG + ']')) continue;
                    if (el.closest('[' + TAG + ']')) continue;
                  } catch (e) {}

                  var txt = (el.innerText || el.textContent || '');
                  if (!txt || txt.length > 160) continue;
                  if (!PROMO_RE.test(txt)) continue;

                  // Cheap DOM checks before any style read. getComputedStyle
                  // forces layout, so it is the last thing we do and only for
                  // the few nodes whose text already matched.
                  var hasStore = !!el.querySelector(
                    'a[href*="play.google.com"],a[href*="apps.apple.com"],' +
                    'a[href^="market:"],a[href^="fb:"],a[href^="intent:"],' +
                    'a[href^="/lite/"],a[href^="/mobile/"]'
                  );
                  var small = el.children.length <= 8 && txt.length <= 120;

                  var pinned = false;
                  if (!hasStore && !small) {
                    var cs = null;
                    try { cs = window.getComputedStyle(el); } catch (e) {}
                    pinned = !!cs && (cs.position === 'fixed' || cs.position === 'sticky');
                  }

                  if (pinned || hasStore || small) hideStory(el, pinned ? 2 : 5);
                }
              }

              function killPromos() {
                var n = document.querySelectorAll(PROMO_SEL);
                for (var i = 0; i < n.length; i++) hideStory(n[i], 5);

                // Facebook's login-page banner is a plain <div> with no role
                // and no class, so the element list has to include bare block
                // containers or the literal PROMO_TEXT list never gets a
                // chance to match it.
                var c = document.querySelectorAll(
                  'a,button,[role="button"],[role="banner"],[role="dialog"],[role="link"],' +
                  'div,section,aside,header'
                );
                for (var j = 0; j < c.length; j++) {
                  var el = c[j];
                  if (el.hasAttribute(TAG)) continue;
                  var t = el.innerText || el.textContent || '';
                  if (!t || t.length > 90) continue;
                  t = t.trim().toLowerCase();
                  if (!t) continue;

                  // The node must be the promo itself, not something that
                  // merely contains one.
                  //
                  // Facebook puts "Open app" inside the pinned header, beside
                  // the logo, the Log in button and the tab row. That header's
                  // text reads "Open appLog inVideo", which contains the
                  // phrase - so a substring test matched the header and the
                  // walk then removed the whole thing. On the live page that
                  // is 8 controls, and it is the header disappearing about a
                  // second after the feed paints.
                  //
                  // A real promo is a leaf: a link or a button whose entire
                  // text is the offer. Anything wrapping other controls is a
                  // container, and the promo inside it is matched on its own
                  // pass anyway.
                  var extra = 0;
                  try {
                    extra = el.querySelectorAll(
                      'a,button,[role="button"],[role="link"],[data-action-id]'
                    ).length;
                  } catch (e) {}
                  var self = (el.tagName === 'A' || el.tagName === 'BUTTON' ||
                              el.getAttribute('role') === 'button' ||
                              el.getAttribute('role') === 'link' ||
                              el.hasAttribute('data-action-id'));
                  if (extra > (self ? 1 : 0)) continue;

                  for (var k = 0; k < PROMO_TEXT.length; k++) {
                    if (t.indexOf(PROMO_TEXT[k]) !== -1) { hideStory(el, 5); break; }
                  }
                }

                killPromosStructural();
              }

              // ---- optional section hiding (driven by hidden settings) --------
              // Matched by aria-label and heading text, which survive Facebook's
              // class-name randomisation.
              var SECTIONS = {
                stories:     ['stories','story','historias','بالقصص'],
                reels:       ['reels','reel'],
                rooms:       ['rooms','room','create room'],
                marketplace: ['marketplace'],
                groups:      ['groups','your groups'],
                watch:       ['watch','video'],
                events:      ['events'],
                gaming:      ['gaming','play games'],
                memories:    ['memories'],
                birthdays:   ['birthdays'],
                pymk:        ['people you may know','suggested for you','suggested friends'],
                pages:       ['pages','pages you may like'],
                sponsored:   []
              };

              function killSection(key) {
                var words = SECTIONS[key];
                if (!words || !words.length) return;

                // 1. aria-label / data-pagelet direct hits
                for (var w = 0; w < words.length; w++) {
                  var word = words[w];
                  var sel = '[aria-label="' + word + '" i],[data-pagelet*="' + word + '" i]';
                  var hits;
                  try { hits = document.querySelectorAll(sel); } catch (e) { continue; }
                  for (var i = 0; i < hits.length; i++) hideStory(hits[i], 6);
                }

                // 2. section headings (h1-h4 / role=heading) inside the feed
                var heads = document.querySelectorAll(
                  'h1,h2,h3,h4,[role="heading"],span[dir="auto"]'
                );
                for (var j = 0; j < heads.length; j++) {
                  var el = heads[j];
                  if (el.hasAttribute(TAG)) continue;
                  var t = (el.innerText || el.textContent || '').trim().toLowerCase();
                  if (!t || t.length > 40) continue;
                  for (var k = 0; k < words.length; k++) {
                    if (t === words[k]) { hideStory(el, 8); break; }
                  }
                }

                // 3. link-based hits for nav entries
                var hrefMap = {
                  marketplace: '/marketplace', groups: '/groups', watch: '/watch',
                  events: '/events', gaming: '/gaming', reels: '/reel',
                  memories: '/memories'
                };
                if (hrefMap[key]) {
                  var links = document.querySelectorAll('a[href^="' + hrefMap[key] + '"]');
                  for (var m = 0; m < links.length; m++) {
                    // only nav/shortcut links, not user-clicked content
                    var r = links[m].getAttribute('role');
                    if (r === 'link' || r === 'button' || links[m].closest('[role="navigation"]')) {
                      hideStory(links[m], 4);
                    }
                  }
                }
              }

              function run() {
                if (busy) return;
                busy = true;
                try {
                  refreshPageText();
                  if (BLOCK_ADS) killSponsored();
                  if (BLOCK_PROMOS) killPromos();
                  for (var key in FLAGS) { if (FLAGS[key]) killSection(key); }
                } catch (e) {}
                busy = false;
              }

              // ---- throttled observer (no feedback loop) ----------------------
              var queued = false, last = 0;
              function schedule() {
                if (busy || queued) return;
                queued = true;
                var wait = Math.max(0, 250 - (Date.now() - last));
                setTimeout(function() {
                  queued = false; last = Date.now();
                  (window.requestIdleCallback || window.requestAnimationFrame ||
                   function(f){ setTimeout(f, 0); })(run);
                }, wait);
              }

              var mo = new MutationObserver(function(muts) {
                for (var i = 0; i < muts.length; i++) {
                  if (muts[i].addedNodes && muts[i].addedNodes.length) { schedule(); return; }
                }
              });

              function start() {
                if (!document.body) return;
                // childList only. Observing attributes watched our own writes
                // and pinned the CPU in the previous version.
                mo.observe(document.body, { childList: true, subtree: true });
                run();
              }

              if (document.body) start();
              else document.addEventListener('DOMContentLoaded', start, { once: true });

              setTimeout(run, 700);
              setTimeout(run, 1800);
              setTimeout(run, 3500);

              // blob: download bridge
              if (!window.__fbproBlob) {
                window.__fbproBlob = true;
                document.addEventListener('click', function(ev) {
                  var a = ev.target && ev.target.closest && ev.target.closest('a[download]');
                  if (!a || !a.href || a.href.indexOf('blob:') !== 0) return;
                  ev.preventDefault();
                  var x = new XMLHttpRequest();
                  x.open('GET', a.href, true); x.responseType = 'blob';
                  x.onload = function() {
                    var r = new FileReader();
                    r.onloadend = function() {
                      if (window.FBPro && window.FBPro.onBlobDownload)
                        window.FBPro.onBlobDownload(r.result, a.getAttribute('download') || '');
                    };
                    r.readAsDataURL(x.response);
                  };
                  x.send();
                }, true);
              }

              window.__fbproCos = {
                run: run,
                update: function(a, p, f) { BLOCK_ADS = a; BLOCK_PROMOS = p; FLAGS = f; }
              };
            })();
        """.trimIndent()
    }

    /** CSS hard-blocks applied before first paint. */
    fun getStyleScript(
        blockPromos: Boolean,
        blockAds: Boolean,
        hideSiteLoadingBar: Boolean = false
    ): String {
        val rules = mutableListOf<String>()
        if (hideSiteLoadingBar) {
            // Turning the app's own loading bar off did not remove the thin
            // blue line at the top of the screen, because that line is not
            // ours. Facebook's lite renderer draws its own, from its own
            // stylesheet:
            //
            //   .loading-bar-animation{position:fixed;top:0;left:0;height:2px;
            //     width:100%;animation:prog 15s linear forwards;z-index:1}
            //   .revamped-progress-bar-color .loading-bar-animation{
            //     background:linear-gradient(90deg,#004cc6,#0079ff)}
            //
            // and builds it in JS on every screen swap:
            //
            //   a.e.className='loading-bar-animation';
            //   a.f.className='loading-bar-background';
            //
            // So the setting has to hide Facebook's element too, or it only
            // ever removed a bar the user was not looking at.
            //
            // The dimming layer has to go with it, and an earlier version of
            // this comment was wrong to leave it. One function builds all
            // three, and the overlay is the bar's own parent:
            //
            //   a.g.className='loading-overlay';
            //   a.e.className='loading-bar-animation';
            //   a.f.className='loading-bar-background';
            //   a.g.appendChild(a.e); a.g.appendChild(a.f);
            //
            // Hiding only the bar left the grey wash behind - in dark mode
            // rgba(0,0,0,0.6) over the whole screen - with nothing moving on
            // it. Tapping anything appeared to make the app freeze, because
            // the one element that said "this is loading" was the part that
            // had been removed. That is worse than either extreme.
            //
            // Checked before touching it: 'loading-overlay' is assigned in
            // exactly one place in the whole lite bundle, the function above.
            // Real dialogs use .dialog-screen and content uses .overlay, both
            // of which are left alone.
            //
            // display:none rather than opacity:0 - the overlay also sets
            // pointer-events, and a wash that still swallows taps would be a
            // worse bug than the one being fixed.
            rules += listOf(
                ".loading-bar-animation",
                ".loading-bar-background",
                ".loading-overlay",
                ".loading-overlay-background"
            )
        }
        if (blockPromos) {
            rules += listOf(
                // classic m.facebook.com install notice above the login form
                "#header-notices",
                "div[id^=\"header-notice\"]",
                "._52jh._5c6i",
                "a[href*=\"utm_source=mobile_web\"]",
                "a[href*=\"play.google.com/store\"]",
                "a[href*=\"play.google.com/store/apps\"]",
                "a[href*=\"apps.apple.com\"]",
                "a[href^=\"market://\"]",
                "a[href^=\"itms-apps://\"]",
                "#mobile_app_install_banner",
                "#appManifestBanner",
                "#MComposerAppInstallBanner",
                "[data-testid*=\"app_download\"]",
                "[data-testid*=\"install_app\"]",
                "[data-nt=\"FB:APP_INSTALL\"]"
            )
        }
        if (blockAds) {
            rules += listOf(
                "ins.adsbygoogle",
                "div[id^=\"div-gpt-ad\"]",
                "iframe[src*=\"doubleclick.net\"]",
                "iframe[src*=\"googlesyndication\"]",
                "[data-pagelet^=\"FeedAdUnit\"]"
            )
        }
        // Nothing to hide any more. This has to actively empty the sheet
        // rather than do nothing: the element survives from the previous
        // injection, so returning a no-op left the last set of rules in force
        // and a setting that had just been switched off went on applying.
        if (rules.isEmpty()) {
            return """
                (function() {
                  var s = document.getElementById('fbpro-style');
                  if (s) s.textContent = '';
                })();
            """.trimIndent()
        }
        // Selectors contain double quotes, so the CSS is emitted as a single
        // quoted JS string with quotes escaped. Building it with plain double
        // quotes produced a SyntaxError and the whole style layer silently
        // never ran.
        val css = (rules.joinToString(",") + "{display:none !important;}")
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return """
            (function() {
              var id = 'fbpro-style';
              var s = document.getElementById(id);
              if (!s) {
                s = document.createElement('style');
                s.id = id;
                (document.head || document.documentElement).appendChild(s);
              }
              s.textContent = '$css';
            })();
        """.trimIndent()
    }

    /**
     * Native-app feel. Facebook's mobile web keeps a few browser-only
     * affordances that break the illusion; this removes them.
     */
    fun getNativeFeelScript(): String {
        return """
            (function() {
              if (document.getElementById('fbpro-native')) return;
              var css = [
                /* no text selection or callout on chrome, like a real app */
                'body{-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent;}',
                /* keep selection where it matters */
                'p,span[dir],div[dir],input,textarea,[contenteditable]{',
                '-webkit-touch-callout:default;-webkit-user-select:text;}',
                /* momentum scrolling */
                /* momentum scrolling only on real scrollers, not every node */
                'html,body{-webkit-overflow-scrolling:touch;}',

                /* Pressed state.
                   The line above turns off the browser's own tap highlight,
                   which is right for a native feel - but only if something
                   replaces it. Facebook's lite renderer ships a highlight of
                   its own, .mtfi [data-action-id].highlight, and it never
                   fires here: .mtfi appears in its stylesheet and on no
                   element on the page. So a tap produced no response at all
                   until the next screen arrived, and the wait read as the app
                   being slow rather than as the page loading.
                   Applied on a class we add ourselves, so it cannot fight
                   whatever Facebook does with :active. */
                '.__db_press{opacity:.55 !important;', 
                'transition:opacity .04s ease-out !important;}',
                /* kill the browser-ish install/notification prompts */
                '[data-testid="cookie-policy-manage-dialog"],',
                'div[role="dialog"]:has(a[href*="play.google.com"]){display:none !important;}',
                /* no horizontal rubber-band */
                /* Only damp horizontal rubber-band. Never set overflow or
                   max-width on html/body: that creates a new scroll context
                   and can freeze Facebook's virtualised feed. */
                'html,body{overscroll-behavior-x:contain;}'
              ].join('');
              var s = document.createElement('style');
              s.id = 'fbpro-native';
              s.textContent = css;
              (document.head || document.documentElement).appendChild(s);

              /* Suppress web push / notification permission prompts - a native
                 app would use system notifications, not a web prompt. */
              try {
                if (window.Notification && Notification.requestPermission) {
                  Notification.requestPermission = function() {
                    return Promise.resolve('denied');
                  };
                }
              } catch (e) {}

              /* Block the PWA install banner entirely. */
              window.addEventListener('beforeinstallprompt', function(e) {
                e.preventDefault(); return false;
              });

              /* ---- instant response to a tap --------------------------------
                 A native app dims a control the moment it is touched, before
                 anything loads. The page does nothing until the next screen
                 arrives, so every tap felt like a delay even when the load
                 was perfectly quick.

                 touchstart, not click: touchstart fires immediately, click
                 only after the gesture is judged not to be a scroll - which
                 is exactly the delay being complained about.

                 The class is removed on touchend, on touchcancel, and on the
                 first scroll, so a swipe that merely began on a control does
                 not leave it dimmed. A timer clears it as a last resort: if
                 the page is replaced while the finger is down there may be no
                 touchend at all, and a control stuck at half opacity would be
                 a worse bug than the one being fixed. */
              (function() {
                var held = null, timer = null;

                function release() {
                  if (timer) { clearTimeout(timer); timer = null; }
                  if (!held) return;
                  try { held.classList.remove('__db_press'); } catch (e) {}
                  held = null;
                }

                /* What counts as a control. Anything Facebook gives an action
                   to, plus the ordinary interactive elements. Kept to the
                   nearest one so a tap dims the button, not the whole card. */
                var SEL = 'a,button,[role="button"],[role="link"],' +
                          '[role="tab"],[role="menuitem"],[data-action-id],' +
                          '[data-fd-action],[data-sigil]';

                document.addEventListener('touchstart', function(ev) {
                  release();
                  var t = ev.target;
                  if (!t || !t.closest) return;
                  var el = t.closest(SEL);
                  if (!el) return;
                  /* Never dim something enormous: on a lite screen the whole
                     scroller can carry a data-sigil, and dimming that is a
                     flash of the entire page. */
                  try {
                    var r = el.getBoundingClientRect();
                    if (r.height > window.innerHeight * 0.6) return;
                  } catch (e) {}
                  held = el;
                  try { el.classList.add('__db_press'); } catch (e) {}
                  timer = setTimeout(release, 1200);
                }, {passive: true, capture: true});

                ['touchend', 'touchcancel'].forEach(function(e) {
                  document.addEventListener(e, release,
                    {passive: true, capture: true});
                });
                document.addEventListener('scroll', release,
                  {passive: true, capture: true});
              })();

              /* Report scroll position to the app.
                 Facebook sometimes scrolls an inner container instead of the
                 document, in which case WebView.scrollY never changes and
                 pull-to-refresh would swallow every downward drag. We report
                 the true top state from here so the app can tell the
                 difference between "at top" and "cannot scroll". */
              (function() {
                if (!window.FBPro || !window.FBPro.onScrollState) return;
                var lastState = null;

                function scroller() {
                  if ((document.scrollingElement || document.documentElement).scrollTop > 0)
                    return document.scrollingElement || document.documentElement;
                  // find the tallest scrollable container currently scrolled
                  var best = null;
                  var nodes = document.querySelectorAll('[role="main"],[role="feed"],div');
                  for (var i = 0; i < nodes.length && i < 400; i++) {
                    var n = nodes[i];
                    if (n.scrollHeight > n.clientHeight + 40 && n.scrollTop > 0) {
                      if (!best || n.scrollTop > best.scrollTop) best = n;
                    }
                  }
                  return best;
                }

                function report() {
                  var el = scroller();
                  var atTop = !el || el.scrollTop <= 1;
                  if (atTop !== lastState) {
                    lastState = atTop;
                    try { window.FBPro.onScrollState(atTop); } catch (e) {}
                  }
                }

                var ticking = false;
                function onScroll() {
                  if (ticking) return;
                  ticking = true;
                  requestAnimationFrame(function() { ticking = false; report(); });
                }

                // capture:true catches scroll events from inner containers too
                window.addEventListener('scroll', onScroll, true);
                document.addEventListener('touchmove', onScroll, {passive: true, capture: true});
                report();
              })();

              // ---- keep video audible ----------------------------------------
              // Facebook starts feed and reel video muted and shows a "Tap to
              // unmute" prompt. That prompt is removed offline, which left no
              // way to get the sound back at all, so the video has to start
              // unmounted instead.
              //
              // The historical warning still applies and is respected here: a
              // clip that was only allowed to autoplay *because* it is muted
              // is stopped by the browser the instant sound comes on. So this
              // never unmutes a video that is already running. It clears the
              // flag before playback begins, and if a running video is muted
              // it waits for the next user gesture -- by then the gesture
              // itself authorises audible playback, so nothing is paused.
              (function() {
                var pendingGesture = false;
                var lastGesture = 0;

                function unmute(v) {
                  if (!v) return;
                  // The user asked for silence: leave it alone. Without the
                  // old one-shot flag the sweep would otherwise undo their
                  // own mute a second later, every second.
                  if (v.__dbUserMuted) return;
                  try {
                    if (v.paused || v.currentTime === 0) {
                      // Not yet playing: safe to clear now.
                      v.muted = false;
                      v.defaultMuted = false;
                      v.removeAttribute('muted');
                      // Deliberately not latched. Facebook's feed player sets
                      // muted straight back on when it starts an autoplay -
                      // browsers only permit an unattended play() while the
                      // clip is silent - so a one-shot flag meant the sweep
                      // gave up after the first attempt and feed video stayed
                      // silent for the rest of the session. Reels seemed to
                      // work only because the user taps those, and the tap
                      // authorises audible playback through onGesture below.
                    } else if (v.muted) {
                      // Already running and muted. Touching it here would
                      // pause it, so defer to the next real interaction.
                      pendingGesture = true;
                    }
                  } catch (e) {}
                }

                function sweep() {
                  var v = document.getElementsByTagName('video');
                  for (var i = 0; i < v.length; i++) unmute(v[i]);
                }

                function onGesture() {
                  lastGesture = Date.now();
                  if (!pendingGesture) return;
                  pendingGesture = false;
                  var v = document.getElementsByTagName('video');
                  for (var i = 0; i < v.length; i++) {
                    try {
                      if (!v[i].paused && v[i].muted && !v[i].__dbUserMuted) {
                        v[i].muted = false;
                      }
                    } catch (e) {}
                  }
                }

                // Tell a user mute apart from Facebook's own. Facebook re-mutes
                // to satisfy the autoplay rules, which happens on its own; a
                // person does it by tapping the speaker, so there is a gesture
                // immediately before. Anything muted within a moment of a real
                // tap is treated as deliberate and never touched again.
                document.addEventListener('volumechange', function(ev) {
                  var t = ev.target;
                  if (!t || t.tagName !== 'VIDEO') return;
                  if (t.muted && (Date.now() - lastGesture) < 1000) {
                    t.__dbUserMuted = true;
                  } else if (!t.muted) {
                    t.__dbUserMuted = false;
                  }
                }, true);

                // loadedmetadata fires before playback, which is the moment
                // the flag can be cleared without interrupting anything.
                ['loadstart', 'loadedmetadata', 'canplay'].forEach(function(e) {
                  document.addEventListener(e, function(ev) {
                    if (ev.target && ev.target.tagName === 'VIDEO') unmute(ev.target);
                  }, true);
                });
                ['touchend', 'click'].forEach(function(e) {
                  document.addEventListener(e, onGesture, true);
                });
                setInterval(sweep, 1000);
                sweep();
              })();

              // ---- is anything actually playing? -----------------------------
              // Background audio used to be decided from the URL, but the lite
              // renderer swaps the Reels screen in without navigating, so the
              // address never changes and the test never matched. The video
              // element knows the truth, so let it say so.
              (function() {
                var last = null;

                function audible(m) {
                  // Muted does not count. The home feed autoplays every clip
                  // it scrolls past with the sound off, so counting those made
                  // the app claim audio was playing when nothing could be
                  // heard: leaving the app then kept a silent video alive and
                  // held the notification up. Volume 0 is the same thing by
                  // another name.
                  if (m.muted) return false;
                  if (typeof m.volume === 'number' && m.volume === 0) return false;
                  // readyState alone is not enough: a buffered but paused
                  // clip would keep the service alive forever.
                  return !m.paused && !m.ended && m.currentTime > 0;
                }

                // Background audio is for Reels, not for the home feed.
                //
                // Scrolling the feed autoplays whatever passes the viewport,
                // so treating any playing element as "audio" meant leaving
                // the app during an ordinary feed post kept a video alive and
                // held the notification up. Facebook labels the difference:
                // the lite renderer stamps every player node with
                //
                //   data-is-reels="true|false"
                //
                // captured live from m.facebook.com, where Watch feed posts
                // all carry "false". The <video> element is created inside
                // that node afterwards, so the flag is found by walking out
                // to the enclosing player.
                function isReel(m) {
                  try {
                    var n = m.closest ? m.closest('[data-is-reels]') : null;
                    if (n) return n.getAttribute('data-is-reels') === 'true';
                  } catch (e) {}
                  // No label anywhere: the dedicated Reels screen replaces
                  // the whole viewport, so fall back to that shape rather
                  // than guessing from the address, which never changes here.
                  try {
                    var r = m.getBoundingClientRect();
                    if (r && r.height > 0 && window.innerHeight > 0) {
                      return (r.height / window.innerHeight) > 0.7 &&
                             r.height > r.width;
                    }
                  } catch (e) {}
                  return false;
                }

                function anyPlaying() {
                  var v = document.getElementsByTagName('video');
                  for (var i = 0; i < v.length; i++) {
                    if (audible(v[i]) && isReel(v[i])) return true;
                  }
                  // Audio elements have no reel/feed distinction to make: the
                  // feed does not autoplay bare <audio>, so anything here was
                  // started deliberately.
                  var a = document.getElementsByTagName('audio');
                  for (var j = 0; j < a.length; j++) {
                    if (audible(a[j])) return true;
                  }
                  return false;
                }

                function tell() {
                  var now = anyPlaying();
                  if (now === last) return;
                  last = now;
                  try { window.FBPro.onMediaState(now); } catch (e) {}
                }

                // Media events do not bubble, so they are caught on capture.
                // volumechange matters as much as play here: a feed clip is
                // already running when the sound comes on, so without it the
                // change would not be noticed until the next poll.
                ['play', 'playing', 'pause', 'ended', 'emptied', 'volumechange']
                  .forEach(function(e) {
                    document.addEventListener(e, tell, true);
                  });

                // A reel can be swapped out without firing pause on the old
                // element, so poll as a backstop. Cheap: a tag lookup and a
                // couple of boolean reads.
                setInterval(tell, 1000);
                tell();
              })();
            })();
        """.trimIndent()
    }

    /**
     * Reports whether the page currently shown is a logged-out screen.
     *
     * Facebook serves the login form at https://www.facebook.com/ itself, so
     * the URL cannot be trusted. A password field, or a login/signup form,
     * is the reliable marker.
     */
    fun getAuthProbeScript(): String {
        return """
            (function() {
              function loggedOut() {
                if (document.querySelector('input[type="password"]')) return true;
                if (document.querySelector('form[action*="login"],#login_form,#loginform'))
                  return true;
                if (document.querySelector('[data-testid="royal_login_form"],[data-testid="open-registration-form-button"]'))
                  return true;
                // Signed-in shells always carry one of these.
                if (document.querySelector('[role="feed"],[aria-label="Facebook Menu"],[data-pagelet="LeftRail"]'))
                  return false;
                return false;
              }
              /**
               * Whether the page is actually fillable.
               *
               * The native login screen refused to submit until this was
               * reported true, and nothing reported it at all - so the app
               * answered every attempt with "Still connecting, try again in a
               * moment", however completely the page had loaded. The test is
               * the same one the login script performs: both fields present.
               * Reported from the probe, which already runs on every load and
               * several times after it.
               */
              function formReady() {
                var user = document.querySelector(
                  'input[name="email"],input[name="username"],#m_login_email,' +
                  '#email,input[type="email"],input[autocomplete="username"]'
                );
                var pass = document.querySelector(
                  'input[name="pass"],input[name="password"],#m_login_password,' +
                  '#pass,input[type="password"]'
                );
                return !!(user && pass);
              }
              try {
                if (window.FBPro && window.FBPro.onAuthState)
                  window.FBPro.onAuthState(loggedOut());
              } catch (e) {}
              try {
                if (window.FBPro && window.FBPro.onLoginFormReady)
                  window.FBPro.onLoginFormReady(formReady());
              } catch (e) {}
            })();
        """.trimIndent()
    }

    /**
     * Hands credentials to Facebook's own login form and submits it.
     *
     * Nothing is faked. We locate the real form the page rendered, set the
     * real input values, dispatch the input events React listens for, and
     * click the real submit button. Facebook therefore receives an ordinary
     * form post with its own CSRF token, its own action URL and its own
     * cookies - exactly what a browser login is.
     *
     * The credentials exist only for this call. They are not stored, logged
     * or sent anywhere else.
     */
    fun getLoginScript(email: String, password: String): String {
        // Encode as a JS string literal so a password containing quotes,
        // backslashes or script tags cannot break out or be corrupted.
        fun esc(v: String): String {
            val sb = StringBuilder()
            for (c in v) {
                when {
                    c == '\\' -> sb.append("\\\\")
                    c == '\'' -> sb.append("\\'")
                    c == '"' -> sb.append("\\\"")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c == '<' -> sb.append("\\u003C")
                    c == '>' -> sb.append("\\u003E")
                    c == '&' -> sb.append("\\u0026")
                    c.code < 0x20 -> sb.append("\\u%04X".format(c.code))
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
        return """
            (function() {
              var E = '${esc(email)}';
              var P = '${esc(password)}';

              function setValue(el, v) {
                // React overrides the value setter, so write through the
                // native descriptor or the framework ignores the change.
                var proto = Object.getPrototypeOf(el);
                var d = Object.getOwnPropertyDescriptor(proto, 'value');
                if (d && d.set) d.set.call(el, v); else el.value = v;
                el.dispatchEvent(new Event('input',  { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
              }

              function find() {
                var user = document.querySelector(
                  'input[name="email"],input[name="username"],#m_login_email,' +
                  '#email,input[type="email"],input[autocomplete="username"]'
                );
                var pass = document.querySelector(
                  'input[name="pass"],input[name="password"],#m_login_password,' +
                  '#pass,input[type="password"]'
                );
                return { user: user, pass: pass };
              }

              var f = find();
              if (!f.user || !f.pass) return 'NOFORM';

              f.user.focus(); setValue(f.user, E);
              f.pass.focus(); setValue(f.pass, P);

              // Prefer the real submit control so Facebook's own handlers run.
              var btn = document.querySelector(
                'button[name="login"],input[name="login"],#loginbutton,' +
                'button[type="submit"],[data-testid="royal_login_button"]'
              );
              setTimeout(function() {
                if (btn) {
                  btn.click();
                } else {
                  var form = f.pass.closest('form');
                  if (form) form.submit();
                }
              }, 120);
              return 'OK';
            })();
        """.trimIndent()
    }
}
