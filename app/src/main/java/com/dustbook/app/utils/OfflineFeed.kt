package com.dustbook.app.utils

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline content, so the app behaves like the native Facebook app rather
 * than a browser tab.
 *
 * [OfflineCache] keeps individual assets, but that alone is not enough: with
 * no connection the main-frame request fails, the WebView shows its error
 * page, and the cached images are never asked for.
 *
 * An earlier version tried to fix that by storing Facebook's rendered HTML.
 * That never worked - the feed is megabytes of markup, so nothing was ever
 * stored, and "View saved content" opened a blank page.
 *
 * What we store instead is a small structured list: for each post or reel, the
 * media URL, a caption and a permalink. The media is downloaded into
 * [OfflineCache]; the screen the user sees offline is rendered by us from that
 * list. Predictable size, and it works without any of Facebook's scripts.
 */
object OfflineFeed {

    const val SECTION_FEED = "feed"
    const val SECTION_REELS = "reels"
    const val SECTION_STORIES = "stories"

    private val SECTIONS = setOf(SECTION_FEED, SECTION_REELS, SECTION_STORIES)

    private const val DIR = "offline_items_v1"
    private const val MIN_VIDEO_BYTES = 50_000L

    private val pool = Executors.newFixedThreadPool(3)
    private val busy = AtomicBoolean(false)

    /** Media waiting to be fetched, and what is already in the queue. */
    private val queue = ArrayList<String>()
    private val queued = HashSet<String>()

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


    /** Media stored by the last prefetch pass, shown in hidden settings. */
    @Volatile var lastStored: Int = 0
        private set

