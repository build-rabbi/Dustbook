package com.dustbook.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Central orchestrator for the complete offline content lifecycle.
 *
 * Lifecycle:
 *   App opens online → start()
 *     Step 1: Save 10 random unwatched feed posts
 *     Step 2: Save user-configured reel count (only new, not watched)
 *     Step 3: Wait for reel videos to finish downloading
 *     Step 4: Save 300 more posts
 *     Step 5: Save ALL stories (watched + unwatched)
 *   User browses online → seen content tracked via bridge + store
 *   App goes offline → saved content displays
 *   App comes back online → clearAll() → start() fresh
 *
 * Everything runs silently on background threads. No user action needed.
 */
object BackgroundSyncManager {

    @Volatile var isRunning = false
        private set

    @Volatile var currentStep = ""
        private set

    private var ctx: Context? = null
    private var prefs: Prefs? = null

    fun init(context: Context, p: Prefs) {
        ctx = context.applicationContext
        prefs = p
    }

    /**
     * Start the full sync pipeline. Called once after login is confirmed
     * and the home page has loaded. Runs entirely on background threads.
     */
    fun start() {
        if (isRunning) return
        val c = ctx ?: return
        val p = prefs ?: return
        if (!UrlHelper.isLoggedIn()) return
        if (!p.offlineMode) return
        // Saving pulls feed pages, reels and their video. Not on a metered
        // connection unless the user has said that is fine.
        if (!NetworkPolicy.canDownload(c, p)) return

        isRunning = true
        step1NewPosts(c, p)
    }

    /** Called when connectivity returns after being offline. */
    fun onNetworkRestored() {
        if (!isRunning) {
            clearAllStored()
            start()
        }
    }

    /** Clear the entire offline library for a fresh sync cycle. */
    fun clearAllStored() {
        AppExecutors.diskIO.execute {
            OfflineCache.clear()
            OfflineFeed.clear()
            OfflineDocs.clear()
        }
    }

    // -------------------------------------------------------- steps

    /** How long to wait between rounds of the same step. */
    private const val ROUND_GAP_MS = 12_000L

    /** A step gives up after this many rounds rather than stalling forever. */
    private const val MAX_ROUNDS = 18

    private fun step1NewPosts(context: Context, p: Prefs) {
        currentStep = "posts-10"
        val target = 10
        runUntilTarget(context, p, OfflineFeed.SECTION_FEED, target, "posts-10") {
            step2Reels(context, p)
        }
    }

    private fun step2Reels(context: Context, p: Prefs) {
        // Exactly what the user asked for — never raised, never lowered.
        val target = p.offlineReelTarget
        runUntilTarget(context, p, OfflineFeed.SECTION_REELS, target, "reels") {
            step3WaitForVideo(context, p)
        }
    }

    private fun step3WaitForVideo(context: Context, p: Prefs) {
        currentStep = "wait-video"
        AppExecutors.background.execute {
            // Wait up to 5 minutes for downloads to finish.
            OfflineFeed.awaitPrefetch(300_000)
            // Only once the reels actually landed, move on to the 300 posts.
            android.os.Handler(android.os.Looper.getMainLooper()).post { step4MorePosts(context, p) }
        }
    }

    private fun step4MorePosts(context: Context, p: Prefs) {
        currentStep = "posts-300"
        val target = 300
        runUntilTarget(context, p, OfflineFeed.SECTION_FEED, target, "posts-300") {
            // Posts done; stories go last.
            step5Stories(context, p)
        }
    }

    private fun step5Stories(context: Context, p: Prefs) {
        currentStep = "stories"
        // Stories: save ALL, not just unwatched. Always the final step.
        OfflineSync.run(context, OfflineFeed.SECTION_STORIES, 200,
            includeVideo = true, force = true) { count ->
            currentStep = "done"
            isRunning = false
        }
    }

    /**
     * Run one step until its target is actually met, then hand over.
     *
     * A single capture pass stops when the page stops producing new cards,
     * which is usually short of the target — the first pass over reels might
     * store a dozen of the thirty asked for. The old code took whatever one
     * pass returned and moved on, silently lowering every amount. This keeps
     * re-running the same step until the fully-downloaded count reaches the
     * target (or the round budget runs out), and only then calls [onDone].
     *
     * Steps never overlap: the next step starts only when this one has
     * finished, so posts are never downloaded while reels are, and reels are
     * never downloaded while the 300-post pass is running.
     */
    private fun runUntilTarget(
        context: Context,
        p: Prefs,
        section: String,
        target: Int,
        stepName: String,
        onDone: () -> Unit
    ) {
        currentStep = stepName
        if (OfflineFeed.realPlayableCount(section) >= target) {
            onDone()
            return
        }
        var attempts = 0
        fun round() {
            attempts++
            OfflineSync.run(context, section, target,
                includeVideo = true, force = true) { _ ->
                if (OfflineFeed.realPlayableCount(section) >= target ||
                    attempts >= MAX_ROUNDS
                ) {
                    onDone()
                } else {
                    // A short pause lets the download pool finish and the
                    // page settle before the next round of the same step.
                    AppExecutors.background.execute {
                        try { Thread.sleep(ROUND_GAP_MS) } catch (_: InterruptedException) {}
                        android.os.Handler(android.os.Looper.getMainLooper()).post { round() }
                    }
                }
            }
        }
        round()
    }
}
