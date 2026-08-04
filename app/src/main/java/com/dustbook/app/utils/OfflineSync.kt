package com.dustbook.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/**
 * Fills the offline store on its own.
 *
 * Capturing only what the user scrolled past means fifty saved reels costs the
 * user watching fifty reels, which is not what "keep 50 reels offline" should
 * mean. This runs an offscreen WebView, signed in with the same cookies, that
 * loads the reels screen and pages through it until the target is reached,
 * then downloads the media.
 *
 * It is deliberately conservative:
 *  - only ever runs while online, and never while the user is mid-session
 *  - one run at a time, throttled to [MIN_INTERVAL_MS]
 *  - the WebView is destroyed as soon as the pass ends or times out
 *  - it never touches the visible WebView, so it cannot disturb the feed
 */
object OfflineSync {

    // V4: Much more aggressive for proactive background preparation
    private const val MIN_INTERVAL_MS = 90_000L          // 1.5 minutes (was 5 min)
    private const val TIMEOUT_MS = 180_000L              // 3 minutes

    /** How long to wait before fetching another batch. */
    private const val ROUND_GAP_MS = 12_000L             // Faster round cycling

    /** V4: Higher ceiling so we can reach 200+ fresh items */
    private const val MAX_ROUNDS = 18

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var lastRun = 0L
    @Volatile private var rounds = 0

    /** Reels stored by the most recent run, for the settings screen. */
    @Volatile var lastResult: Int = 0
        private set

    fun isRunning(): Boolean = running

    /**
     * Fill every section the user turned on, one after another.
     *
     * There is no button for this. "Keep reels offline" means the reels are
     * there when the user opens the app with no signal, so the app fetches
     * them itself rather than making the user scroll through fifty reels
     * first.
     */
    fun runAll(
        context: Context,
        sections: List<String>,
        target: Int,
        includeVideo: Boolean,
        force: Boolean = false,
        onDone: (Int) -> Unit = {}
    ) {
        if (sections.isEmpty() || target <= 0) return
        if (!canRun(force)) return

        // Sections run in series: two offscreen WebViews competing for the
        // network would make both passes slower and neither complete.
        fun step(i: Int, total: Int) {
            if (i >= sections.size) {
                onDone(total)
                // Keep going until the target is actually met.
                //
                // A single pass stops when the page stops producing new cards,
                // which is usually well short of fifty, and the old code then
                // waited fifteen minutes before trying again. Opening the app
                // and leaving it therefore saved a handful of reels and no
                // more. Loading the screen afresh gets a new batch from
                // Facebook, so go round again until there is enough.
                val short = sections.any {
                    OfflineFeed.realPlayableCount(it) < targetFor(it, target)
                }
                if (short && rounds < MAX_ROUNDS) {
                    rounds++
                    main.postDelayed({
                        runAll(context, sections, target, includeVideo,
                               force = true, onDone = onDone)
                    }, ROUND_GAP_MS)
                } else {
                    rounds = 0
                }
                return
            }
            run(context, sections[i], target, includeVideo, force = true) { n ->
                step(i + 1, total + n)
            }
        }
        step(0, 0)
    }

    /** V4: Higher targets for fresh content. */
    /**
     * How many items this pass is trying to reach.
     *
     * The caller's number, unchanged. It used to be raised to the V4 constants
     * — 500 posts, 200 reels — which silently overrode every request. Step 1
     * of the pipeline asks for 50 posts and then hands over to reels, but with
     * the target inflated to 500 it kept looping on posts and the handover
     * never happened: posts climbed past 50 and no reel was ever fetched.
     *
     * A caller that wants a larger goal can simply ask for one.
     */
    private fun targetFor(section: String, target: Int): Int = target

    fun canRun(force: Boolean): Boolean {
        if (running) return false
        if (force) return true
        return System.currentTimeMillis() - lastRun > MIN_INTERVAL_MS
    }