    /**
     * One saved story, as Facebook's own markup.
     *
     * Earlier versions held separate fields - author, caption, counts - and
     * rebuilt a card from them. A rebuilt card can only ever contain what
     * somebody remembered to capture, which is why Like, Comment and Share
     * were missing from saved posts. The real `outerHTML` is stored instead,
     * so nothing can be left out: it is the original markup.
     *
     * @param id    a stable identity, so the same story is not stored twice.
     * @param html  Facebook's own markup for the card.
     * @param media every URL it references, which must be on disk for it to
     *              render offline.
     */
    data class Item(
        val id: String,
        val html: String,
        val media: List<String>
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            val d = File(context.filesDir, DIR)
            if (!d.exists()) d.mkdirs()
            root = d
        }
    }

    private fun fileFor(section: String): File? {
        val dir = root ?: return null
        if (!SECTIONS.contains(section)) return null
        return File(dir, "$section.json")
    }

    fun sectionForUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = UrlHelper.hostOf(url) ?: return null
        if (!host.endsWith("facebook.com")) return null
        val path = try {
            Uri.parse(url).path?.lowercase(Locale.ROOT) ?: ""
        } catch (e: Exception) {
            return null
        }
        val first = path.trim('/').substringBefore('/')
        return when {
            first == "stories" || first == "story" -> SECTION_STORIES
            first == "reel" || first == "reels" || first == "watch" -> SECTION_REELS
            first.isEmpty() || first == "home.php" -> SECTION_FEED
            else -> null
        }
    }

    // ------------------------------------------------------------------ store

    /**
     * V4 Step 2: Merge newly captured items into the store.
     * Improved deduplication using multiple keys to avoid storing the same
     * already-consumed content.
     */
    fun addItems(section: String, incoming: List<Item>, limit: Int) {
        if (!enabled || incoming.isEmpty()) return
        val f = fileFor(section) ?: return
        synchronized(this) {
            val existing = loadItems(section)
            val seen = HashSet<String>()

            // For reels: only keep items that reference actual video.
            // A card with only images is not a playable reel.
            val filtered = if (section == SECTION_REELS) {
                incoming.filter { it.media.any { u -> isVideoUrl(u) } }
            } else incoming

            val merged = ArrayList<Item>(limit)

            fun keyFor(it: Item): String {
                if (it.id.isNotBlank()) return it.id
                val mediaKey = it.media.firstOrNull() ?: ""
                val textKey = it.html.take(180)
                return "$mediaKey|$textKey"
            }

            for (it in filtered + existing) {
                val key = keyFor(it)
                if (!seen.add(key)) continue
                merged.add(it)
                if (merged.size >= limit) break
            }

            val arr = JSONArray()
            for (it in merged) {
                val m = JSONArray()
                for (u in it.media) m.put(u)
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("h", it.html)
                        .put("m", m)
                )
            }
            try {
                val tmp = File(f.parentFile, f.name + ".part")
                tmp.writeText(arr.toString())
                if (tmp.renameTo(f)) {
                    // The assembled offline page embeds these cards, so it is
                    // now stale and must be rebuilt on the next request.
                    OfflineDocs.invalidate()
                } else {
                    tmp.delete()
                }
            } catch (e: Exception) {
                // out of space: the previous list stays usable
            }
        }
    }

    /**
     * Identities of everything already stored for a section.
     *
     * Handed to the capture script so it can skip cards we already hold.
     * Without it the same posts were captured, sent over the bridge and
     * re-queued on every pass - the store de-duplicated them, but the work
     * had already been done and no new content was reached.
     */
    fun knownIds(section: String): List<String> =
        loadItems(section).map { it.id }.filter { it.isNotBlank() }

    fun loadItems(section: String): List<Item> {
        val f = fileFor(section) ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val html = o.optString("h", "")
                if (html.isBlank()) return@mapNotNull null
                val m = o.optJSONArray("m")
                val media = if (m == null) emptyList() else
                    (0 until m.length()).mapNotNull { j -> m.optString(j, null) }
                Item(
                    id = o.optString("id", ""),
                    html = html,
                    media = media
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Items whose media is actually on disk - the only ones worth showing. */
    /**
     * Items whose media is on disk. A card with no media at all - a text post -
     * is always viewable, so it counts too.
     */
    /** 
     * V4: Items that can actually be shown offline.
     */
    fun playableItems(section: String): List<Item> =
        loadItems(section).filter { item ->
            item.media.isEmpty() || item.media.any { OfflineCache.has(it) }
        }

    fun playableCount(section: String): Int = playableItems(section).size

    /**
     * Items that are genuinely usable offline. This is the filter used when
     * actually rendering stored content to the user.
     *
     * For reels: the video must be cached. A reel whose only cached asset is
     * a 4 KB avatar is not playable and should not appear in the feed.
     * For feed/stories: at least one non-avatar asset must be cached (or the
     * item has no media at all, i.e. a text post).
     */
    /**
     * Advertising is never content: a saved card whose markup carries the
     * "Sponsored" label is not shown offline and does not count toward the
     * saved total. This applies at display/count time, so items captured
     * before the capture-side skip are filtered here too.
     */
    private fun isSponsored(item: Item): Boolean =
        item.html.contains("sponsored", ignoreCase = true)

    fun realPlayableItems(section: String): List<Item> =
        loadItems(section).filter { !isSponsored(it) && isFullyDownloaded(it) }

    /**
     * True when everything this item needs is already on disk.
     *
     * The old rule was `any {}`: one cached asset was enough. A post with five
     * photos counted as saved when one had arrived, so the number climbed
     * while the content behind it was still downloading and could fall again
     * on the next pass. An item is either ready to read offline or it is not.
     *
     * Every media URL must therefore be present, with two qualifications that
     * are about correctness rather than leniency:
     *
     *  - a video must also be a plausible size. A truncated or still-writing
     *    file exists but does not play, and counting it is the same mistake in
     *    a different place.
     *  - an item carrying no media at all — a text post — is complete as soon
     *    as its markup is stored, because there is nothing else to fetch.
     */
    fun isFullyDownloaded(item: Item): Boolean {
        if (item.media.isEmpty()) return true

        val videos = item.media.filter { isVideoUrl(it) }
        val images = item.media.filter { !isVideoUrl(it) }

        // A video item is ready when its video is really on disk. Requiring
        // every URL was too strict and left reels permanently "incomplete":
        // capture records every srcset variant of the poster image, and only
        // the one the renderer actually chose is ever fetched, so the rest
        // could never arrive.
        if (videos.isNotEmpty()) {
            return videos.any { OfflineCache.hasMinSize(it, MIN_VIDEO_BYTES) }
        }

        // A picture post is ready when its pictures are here. Same reasoning:
        // the alternates are alternates, not additional content, so one cached
        // image per item is what "the photo is saved" actually means. This is
        // still far stricter than the old rule, which counted an item whose
        // only cached file was a 4 KB avatar.
        if (images.any { OfflineCache.has(it) && !isAvatar(it) }) return true
        if (images.all { OfflineCache.has(it) }) return true

        // Nothing here is content.
        //
        // Capture records every <img> inside a card, and on a plain text post
        // the only images are the author's avatar and any emoji in the body.
        // The rule above then read that as "this item has media and none of it
        // arrived" and hid the post - so a feed of ordinary text updates came
        // back offline as a handful of items instead of the fifty that were
        // saved. There is nothing to wait for on such a post: the words are in
        // the stored markup already.
        if (images.all { isAvatar(it) || isChrome(it) }) return true

        return false
    }

    /**
     * Interface furniture rather than post content: emoji sprites, static
     * icons and spacers. These come from Facebook's static host, not the
     * content CDN, and a post is perfectly readable without them.
     */
    private fun isChrome(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase(Locale.ROOT)
        return clean.contains("/emoji.php/") ||
            clean.contains("static.xx.fbcdn.net") ||
            clean.contains("/rsrc.php/") ||
            clean.endsWith(".svg")
    }

    /**
     * Profile pictures and other chrome, which arrive first and are tiny.
     *
     * Counting an item because its avatar downloaded is the mistake this
     * whole rule exists to avoid.
     */
    private fun isAvatar(url: String): Boolean {
        val clean = url.substringBefore('?')
        return clean.contains("/t39.30808-1/") ||
            (clean.contains("profile") && clean.contains("_s."))
    }

    /**
     * Strict count: only counts an item when its *primary* media is on disk.
     *
     * For reels this means the video must be cached; for feed and stories it
     * means at least one non-avatar asset (photo or video) is cached. A text
     * post with no media always counts.
     *
     * This is the real number shown to the user. The old count reported "50
     * reels saved" when only 4 KB avatars were on disk while the 8 MB videos
     * were still queued. A reel you cannot watch is not saved.
     */
    fun realPlayableCount(section: String): Int =
        loadItems(section).count { isFullyDownloaded(it) }


    /** V4 helper */
    fun freshCount(section: String): Int = realPlayableCount(section)

    /** V4: Total stored items (even if media not yet downloaded) */
    /**
     * Cheap "is there anything here at all" test.
     *
     * Deliberately does not parse. Callers on the WebView's resource thread
     * only need to know whether a section holds content; reading and parsing
     * the JSON there, four times per served page, is what made assembling an
     * offline page slow enough to stall.
     */
    fun storedCount(section: String): Int {
        val f = fileFor(section) ?: return 0
        return try {
            if (f.exists() && f.length() > 2L) 1 else 0
        } catch (e: Exception) {
            0
        }
    }

    fun totalStored(section: String): Int = loadItems(section).size

    fun hasAnything(): Boolean =
        realPlayableCount(SECTION_REELS) > 0 || realPlayableCount(SECTION_FEED) > 0 ||
            realPlayableCount(SECTION_STORIES) > 0

    // --------------------------------------------------------------- download

    /**
     * Download the media these items need. Returns immediately; work happens
     * on a small pool. A second call while a pass is running is dropped, so a
     * scrolling user cannot pile up threads.
     */
    fun prefetch(items: List<Item>, includeVideo: Boolean) {
        prefetchUrls(items.flatMap { it.media }.distinct(), includeVideo)
    }

    /**
     * Download a list of media URLs into [OfflineCache].
     *
     * Returns immediately; the work happens on a small pool. A second call
     * while a pass is running is dropped rather than queued, so a scrolling
     * user cannot pile up hundreds of connections.
     */
    fun prefetchUrls(urls: List<String>, includeVideo: Boolean) {
        if (!enabled || urls.isEmpty()) return
        if (!downloadAllowed()) return

        // Queue, never drop.
        //
        // This used to refuse a second call while a pass was running, so
        // everything after the first batch was silently thrown away - which is
        // why only a handful of reels were ever downloaded no matter how long
        // the app was left open. The work is queued now and a single worker
        // drains it.
        synchronized(queue) {
            for (u in urls) {
                if (!includeVideo && isVideoUrl(u)) continue
                if (queued.add(u)) queue.add(u)
            }
        }
        drain()
    }

    /**
     * Whether the current connection may be used for saving.
     *
     * Defaults to allowing it when the context is not yet known, so a missing
     * init() cannot silently disable downloading altogether.
     */
    private fun downloadAllowed(): Boolean {
        val c = appContext ?: return true
        return NetworkPolicy.canDownload(c, Prefs(c))
    }

    /** One worker drains the queue; further calls just add to it. */
    private fun drain() {
        if (!busy.compareAndSet(false, true)) return
        pool.execute {
            var stored = 0
            try {
                while (enabled) {
                    // Re-checked every item, not once at the start: a user can
                    // walk out of Wi-Fi range mid-pass, and a queue of reels
                    // would otherwise keep pulling video over mobile data.
                    if (!downloadAllowed()) break
                    val u = synchronized(queue) {
                        if (queue.isEmpty()) null else queue.removeAt(0)
                    } ?: break
                    if (OfflineCache.has(u)) continue
                    if (fetchInto(u)) {
                        stored++
                        downloaded++
                    }
                }
            } catch (e: Exception) {
                // Network died mid-pass: whatever was stored is still valid.
            } finally {
                lastStored = stored
                // An item only becomes complete when its last file lands, and
                // that happens here, not when its metadata was written. The
                // assembled page was built before those bytes existed, so it
                // kept serving the smaller set: the count said six reels and
                // three were on screen, and pull-to-refresh redisplayed the
                // same stale page. Rebuild it now that more is genuinely
                // playable.
                if (stored > 0) OfflineDocs.invalidate()
                busy.set(false)
                // Anything added while we were finishing must not be stranded.
                val more = synchronized(queue) { queue.isNotEmpty() }
                if (more && enabled) drain()
            }
        }
    }

    /** Files fetched since the app started, for the settings screen. */
    @Volatile var downloaded: Int = 0
        private set

    fun pending(): Int = synchronized(queue) { queue.size }

    private fun isVideoUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase(Locale.ROOT)
        return clean.endsWith(".mp4") || clean.endsWith(".webm") ||
            clean.contains("/v/t2/")
    }

    fun isPrefetching(): Boolean = busy.get()

    /**
     * Block until the current download pass finishes, or the timeout expires.
     *
     * Used only to sequence the two asset sweeps: the icon font can only be
     * discovered by reading a stylesheet, and a stylesheet can only be read
     * once it has finished downloading. Never called from the UI thread.
     */
    fun awaitPrefetch(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!busy.get() && pending() == 0) return
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun looksLikeMedia(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val h = UrlHelper.hostOf(url) ?: return false
        if (!(h.endsWith("fbcdn.net") || h.endsWith("fbsbx.com"))) return false
        return !BlockList.blocksHost(h)
    }

    /**
     * Fetch one asset into [OfflineCache].
     *
     * Encoding matters: an earlier build forwarded Accept-Encoding, then
     * replayed gzip bytes without Content-Encoding, and the WebView read
     * compressed data as text - a blank screen. We ask for identity and refuse
     * anything that still arrives encoded.
     */
    private fun fetchInto(url: String): Boolean {
        if (!looksLikeMedia(url)) {
            if (!isVideoUrl(url)) return false
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (conn.responseCode != 200) return false
            val enc = conn.getHeaderField("Content-Encoding")
            if (enc != null && !enc.equals("identity", true)) return false
            val mime = conn.contentType
                ?: if (isVideoUrl(url)) "video/mp4"
                   else "application/octet-stream"
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return false
            OfflineCache.put(url, mime, bytes)
            OfflineCache.has(url)
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    // ----------------------------------------------------------------- render

    /**
     * The offline screen, built from what we actually hold. Rendered by us,
     * because none of Facebook's own code can run without a connection.
     *
     * Reels are shown as a full-height vertical pager, the way the real app
     * shows them; feed posts as a simple card list.
     */
    /**
     * The saved stories, as the markup Facebook served.
     *
     * Nothing is laid out or reconstructed here. Every earlier version built a
     * card out of captured fields, and every one of them was missing something
     * - Like, Comment and Share most obviously - because a rebuilt card only
     * contains what was thought of in advance. This returns the original nodes,
     * so the offline card is the online card.
     */
    fun cardsHtml(section: String): String {
        val items = realPlayableItems(section)
        if (items.isEmpty()) return ""
        return items.joinToString("\n") { it.html }
    }

    fun sizeBytes(): Long {
        val dir = root ?: return 0
        return try { dir.listFiles()?.sumOf { it.length() } ?: 0 } catch (e: Exception) { 0 }
    }

    fun clear() {
        // The assembled page embeds these cards, so it must not survive them.
        OfflineDocs.invalidate()
        val dir = root ?: return
        try { dir.listFiles()?.forEach { it.delete() } } catch (e: Exception) {}
    }
}
