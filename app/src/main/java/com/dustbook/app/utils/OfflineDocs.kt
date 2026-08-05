package com.dustbook.app.utils

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the real Facebook pages, so being offline looks like being online.
 *
 * The previous approach rendered a screen of our own from a list of saved
 * items. It worked, but it was obviously not Facebook: no header, no tab bar,
 * no like or comment controls, and reels laid out by our CSS rather than by
 * the site.
 *
 * m.facebook.com is server-rendered, so the document it returns already
 * contains the whole shell and the content. Storing that document, and serving
 * it back for the main frame when there is no connection, gives the real UI
 * for free - every button in its right place, because it *is* the site. The
 * images, video, CSS and fonts it references come from [OfflineCache].
 *
 * Writes are always on a background thread. Nothing here goes near the bridge:
 * an earlier attempt passed the rendered DOM through JavaScript and the
 * documents were far too large to survive the trip.
 */
object OfflineDocs {

    private const val DIR = "offline_docs_v1"

    /** A document older than this is refetched as soon as we are online. */
    private const val STALE_AFTER_MS = 20L * 60 * 1000

    /** Documents are HTML; anything much larger than this is not a page. */
    private const val MAX_DOC_BYTES = 6 * 1024 * 1024
    private const val MIN_DOC_BYTES = 20 * 1024

    /** Per-screen ceiling on how many asset URLs are queued for download. */
    private const val MAX_PREFETCH_URLS = 400

    /** `url(...)` inside a stylesheet - fonts, sprites and masks. */
    private val CSS_URL = Regex("""url\(\s*["']?(https://[^"'")\s]+)""")

    /**
     * Screens worth keeping, by first path segment. These are the tabs the
     * user can reach from the bar at the top, which is what has to work for
     * offline to feel normal.
     */
    private val SCREENS = linkedMapOf(
        "home" to "https://m.facebook.com/",
        "reels" to "https://m.facebook.com/reel/",
        "stories" to "https://m.facebook.com/stories/",
        "watch" to "https://m.facebook.com/watch/",
        "notifications" to "https://m.facebook.com/notifications/",
        "friends" to "https://m.facebook.com/friends/",
        "marketplace" to "https://m.facebook.com/marketplace/",
        "menu" to "https://m.facebook.com/menu/"
    )

    private val pool = Executors.newFixedThreadPool(2)
    private val inFlight = AtomicInteger(0)

    /** Last outcome per screen, so a missing tab can be diagnosed. */
    private val outcomes = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var root: File? = null
    @Volatile private var appContext: Context? = null
    @Volatile var enabled: Boolean = true

    /**
     * Whether new content may be *written*.
     *
     * [enabled] used to gate reading and writing together, so switching
     * saving off also hid content already on disk. Reading is now always
     * allowed; only collecting new content follows the user's switches.
     */
    @Volatile var writeEnabled: Boolean = true


    /**
     * The WebView's user agent, set by the app at startup.
     *
     * This is not optional. Facebook chooses an entirely different renderer
     * per user agent, so fetching without one stored a page the WebView would
     * never have been served - which is why most screens came back empty
     * offline and only reels had anything.
     */
    @Volatile var userAgent: String? = null