    /**
     * @param section  which screen to fill, from [OfflineFeed].
     * @param target   how many items to aim for.
     * @param onDone   called on the main thread with the count captured.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(
        context: Context,
        section: String,
        target: Int,
        includeVideo: Boolean,
        force: Boolean = false,
        onDone: (Int) -> Unit = {}
    ) {
        if (target <= 0) return
        if (!canRun(force)) return
        if (!UrlHelper.isLoggedIn()) return
        // The offscreen WebView loads real Facebook pages, so this costs data
        // even before any media is fetched.
        if (!NetworkPolicy.canDownload(context, Prefs(context))) return

        running = true
        lastRun = System.currentTimeMillis()

        main.post {
            var web: WebView? = null
            var finished = false

            fun finish(count: Int) {
                if (finished) return
                finished = true
                running = false
                lastResult = count
                try {
                    web?.stopLoading()
                    web?.destroy()
                } catch (e: Exception) {}
                web = null
                onDone(count)
            }

            try {
                val w = WebView(context.applicationContext)
                web = w
                // Offscreen but laid out, otherwise the page reports a zero
                // viewport and Facebook never renders any cards to capture.
                w.layout(0, 0, 1080, 1920)

                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true

                    // Images must load here, however tempting it is to skip
                    // them for speed.
                    //
                    // Blocking them was tried and reverted: Facebook
                    // lazy-loads feed images, so an <img> only carries its
                    // real fbcdn URL once the renderer has decided to fetch
                    // it. With loading blocked the tags stay empty, capture
                    // collects no media URLs at all, and every saved post
                    // comes back offline as text with blank spaces where the
                    // photos should be.
                    loadsImagesAutomatically = true
                    blockNetworkImage = false

                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = true
                    userAgentString = userAgentString.replace(" wv", "")
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

                w.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onOfflineItems(sec: String, json: String, done: Boolean) {
                        val items = parseItems(json)
                        if (items.isEmpty()) return

                        // Filter out items the store already holds.
                        // Without this the sync saves every card
                        // the page shows, including what the user
                        // just watched — which is why the same
                        // reels kept appearing offline.
                        val knownSet = OfflineFeed.knownIds(sec).toSet()
                        val newItems = items.filter {
                            it.id.isBlank() || it.id !in knownSet
                        }
                        if (newItems.isEmpty()) return

                        OfflineFeed.addItems(sec, newItems, target)
                        OfflineFeed.prefetch(newItems, includeVideo)
                    }

                    @JavascriptInterface
                    fun onOfflinePage(sec: String, html: String) {
                        // The only place these documents can come from:
                        // Facebook refuses a plain HTTP fetch of them.
                        OfflineDocs.storeFromPage(
                            when (sec) {
                                OfflineFeed.SECTION_REELS -> "reels"
                                OfflineFeed.SECTION_STORIES -> "stories"
                                else -> "home"
                            },
                            html
                        )
                    }

                    @JavascriptInterface
                    fun onSyncDone(count: Int) {
                        main.post {
                            // Give the download pool the final list before we
                            // tear the page down.
                            OfflineFeed.prefetch(
                                OfflineFeed.loadItems(section), includeVideo
                            )
                            finish(count)
                        }
                    }
                }, "FBPro")

                w.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        request ?: return null
                        if (AdBlocker.shouldBlockRequest(request)) {
                            return AdBlocker.createEmptyResponse()
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(MFacebookAds.script(), null)
                        // V4 Step 2: Use higher target + syncMode to fetch fresher content
                        // Use exactly the requested target: a user who asked
                        // to keep 30 reels must not be made to page through
                        // 150 of them.
                        view?.evaluateJavascript(
                            OfflineCapture.script(
                                target,
                                syncMode = true,
                                // Skip what is already held, so each pass
                                // reaches content the user has not seen.
                                knownIds = OfflineFeed.knownIds(section)
                            ),
                            null
                        )
                    }
                }

                val start = when (section) {
                    OfflineFeed.SECTION_REELS -> "https://m.facebook.com/reel/"
                    OfflineFeed.SECTION_STORIES -> "https://m.facebook.com/stories/"
                    else -> "https://m.facebook.com/home.php"
                }
                w.loadUrl(start)

                // Hard stop, so a stalled page can never leak a WebView.
                main.postDelayed({
                    finish(OfflineFeed.realPlayableCount(section))
                }, TIMEOUT_MS)
            } catch (e: Exception) {
                finish(0)
            }
        }
    }

    /** Parse what the page reported: Facebook's markup, plus its media URLs. */
    fun parseItems(json: String): List<OfflineFeed.Item> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val html = o.optString("h", "")
            if (html.isBlank()) return@mapNotNull null
            val m = o.optJSONArray("m")
            val media = if (m == null) emptyList() else
                (0 until m.length()).mapNotNull { j -> m.optString(j, null) }
            OfflineFeed.Item(
                id = o.optString("id", ""),
                html = html,
                media = media
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
