package com.dustbook.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * V4 Proactive Offline Preparation Engine.
 *
 * Core V4 change:
 * - When offline mode is enabled, the app **automatically** prepares
 *   fresh content in the background **without** any user action.
 * - Goal: 200+ fresh reels + 500+ fresh feed posts.
 * - Content should feel "alive" — new items, not just previously viewed ones.
 */
object OfflineManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isPreparing = false
    @Volatile private var lastAggressivePrep = 0L

    // V4 targets (significantly higher than v3)
    const val V4_REEL_TARGET = 200
    const val V4_FEED_TARGET = 500
    const val V4_STORIES_TARGET = 80

    fun isPreparingOffline(): Boolean = isPreparing

    /**
     * The main entry point for V4.
     * Called automatically on launch and on resume.
     */
    fun startProactivePreparation(
        context: Context,
        prefs: Prefs,
        force: Boolean = false
    ) {
        if (!prefs.offlineMode) return
        if (!UrlHelper.isLoggedIn()) return

        val now = System.currentTimeMillis()

        // Prevent too frequent aggressive runs, but allow force
        if (!force && (now - lastAggressivePrep < 2 * 60 * 1000)) {
            // Light top-up only
            scheduleLightTopUp(context, prefs)
            return
        }

        isPreparing = true
        lastAggressivePrep = now

        // Step 1: Refresh full page shells (so offline looks real)
        AppExecutors.background.execute {
            try {
                val screens = listOf("home", "reels", "stories")
                OfflineDocs.refresh(screens = screens, force = force)
            } catch (_: Exception) {}
        }

        // Step 2: Determine sections to prepare, in the owner's order:
        // feed first, then reels, stories last.
        val sections = buildList {
            if (prefs.offlineFeed) add(OfflineFeed.SECTION_FEED)
            if (prefs.offlineReels) add(OfflineFeed.SECTION_REELS)
            if (prefs.offlineStories) add(OfflineFeed.SECTION_STORIES)
        }

        if (sections.isEmpty()) {
            isPreparing = false
            return
        }

        // Exactly what the user asked for — never raised.
        val target = prefs.offlineReelTarget

        mainHandler.post {
            OfflineSync.runAll(
                context = context.applicationContext,
                sections = sections,
                target = target,
                includeVideo = prefs.offlineVideo,
                force = true,   // Always force on proactive path
                onDone = { captured ->
                    isPreparing = false

                    // Record the time only when the pass actually stored
                    // something. A run that reached nothing must not be
                    // reported as an update, or the offline library looks
                    // fresher than it is.
                    if (captured > 0) {
                        prefs.offlineLastSync = System.currentTimeMillis()
                    }

                    // Schedule a follow-up light refresh to get even fresher content
                    scheduleLightTopUp(context, prefs)
                }
            )
        }
    }

    private fun scheduleLightTopUp(context: Context, prefs: Prefs) {
        mainHandler.postDelayed({
            if (!prefs.offlineMode || !UrlHelper.isLoggedIn()) return@postDelayed

            val sections = buildList {
                if (prefs.offlineFeed) add(OfflineFeed.SECTION_FEED)
                if (prefs.offlineReels) add(OfflineFeed.SECTION_REELS)
            }

            if (sections.isNotEmpty()) {
                OfflineSync.runAll(
                    context = context.applicationContext,
                    sections = sections,
                    target = prefs.offlineReelTarget,
                    includeVideo = prefs.offlineVideo,
                    force = false
                )
            }
        }, 3 * 60 * 1000) // 3 minutes later — get newer content
    }

    /** Called from connectivity callback when network returns */
    fun onNetworkRestored(context: Context, prefs: Prefs) {
        if (!prefs.offlineMode) return

        mainHandler.postDelayed({
            startProactivePreparation(context, prefs, force = true)
        }, 2200)
    }

    fun stop() {
        isPreparing = false
    }
}