    fun init(context: Context) {
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            appContext = context.applicationContext
            val d = File(context.filesDir, DIR)
            if (!d.exists()) d.mkdirs()
            root = d
        }
    }

    /** Which stored screen, if any, answers a request for [url]. */
    fun screenFor(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = UrlHelper.hostOf(url) ?: return null
        if (!host.endsWith("facebook.com")) return null
        val path = try {
            Uri.parse(url).path?.lowercase(Locale.ROOT) ?: ""
        } catch (e: Exception) {
            return null
        }
        val first = path.trim('/').substringBefore('/')
        return when (first) {
            "", "home.php" -> "home"
            "reel", "reels" -> "reels"
            "stories", "story" -> "stories"
            "watch", "videos" -> "watch"
            "notifications" -> "notifications"
            "friends" -> "friends"
            "marketplace" -> "marketplace"
            "menu", "bookmarks" -> "menu"
            else -> "home"
        }
    }

    private fun fileFor(screen: String): File? {
        val dir = root ?: return null
        if (!SCREENS.containsKey(screen)) return null
        return File(dir, "$screen.html")
    }

    fun has(screen: String): Boolean {
        val f = fileFor(screen) ?: return false
        return f.exists() && f.length() > 0
    }

    fun savedScreens(): List<String> = SCREENS.keys.filter { has(it) }

    /**
     * Screens the offline navigation may route to.
     *
     * Not [savedScreens]: that lists screens with a stored *document*, and a
     * screen can be perfectly usable without one. Stories are captured as
     * cards and rendered by the story viewer, so stories.html frequently does
     * not exist — the tab was therefore treated as unavailable and tapping it
     * did nothing at all. shellFor() builds a page from the cards in exactly
     * that case, so the route is valid whenever either exists.
     */
    fun navigableScreens(): List<String> = SCREENS.keys.filter { screen ->
        if (has(screen)) return@filter true
        val section = when (screen) {
            "reels", "watch" -> OfflineFeed.SECTION_REELS
            "stories" -> OfflineFeed.SECTION_STORIES
            "home" -> OfflineFeed.SECTION_FEED
            else -> return@filter false
        }
        // storedCount, not realPlayableCount. This runs on the WebView's
        // resource thread while a page is being assembled, and
        // realPlayableCount re-reads and re-parses the section's JSON and
        // then stats every media file it names — for four screens, on every
        // served page. The question here is only "is this tab worth
        // offering", which the cheap count answers.
        OfflineFeed.storedCount(section) > 0
    }

    /** The URL a stored screen answers on. */
    fun urlFor(screen: String): String? = SCREENS[screen]

    fun isStale(screen: String): Boolean {
        val f = fileFor(screen) ?: return true
        if (!f.exists()) return true
        return System.currentTimeMillis() - f.lastModified() > STALE_AFTER_MS
    }

    /**
     * A cached, ready-to-serve page, keyed by screen.
     *
     * Serving used to rebuild the whole document on every navigation, on the
     * WebView's resource thread: read the stored page, parse the item store,
     * hash and stat every media URL to decide what is playable, then
     * concatenate every card's markup. With a couple of hundred reels saved
     * that is several hundred SHA-256 hashes and filesystem stats per back
     * press, which is why going back from Reels crawled — and why it got
     * worse the more was downloaded.
     *
     * The result only changes when the stored page or the item store changes,
     * so it is pre-encoded as bytes and held until someone calls
     * [invalidate]. This means navigation from the built cache is a single
     * ConcurrentHashMap lookup and a ByteArrayInputStream — no file I/O,
     * no string copying, and no hashing on the resource thread.
     */
    private class Built(val bytes: ByteArray)

    private val built = java.util.concurrent.ConcurrentHashMap<String, Built>()

    /** Called whenever stored content changes, so the next serve rebuilds. */
    fun invalidate() {
        built.clear()
    }

    fun serve(request: WebResourceRequest): WebResourceResponse? {
        if (!enabled) return null
        if (!request.isForMainFrame) return null
        if (!request.method.equals("GET", true)) return null
        val screen = screenFor(request.url.toString()) ?: return null
        val f = fileFor(screen) ?: return null

        if (!f.exists() || f.length() == 0L) {
            // No stored document, but there may still be saved cards.
            val section = when (screen) {
                "reels", "watch" -> OfflineFeed.SECTION_REELS
                "stories" -> OfflineFeed.SECTION_STORIES
                "home" -> OfflineFeed.SECTION_FEED
                else -> null
            }
            val cards = section?.let { OfflineFeed.cardsHtml(it) } ?: ""
            return shellFor(screen, cards, "")
        }

        // The built cache is invalidated only when the store changes, so a
        // hit here is always fresh. No stamp check, no file stat, no copy.
        built[screen]?.let { b ->
            return WebResourceResponse(
                "text/html", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"),
                b.bytes.inputStream()
            )
        }

        return try {
            val section = when (screen) {
                "reels", "watch" -> OfflineFeed.SECTION_REELS
                "stories" -> OfflineFeed.SECTION_STORIES
                "home" -> OfflineFeed.SECTION_FEED
                else -> null
            }
            val cards = section?.let { OfflineFeed.cardsHtml(it) } ?: ""

            // Saved content wins over the stored skeleton. The skeleton's
            // own scripts are dead offline and its scroller is a
            // fixed-height window that hides everything beyond it;
            // injecting into it failed on real layouts (content flashed
            // then vanished, reels never scrolled). Serve the saved cards
            // in a plain page that reuses Facebook's own stylesheets from
            // the stored document, so they look exactly as they did online
            // and scroll naturally.
            if (cards.isNotBlank()) {
                val cssLinks = stylesheetLinks(f.readText())
                return shellFor(screen, cards, cssLinks)
            }

            // Nothing saved: serve the stored document untouched.
            val html = promoHideCss() +
                f.readText() +
                unmuteStripScript() +
                offlineAdHideScript() +
                OfflineBanner.html() +
                "<script>" + OfflineNav.script(navigableScreens()) + "</script>" +
                if (screen == "reels" || screen == "watch" || screen == "stories") {
                    "<script>" + VideoHelper.getOfflineVideoAssistScript() + "</script>"
                } else ""

            // Encode once, serve many times.
            val b = html.toByteArray()
            built[screen] = Built(b)

            WebResourceResponse(
                "text/html", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"),
                b.inputStream()
            )
        } catch (e: Exception) {
            null
        }
    }

    /** @see shellFor */

    /**
     * Full-screen story viewer. Stories are MScreen captures, not inline
     * cards, so they are shown one at a time. Left-half tap = previous,
     * right-half tap = next. An overlay hides the page chrome so the
     * story fills the viewport.
     */
    private fun storyViewer(cardsHtml: String, resumeId: String?): String {
        // Same hazard as OfflineInject: a stored card containing "</script>"
        // ends the block early and loses every story after it.
        val stories = cardsHtml
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
            .replace("</script", "</scr` + `ipt")
        val resumeJs = if (resumeId != null) {
            "\nvar START=0;var all=STORIES;for(var i=0;i<all.length;i++){" +
            "if(all[i].indexOf('" + resumeId + "')>=0){START=i;break;}}\n"
        } else "\nvar START=0;\n"
        return """
        (function(){
          if(window.__dbStoryViewer)return;
          window.__dbStoryViewer=true;

          var STORIES = (`$stories`).split('---DBSTORY---').filter(function(s){return s.trim().length>0;});
          if(!STORIES.length)return;
          $resumeJs
          var idx=START;

          var overlay=document.createElement('div');
          overlay.id='__db_story_overlay';
          // position:fixed is measured against the viewport, not against the
          // padding the activity applies to its root view. Online that padding
          // is what keeps content clear of the status bar; a fixed overlay
          // inside the WebView never sees it, so the story sat too high with
          // its top edge under the status bar.
          //
          // env(safe-area-inset-*) is the viewport-level equivalent, but it
          // only resolves when the document asks for viewport-fit=cover, and
          // this overlay is injected into Facebook's own stored markup whose
          // meta tag we do not control. Ensure the meta tag says so first,
          // then the insets resolve; where they still do not, the 0px
          // fallback leaves the previous behaviour untouched.
          try {
            var vp = document.querySelector('meta[name=viewport]');
            if (!vp) {
              vp = document.createElement('meta');
              vp.setAttribute('name', 'viewport');
              vp.setAttribute('content', 'width=device-width,initial-scale=1');
              document.head.appendChild(vp);
            }
            var c = vp.getAttribute('content') || '';
            if (c.indexOf('viewport-fit') === -1) {
              vp.setAttribute('content', c + ',viewport-fit=cover');
            }
          } catch (e) {}

          // Set through a stylesheet rather than the style attribute. An
          // inline declaration is parsed property by property and a value the
          // parser does not recognise is dropped on the spot, which would
          // leave top unset. In a stylesheet the whole rule is handed to the
          // engine, so top falls back cleanly to 0px where env() is unknown
          // and resolves where it is not.
          try {
            var st = document.createElement('style');
            st.textContent =
              '#__db_story_overlay{position:fixed;left:0;right:0;' +
              'z-index:99999;background:#000;overflow:hidden;' +
              'top:0;bottom:0;' +
              'top:env(safe-area-inset-top,0px);' +
              'bottom:env(safe-area-inset-bottom,0px);}';
            document.head.appendChild(st);
          } catch (e) {}
          overlay.style.cssText='position:fixed;left:0;right:0;top:0;bottom:0;'+
            'z-index:99999;background:#000;overflow:hidden;';
          document.body.appendChild(overlay);

          var prevZone=document.createElement('div');
          prevZone.style.cssText='position:absolute;top:0;left:0;width:33%;bottom:0;z-index:1;';
          overlay.appendChild(prevZone);

          var nextZone=document.createElement('div');
          nextZone.style.cssText='position:absolute;top:0;right:0;width:33%;bottom:0;z-index:1;';
          overlay.appendChild(nextZone);

          var content=document.createElement('div');
          content.id='__db_story_content';
          content.style.cssText='position:absolute;top:0;left:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;';
          overlay.appendChild(content);

          var dots=document.createElement('div');
          dots.style.cssText='position:absolute;top:12px;left:0;right:0;text-align:center;z-index:2;';
          overlay.appendChild(dots);

          function updateDots(){
            var h='';
            for(var i=0;i<STORIES.length;i++){
              h+='<span style="display:inline-block;width:8px;height:8px;border-radius:4px;margin:3px;background:'+(i===idx?'#fff':'rgba(255,255,255,0.4)')+'"></span>';
            }
            dots.innerHTML=h;
          }

          function show(n){
            if(n<0||n>=STORIES.length)return;
            idx=n;
            content.innerHTML=STORIES[idx];
            updateDots();
          }

          prevZone.addEventListener('click',function(ev){
            ev.stopPropagation();if(idx>0)show(idx-1);
          });
          nextZone.addEventListener('click',function(ev){
            ev.stopPropagation();if(idx<STORIES.length-1)show(idx+1);
          });

          var close=document.createElement('div');
          close.style.cssText='position:absolute;top:12px;right:16px;z-index:3;color:#fff;font-size:14px;padding:8px;cursor:pointer;';
          close.textContent='\u2715';
          close.addEventListener('click',function(ev){
            ev.stopPropagation();
            overlay.remove();
            window.__dbStoryViewer=false;
          });
          overlay.appendChild(close);

          updateDots();
          show(START);
        })();
        """.trimIndent()
    }


    /**
     * Facebook's own stylesheet <link> tags from a stored document.
     *
     * The stored Facebook document references its real stylesheets on
     * fbcdn.net; those files are already in the offline cache (the asset
     * prefetch stores them), so the saved-cards page can reuse them and the
     * cards look exactly as they did online.
     */
    private fun stylesheetLinks(html: String): String {
        if (html.isBlank()) return ""
        val re = Regex(
            """<link\b[^>]*\brel\s*=\s*["']stylesheet["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        return re.findAll(html)
            .map { rewriteForOffline(it.value) }
            .distinct()
            .take(6)
            .joinToString("\n")
    }

    /**
     * The saved-cards page: every playable saved card in normal document
     * flow, styled by Facebook's own stylesheets, so offline looks like
     * online and everything scrolls naturally — nothing is hidden inside a
     * dead skeleton's fixed-height scroller.
     *
     * This is the primary offline page whenever the store holds saved
     * content, for home, reels and stories alike. The stored Facebook
     * document is only served when there is nothing saved to show.
     */
    private fun shellFor(screen: String, cards: String, cssLinks: String): WebResourceResponse? {
        val section = when (screen) {
            "reels", "watch" -> OfflineFeed.SECTION_REELS
            "stories" -> OfflineFeed.SECTION_STORIES
            else -> OfflineFeed.SECTION_FEED
        }
        val use = if (cards.isNotBlank()) cards else
            listOf(OfflineFeed.SECTION_REELS, OfflineFeed.SECTION_FEED,
                   OfflineFeed.SECTION_STORIES)
            .firstNotNullOfOrNull { s ->
                OfflineFeed.cardsHtml(s).takeIf { it.isNotBlank() }
            } ?: return offlineFallbackPage()

        // Cached like the document path; invalidated whenever the store
        // changes, so a hit here is always fresh.
        built[screen]?.let { b ->
            return WebResourceResponse(
                "text/html", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"),
                b.bytes.inputStream()
            )
        }

        val html = "<!DOCTYPE html><html lang=\"en\"><head>" +
            "<meta charset=\"utf-8\"><meta name=\"viewport\" " +
            "content=\"width=device-width,initial-scale=1\">" +
            cssLinks +
            "<style>body{margin:0;background:#18191a;color:#e4e6eb;" +
            "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto," +
            "sans-serif}img,video{max-width:100%}</style>" +
            promoHideCss() +
            "</head><body><div>" + use + "</div>" +
            offlineAdHideScript() +
            unmuteStripScript() +
            OfflineBanner.html() +
            "<script>" + OfflineNav.script(navigableScreens()) + "</script>" +
            (if (screen == "reels" || screen == "watch" || screen == "stories") {
                "<script>" + VideoHelper.getOfflineVideoAssistScript() +
                    "</script>"
            } else "") +
            (if (screen == "stories") {
                "<script>" + storyViewer(use.replace("\n", "\n---DBSTORY---\n"), null) + "</script>"
            } else "") +
            "</body></html>"

        val b = html.toByteArray()
        built[screen] = Built(b)
        return WebResourceResponse("text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), b.inputStream())
    }

    /**
     * Hides advertising that was captured inside the stored document or a
     * saved card, without the online ad remover (which must not run on
     * offline pages — it has hidden saved content before).
     *
     * Finds an element whose visible text is exactly the "Sponsored" label,
     * then hides the story card that carries it. Only hides; nothing is
     * removed, so the page can never be broken by it.
     */
    private fun offlineAdHideScript(): String = """
        <script id="__db_off_ad_hide">
        (function(){
          if (window.__dbOffAdHide) return;
          window.__dbOffAdHide = true;
          var labels = Array.prototype.slice.call(
            document.querySelectorAll('div,span,a'));
          for (var i = 0; i < labels.length; i++) {
            var el = labels[i];
            var t = (el.innerText || el.textContent || '').trim();
            if (t.toLowerCase() !== 'sponsored') continue;
            var card = el.closest(
              '[data-tracking-duration-id],[data-video-id],[data-story-id]');
            if (card) { card.style.display = 'none'; continue; }
            var sc = el.closest('[data-type="vscroller"]');
            if (sc && el.parentNode) {
              var p = el.parentNode;
              if (p !== sc && p.parentNode === sc) p.style.display = 'none';
            }
          }
        })();
        </script>
    """.trimIndent()

    /**
     * Store a page captured from a live WebView.
     *
     * This is the only way these documents can be obtained. Facebook answers
     * m.facebook.com with HTTP 400 to any plain HTTP client - verified against
     * the live site with five different header combinations, logged out and
     * with a cookie, and every one was refused. [fetchScreen] therefore never
     * stored anything, savedScreens() stayed empty, and going offline showed
     * the bare "Can't load the page" screen with no saved content at all.
     *
     * A real WebView is not refused, and [OfflineSync] already runs one that
     * is signed in and has these very screens open, so the document is taken
     * from there instead of re-requesting a URL that will not be answered.
     */
    fun storeFromPage(screen: String, html: String): Boolean {
        if (!writeEnabled) return false
        val f = fileFor(screen) ?: return false
        if (html.length < MIN_DOC_BYTES) {
            outcomes[screen] = "tiny${html.length / 1024}k"
            return false
        }
        if (html.length > MAX_DOC_BYTES) {
            outcomes[screen] = "size${html.length / 1024}k"
            return false
        }
        // A logged-out page must never be stored: serving it offline would
        // show the login screen to a signed-in user.
        if (html.contains("name=\"login\"", true) &&
            html.contains("type=\"password\"", true)
        ) {
            outcomes[screen] = "loggedout"
            return false
        }
        // Reject Facebook error pages. Bare paths like /reel/ or /stories/
        // without an ID return this page, and storing it makes offline show
        // "The link you followed may be broken" for every request.
        if (html.contains("The link you followed may be broken", true)) {
            outcomes[screen] = "brokenlink"
            return false
        }

        return try {
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(rewriteForOffline(html))
            if (tmp.renameTo(f)) {
                outcomes[screen] = "ok${html.length / 1024}k"
                true
            } else {
                tmp.delete()
                outcomes[screen] = "writefail"
                false
            }
        } catch (e: Exception) {
            outcomes[screen] = e.javaClass.simpleName
            false
        }
    }

    /**
     * Fetch and store the screens the user has enabled.
     *
     * Runs entirely on [pool]. The request carries the session cookies, so
     * what we store is the signed-in page, not a logged-out one.
     */
    /**
     * Kept for the screens a WebView never visits.
     *
     * Note that m.facebook.com answers HTTP 400 to a plain client, so this
     * will not succeed there - the pages that matter are captured from a live
     * WebView by [storeFromPage] instead. This is left in place because it
     * costs nothing when it fails and still records an outcome, which is what
     * the diagnostics line reports.
     */
    fun refresh(screens: List<String> = SCREENS.keys.toList(), force: Boolean = false) {
        if (!writeEnabled) return
        if (inFlight.get() > 0) return
        for (screen in screens) {
            val url = SCREENS[screen] ?: continue
            if (!force && !isStale(screen)) continue
            inFlight.incrementAndGet()
            pool.execute {
                try {
                    fetchScreen(screen, url)
                } catch (e: Exception) {
                    outcomes[screen] = e.javaClass.simpleName
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    fun isRefreshing(): Boolean = inFlight.get() > 0

    fun statusLine(): String {
        if (SCREENS.keys.none { outcomes.containsKey(it) }) return "not run yet"
        return SCREENS.keys.joinToString(", ") { s ->
            s + "=" + (outcomes[s] ?: "-")
        }
    }

    private fun fetchScreen(screen: String, url: String) {
        val f = fileFor(screen) ?: return
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 25_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                userAgent?.let { setRequestProperty("User-Agent", it) }
                setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
                CookieManager.getInstance().getCookie(url)?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            if (conn.responseCode != 200) {
                outcomes[screen] = "http${conn.responseCode}"
                return
            }
            val enc = conn.getHeaderField("Content-Encoding")
            if (enc != null && !enc.equals("identity", true)) {
                outcomes[screen] = "encoded"
                return
            }
            val type = conn.contentType?.lowercase(Locale.ROOT) ?: ""
            if (!type.contains("html")) {
                outcomes[screen] = "nothtml"
                return
            }

            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_DOC_BYTES) {
                outcomes[screen] = "size${bytes.size / 1024}k"
                return
            }
            if (bytes.size < MIN_DOC_BYTES) {
                outcomes[screen] = "tiny${bytes.size / 1024}k"
                return
            }

            var html = String(bytes, Charsets.UTF_8)
            if (html.contains("name=\"login\"", true) &&
                html.contains("type=\"password\"", true)
            ) {
                outcomes[screen] = "loggedout"
                return
            }

            html = rewriteForOffline(html)

            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(html)
            if (tmp.renameTo(f)) {
                outcomes[screen] = "ok${bytes.size / 1024}k"
            } else {
                tmp.delete()
                outcomes[screen] = "writefail"
            }
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /**
     * Make the stored copy absolute.
     *
     * A stored document is replayed at whatever URL the user navigated to, so
     * root-relative asset paths would resolve differently and miss the cache.
     */
    private fun rewriteForOffline(html: String): String = html
        .replace("src=\"/", "src=\"https://m.facebook.com/")
        .replace("href=\"/", "href=\"https://m.facebook.com/")
        .replace("src='\\/", "src='https://m.facebook.com/")
        .replace("href='\\/", "href='https://m.facebook.com/")

    /** Every media URL a stored page references, for the asset prefetch. */
    fun mediaUrls(screen: String): List<String> {
        val f = fileFor(screen) ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val html = f.readText()
            val re = Regex("""https://[^"'\s\\\)]+?(?:fbcdn\.net|fbsbx\.com)[^"'\s\\\)]*""")
            val all = re.findAll(html)
                .map { it.value.replace("&amp;", "&") }
                .distinct()
                .toList()

            val (chrome, media) = all.partition { it.contains("/rsrc.php/") }

            val fonts = chrome.filter { it.contains(".css") || !it.contains(".") }
                .asSequence()
                .mapNotNull { OfflineCache.textOf(it) }
                .flatMap { css -> CSS_URL.findAll(css) }
                .map { it.groupValues[1].replace("&amp;", "&") }
                .filter { it.startsWith("https://") }
                .distinct()
                .take(80)
                .toList()

            val (avatars, photos) = media.partition {
                it.contains("/t39.30808-1/") || it.contains("_n.jpg?stp=c")
            }
            (fonts + chrome + avatars + photos).distinct().take(MAX_PREFETCH_URLS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun sizeBytes(): Long {
        val dir = root ?: return 0
        return try { dir.listFiles()?.sumOf { it.length() } ?: 0 } catch (e: Exception) { 0 }
    }

    fun clear() {
        invalidate()
        val dir = root ?: return
        try { dir.listFiles()?.forEach { it.delete() } } catch (e: Exception) {}
    }

    /**
     * Story pager: shows stored story cards one at a time with tap-based
     * navigation (left half = previous, right half = next). Stories are
     * full MScreen captures, not inline cards, so they cannot be injected
     * alongside feed content.
     */
    


    /**
     * Removes Facebook's "Tap to unmute" overlay from a served page.
     *
     * Stripping it at capture time was necessary but not sufficient. It only
     * cleans markup captured *after* the fix, so every page already on disk
     * still carried the overlay, and the stored full document -- which is a
     * verbatim copy of Facebook's page, not something we assemble -- was never
     * passed through the capture path at all.
     *
     * Doing it in the page covers both: the element is removed from the live
     * DOM whatever produced it, and again whenever Facebook's own markup
     * re-inserts one. A MutationObserver is used rather than a one-shot sweep
     * because the offline video assist swaps elements in after load.
     */
    private fun unmuteStripScript(): String = """
        <script id="__db_unmute_strip">
        (function(){
          if (window.__dbUnmuteStrip) return;
          window.__dbUnmuteStrip = true;

          var SEL = '[data-sigil~="m-video-overlay"],[data-sigil*="m-video-overlay"]';

          function textLooksLikeUnmute(el) {
            var t = (el.textContent || '').trim().toLowerCase();
            if (!t || t.length > 40) return false;
            return t.indexOf('unmute') !== -1 || t.indexOf('tap to') === 0;
          }

          function sweep() {
            var n;
            try { n = document.querySelectorAll(SEL); } catch (e) { return; }
            for (var i = 0; i < n.length; i++) {
              var el = n[i];
              if (el.parentNode) el.parentNode.removeChild(el);
            }
            // Facebook does not always tag it. Catch the label by its own
            // text, but only on small leaf-ish nodes so a real caption
            // mentioning the word is never removed.
            var spans = document.querySelectorAll('div,span');
            for (var j = 0; j < spans.length && j < 3000; j++) {
              var s = spans[j];
              if (s.children.length > 2) continue;
              if (!textLooksLikeUnmute(s)) continue;
              var box = s.closest ? s.closest('[data-sigil]') : null;
              var target = box && textLooksLikeUnmute(box) ? box : s;
              if (target.parentNode) target.parentNode.removeChild(target);
            }
          }

          sweep();
          try {
            // Debounced, and only when nodes were actually added.
            //
            // Running the sweep on every mutation meant walking up to 3000
            // elements each time, and scrolling produces mutations
            // continuously — so the scan was competing with the scroll for
            // the whole gesture. Coalescing to one pass per idle moment does
            // the same job without being in the way.
            var pending = 0;
            function schedule() {
              if (pending) return;
              pending = setTimeout(function(){ pending = 0; sweep(); }, 400);
            }
            new MutationObserver(function(muts){
              for (var i = 0; i < muts.length; i++) {
                if (muts[i].addedNodes && muts[i].addedNodes.length) {
                  schedule();
                  return;
                }
              }
            }).observe(document.documentElement, {childList:true, subtree:true});
          } catch (e) {}
          document.addEventListener('DOMContentLoaded', sweep);
        })();
        </script>
    """.trimIndent()

    /**
     * Hides app-promotion banners before the stored page paints.
     *
     * Online this is done from onPageStarted, before first paint. An offline
     * page is answered by shouldInterceptRequest, so that hook has already
     * run against the previous document and everything we append lands
     * *after* the stored markup — by which time the "Open in app" bar has
     * been on screen for a frame. That is the flash: it appeared, then the
     * scripted sweep removed it.
     *
     * Prepending a plain stylesheet fixes it. The rule is parsed before the
     * body it applies to, so the bar never gets a frame to paint in, and no
     * script has to run first.
     */
    private fun promoHideCss(): String = """
        <style id="__db_promo_hide">
        #header-notices,div[id^="header-notice"],
        [data-testid*="app_download"],[data-testid*="install_app"],
        [data-testid*="open_in_app"],[data-testid*="app_upsell"],
        [data-nt="FB:APP_INSTALL"],[data-sigil*="app_install"],
        [data-sigil*="appinstall"],[data-sigil*="mUpsellBanner"],
        #mobile_app_install_banner,#appManifestBanner,#MComposerAppInstallBanner,
        a[href*="play.google.com/store"],a[href*="apps.apple.com"],
        a[href^="market://"],a[href^="fb://"],a[href^="intent://"],
        a[href*="/mobile/download"],a[href*="messenger.com/download"],
        .mobile-app-banner,.app-install-banner,.app-download-banner,
        .smartbanner,.smart-banner,.get-app-banner
        {display:none !important;}
        </style>
    """.trimIndent()

    /** Basic page served when there is literally nothing saved. */
    private fun offlineFallbackPage(): WebResourceResponse {
        val html = "<!DOCTYPE html><html lang=\"en\"><head>" +
            "<meta charset=\"utf-8\"><meta name=\"viewport\" " +
            "content=\"width=device-width,initial-scale=1\">" +
            "<style>body{margin:0;background:#18191a;color:#e4e6eb;" +
            "font-family:sans-serif;display:flex;align-items:center;" +
            "justify-content:center;height:100vh;text-align:center}" +
            "</style></head><body><div><h2>No saved content</h2>" +
            "<p>Nothing has been downloaded for offline yet.</p>" +
            "<p>Open Facebook with a connection first.</p></div>" +
            "</body></html>"
        return WebResourceResponse("text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), html.byteInputStream())
    }
}
