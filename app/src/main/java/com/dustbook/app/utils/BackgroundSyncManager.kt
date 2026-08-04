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
     * Run one step until its goal is actually met, then hand over.
     *
     * A single capture pass stops when the page stops producing new cards,
     * which is usually short of the goal — the first pass over reels might
     * store a dozen of the thirty asked for. The old code took whatever one
     * pass returned and moved on, silently lowering every amount. This keeps
     * re-running the same step until the fully-downloaded (playable) count
     * reaches the goal, and only then calls [onDone]. There is no round
     * budget: an amount that was asked for is an amount that gets saved, and
     * the next step never starts before this one is complete.
     *
     * Goals:
     *  - reels: the user's keep-count is the goal. The section store is
     *    capped at exactly that, so "keep 30" means 30 playable reels — no
     *    more, no less. If the goal is already met there is nothing to do.
     *  - posts (10 and 300): each step adds its own fresh batch on top of
     *    what is already held, so the goal is `held + amount`. The store
     *    limit is raised to the goal so the cap can never silently cut the
     *    batch short.
     *
     * Completion means playable, and playable means the media is on disk, so
     * after every round the download queue is allowed to drain before the
     * count is judged. Steps never overlap: the next step starts only when
     * this one has finished, so posts are never downloaded while reels are,
     * and reels are never downloaded while the 300-post pass is running.
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
        val before = OfflineFeed.realPlayableCount(section)
        val goal = if (section == OfflineFeed.SECTION_REELS) target else before + target
        if (section == OfflineFeed.SECTION_REELS && before >= target) {
            // Already exactly the user's count — nothing to do, move on.
            onDone()
            return
        }
        fun round() {
            OfflineSync.run(context, section, target,
                includeVideo = true, force = true, storeLimit = goal) { _ ->
                // Let the downloads finish before judging the count: only
                // fully-downloaded, playable items count toward the goal.
                AppExecutors.background.execute {
                    OfflineFeed.awaitPrefetch(300_000)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (OfflineFeed.realPlayableCount(section) >= goal) {
                            onDone()
                        } else {
                            round()
                        }
                    }
                }
            }
        }
        round()
    }
}
