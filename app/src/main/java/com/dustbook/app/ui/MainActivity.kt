package com.dustbook.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dustbook.app.R
import com.dustbook.app.databinding.ActivityMainBinding
import com.dustbook.app.utils.AdBlocker
import com.dustbook.app.utils.AdInspector
import com.dustbook.app.utils.BlockList
import com.dustbook.app.utils.CosmeticFilters
import com.dustbook.app.utils.MFacebookAds
import com.dustbook.app.utils.BackgroundSyncManager
import com.dustbook.app.utils.NetworkPolicy
import com.dustbook.app.utils.OfflineCache
import com.dustbook.app.utils.OfflineCapture
import com.dustbook.app.utils.OfflineDocs
import com.dustbook.app.utils.OfflineFeed
import com.dustbook.app.utils.AppExecutors
import com.dustbook.app.utils.OfflineSync
import com.dustbook.app.utils.Prefs
import com.dustbook.app.utils.VideoHelper
import com.dustbook.app.utils.SessionState
import com.dustbook.app.utils.SoftRefresh
import com.dustbook.app.utils.ThreeFingerDoubleTapDetector
import com.dustbook.app.utils.UpdateWatcher
import com.dustbook.app.utils.UrlHelper
import com.dustbook.app.viewmodel.MainViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: Prefs

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    // Fullscreen video state
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // Pending geolocation request, retried after the permission dialog.
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingWebRtcRequest: PermissionRequest? = null

    private var settingsPromptShown = false
    private var earlyScriptHandle: androidx.webkit.ScriptHandler? = null

    /** Live network state, updated by the ConnectivityManager callback. */
    @Volatile private var isOnline: Boolean = true

    /**
     * Consecutive main-frame failures for the current navigation.
     *
     * Reset the moment a page starts or finishes successfully, so a genuine
     * outage still reaches the error screen after [MAX_MAIN_FRAME_RETRIES]
     * attempts rather than retrying forever.
     */
    private var mainFrameRetries = 0
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    /** False when the page's own scroll container is scrolled away from top. */
    @Volatile private var pageAtTop: Boolean = true

    /**
     * True while the page has a video or audio element actually playing.
     *
     * Reported from the page, because the URL cannot answer it: the lite
     * renderer swaps the Reels screen in without navigating.
     */
    @Volatile private var mediaPlaying: Boolean = false

    /** The support prompt is considered at most once per session. */
    private var supportAsked = false

    /** True while a login / signup / checkpoint page is showing. */
    private var onAuthPage: Boolean = false

    /** Set by the page probe when a password field or login form is present. */
    @Volatile private var domSaysLoggedOut: Boolean = false

    /** Reels currently held offline, reported by the page. */
    @Volatile private var offlineReelCount: Int = 0

    /** True while a soft refresh is waiting for the page to answer. */
    @Volatile private var softRefreshPending: Boolean = false

    private val gestureDetector by lazy {
        ThreeFingerDoubleTapDetector { openHiddenSettings() }
    }

    // ---------------------------------------------------------------- launchers

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Retry any WebRTC request that was waiting on these permissions.
        pendingWebRtcRequest?.let { req ->
            val granted = result.values.all { it }
            runOnUiThread { if (granted) req.grant(req.resources) else req.deny() }
            pendingWebRtcRequest = null
        }
        // Retry a pending geolocation request.
        pendingGeoCallback?.let { cb ->
            val ok = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            cb.invoke(pendingGeoOrigin, ok, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }
        if (result.values.any { !it } && !settingsPromptShown) {
            settingsPromptShown = true
            showPermissionDialog()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return@registerForActivityResult

        if (result.resultCode != RESULT_OK) {
            cb.onReceiveValue(null)
            cameraPhotoUri = null
            return@registerForActivityResult
        }

        val data = result.data
        // Camera capture returns no data; fall back to the file we created.
        val uris: Array<Uri>? = when {
            data?.data != null || data?.clipData != null ->
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
            else -> null
        }
        cb.onReceiveValue(uris)
        cameraPhotoUri = null
    }

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode())
        if (prefs.amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Blocklist + offline store load off the main thread.
        OfflineCache.init(applicationContext)
        OfflineFeed.init(applicationContext)
        OfflineDocs.init(applicationContext)
        applyOfflineFlags()
        AppExecutors.diskIO.execute {
            BlockList.load(applicationContext)
            CosmeticFilters.load(applicationContext)
            OfflineCache.trimIfNeeded()
        }

        // Updates are watched for the whole process, not just this screen, so
        // a release published while the app is open is offered straight away
        // instead of waiting for the next cold start.
        UpdateWatcher.presenter = { activity, rel, local ->
            UpdatePrompt.show(activity, rel, local)
        }

        // Whether a session exists still decides what the app may do in the
        // background (offline sync must not run on a signed-out shell), but it
        // no longer selects between two different sign-in screens: there is
        // only Facebook's own form now.
        onAuthPage = !UrlHelper.isLoggedIn()

        BackgroundSyncManager.init(applicationContext, prefs)
        applyBlockerFlags()
        setupEdgeToEdge()
        setupWebView()
        setupSwipeRefresh()
        setupDownloadManager()
        setupBackHandling()
        setupErrorView()
        setupConnectivity()
        observeViewModel()

        updateChromeVisibility()

        // Restore in-memory state first, then the on-disk copy. The bundle
        // only exists when Android killed the activity; after a swipe-away or
        // a process reclaim it is gone, and without the disk copy the app
        // would rebuild Facebook's whole shell on every launch.
        val restored = savedInstanceState
            ?: if (prefs.saveSession) SessionState.restore(this) else null

        // Restoring paints from the WebView's own history and issues no
        // request, so whatever page was showing when the app closed is what
        // comes back — including Facebook's "Can't load the page" screen if
        // the connection happened to be down at the time. That then survived
        // every relaunch, with a perfectly good connection, because nothing
        // ever went back to the network. Only accept a state that actually
        // points at Facebook; anything else falls through to a normal load.
        val restoredUrl = restored
            ?.let { binding.webView.restoreState(it) }
            ?.let { binding.webView.url }

        if (restoredUrl != null && SessionState.isUsable(restoredUrl)) {
            // History came back. Nothing to load: the WebView repaints the
            // page it was on, header and tab bar included.
        } else {
            if (restoredUrl != null) {
                // The restore already put a dead page in the WebView. Drop it
                // and the file behind it before loading properly.
                binding.webView.clearHistory()
                SessionState.clear(this)
            }
            val target = if (onAuthPage) {
                // Signed out: go straight to Facebook's own sign-in form.
                // Nothing is stacked in front of it any more, so this is the
                // form the user actually types into -- one surface, one
                // submit. The "Get Facebook for Android" banner it carries is
                // removed by the cosmetic pass before first paint.
                "https://www.facebook.com/login/"
            } else {
                resolveStartUrl(intent)
            }
            binding.webView.loadUrl(target)
        }

        applyRuntimeOptions()

        // =====================================================
        // V4: PROACTIVE OFFLINE PREPARATION
        // Start preparing fresh offline content immediately
        // (no user scrolling required). This is the core of V4.
        // =====================================================
        // Nothing is started here. Preparation begins from onPageFinished,
        // once the Home page has genuinely finished loading - a fixed timer
        // fired while the page was still coming up and competed with it.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = urlFromIntent(intent)
        if (url != null) binding.webView.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    /**
     * Mirror the WebView's state to disk so it survives the process being
     * killed, not just the activity being recreated.
     */
    private fun persistSession() {
        if (!prefs.saveSession) {
            SessionState.clear(this)
            return
        }
        // Never write a state we would refuse to restore. Saving an error
        // page here is what created the loop: the bad page went to disk on
        // pause and came back on every launch afterwards.
        if (!SessionState.isUsable(binding.webView.url)) {
            SessionState.clear(this)
            return
        }
        val b = Bundle()
        if (binding.webView.saveState(b) != null) {
            SessionState.save(this, b)
        }
    }

    override fun onResume() {
        super.onResume()
        stopBgAudioService()
        // Back in front: let the framework manage visibility normally again,
        // so a WebView the user has finished with is still suspended.
        binding.webView.keepMediaAlive = false
        binding.webView.onResume()
        binding.webView.resumeTimers()
        // Leaving with the keyboard up and coming back is the other way to
        // find the window still shrunken, so check here too. Posted, because
        // a height cannot be read until this pass has laid out. Costs two
        // reads and does nothing when the size is already right.
        binding.root.post { if (!isFinishing && !isDestroyed) recoverWindowSizeIfStale() }
        applyBlockerFlags()
        applyRuntimeOptions()
        applyOfflineFlags()
        // Settings may have changed while we were away.
        // V4: Trigger proactive offline preparation on every resume
        // (lightweight if already running).
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) {
                maybeSyncOffline(force = false)
            }
        }, 4500)

        // Downloading also has to be able to begin here, not only from
        // onPageFinished. After a fresh install the first page load is the
        // login screen, and once the credentials are accepted Facebook swaps
        // its shell in client-side — often with no further onPageFinished for
        // the feed. The one call site therefore never fired, and nothing was
        // ever saved until some later navigation happened to trigger it.
        // start() is a no-op when it is already running, not logged in, or
        // offline saving is switched off, so calling it on every resume is
        // safe and costs a few field reads.
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed && isOnline) {
                BackgroundSyncManager.start()
                SyncService.startIfNeeded(applicationContext)
            }
        }, 6000)

        if (MainViewModel.pendingUpdateCheck) {
            MainViewModel.pendingUpdateCheck = false
            UpdateWatcher.checkNow(force = true)
        }

        // Ask about supporting the project, once the app has actually been
        // useful. Deliberately late and deliberately rare: it waits for the
        // feed to settle so it never lands on top of a page still loading,
        // and it stands aside entirely if an update prompt is due, since two
        // dialogs at once is nobody's idea of a native feel.
        if (!supportAsked) {
            supportAsked = true
            // Long enough for the feed to have painted, short enough that it
            // still reads as "on opening the app". Nine seconds was too late:
            // by then the user is already scrolling and a dialog is an
            // interruption rather than a greeting.
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed &&
                    UpdateWatcher.pending == null && customView == null
                ) {
                    SupportPrompt.maybeShow(this)
                }
            }, 3500)
        }

        if (viewModel.settingsDirty) {
            viewModel.settingsDirty = false
            applyWebSettings()
            registerEarlyScript()
            if (viewModel.needsReload) {
                viewModel.needsReload = false
                binding.webView.reload()
            } else {
                // Apply cosmetic changes live, without losing scroll position.
                injectAll(binding.webView)
            }
        }
    }

    override fun onPause() {
        super.onPause()

        saveOfflinePosition()

        // Whether audio should continue cannot be decided from the URL.
        // Facebook's lite renderer swaps the Reels screen in place without
        // navigating, so the address stays on the home feed the whole time a
        // reel is playing — the old "/reel" / "/watch" test simply never
        // matched, and background audio did nothing for the one case it
        // exists for. mediaPlaying is reported by the page itself, from the
        // video element's own play/pause events, so it is true whenever
        // something is actually making sound regardless of the URL.
        val keepAudioAlive = prefs.backgroundAudio && mediaPlaying

        // The service alone was never enough. Android suspends the media
        // pipeline itself when the window is hidden, one layer below anything
        // onPause can do — which is why a notification appeared and the audio
        // stopped anyway. MediaWebView swallows that notification, but only
        // while this flag is set.
        binding.webView.keepMediaAlive = keepAudioAlive

        if (keepAudioAlive) {
            // The service is still needed: it tells Android this is active
            // media playback, so the process is not frozen and Chromium does
            // not throttle its timers.
            startBgAudioService()
        } else {
            // onPause() is per-WebView and is what we want here.
            //
            // pauseTimers() is not: Android documents it as "a global request,
            // not restricted to just this WebView", so it also froze the
            // offscreen WebView that OfflineSync runs. Leaving the app
            // therefore stopped the download every time — the service stayed
            // up and the notification stayed on screen, because the service
            // was never the thing that had stalled.
            //
            // It is only called when nothing is downloading, so an idle app
            // still gives back the same CPU it did before.
            binding.webView.onPause()
            if (!BackgroundSyncManager.isRunning) {
                binding.webView.pauseTimers()
            }
        }

        CookieManager.getInstance().flush()
        persistSession()
        viewModel.blockedCount.value?.let { if (it > 0) prefs.blockCount = it }
    }

    /**
     * Facebook's shell is expensive to rebuild. Keeping the process warm makes
     * the next launch paint from memory instead of re-running the whole
     * bootstrap, which is the difference between an app and a browser tab.
     */
    override fun onDestroy() {
        connectivityCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (e: Exception) {}
        }
        connectivityCallback = null
        AppExecutors.diskIO.execute { OfflineCache.trimIfNeeded() }
        try { earlyScriptHandle?.remove() } catch (e: Exception) {}
        earlyScriptHandle = null
        CookieManager.getInstance().flush()
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            (parent as? android.view.ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- gestures

    /**
     * Three finger double tap anywhere -> hidden settings.
     * Purely observational, never consumes the event, so normal scrolling and
     * tapping are completely unaffected.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openHiddenSettings() {
        if (prefs.haptics) {
            binding.root.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS
            )
        }
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------------------------------------------------------------- setup

    /** True while system bar visibility is being changed, so insets updates
     *  during the transition are ignored — they would otherwise momentarily
     *  resize the WebView and visually bounce content under the player. */
    /** True while any layout-affecting transition is in progress:
     *  fullscreen enter/exit, page load, or navigation. */
    private var suppressInsets = false

    private var inFullscreenTransition = false

    /**
     * Page loads and fullscreen transitions both want to hold insets still,
     * and they used to share one boolean. Whichever finished first cleared it
     * for the other, which is how the layout ended up shifting at random:
     *
     *  - tap a reel while the feed is still loading. onShowCustomView sets the
     *    flag and hides the system bars; onPageFinished then clears it a
     *    moment later, still fullscreen, and the next inset pass writes the
     *    immersive insets (top = 0) into the root. On leaving fullscreen the
     *    bars come back but the padding stays at zero, so everything sits up
     *    under the status bar.
     *
     * Counting instead of flagging: the suppression only lifts when every
     * holder has released it.
     */
    private var insetHolds = 0

    private fun holdInsets() {
        insetHolds++
        suppressInsets = true
    }

    private fun releaseInsets() {
        if (insetHolds > 0) insetHolds--
        if (insetHolds == 0) suppressInsets = false
    }

    /**
     * Clears the fullscreen transition after the animation settles.
     *
     * Kept as a field so it can be cancelled. There was no removeCallbacks
     * anywhere, so a quick exit-then-enter left the exit's callback pending;
     * it fired half a second into the new fullscreen, cleared the flags and
     * pushed an inset pass under the fullscreen container - the very re-layout
     * that makes the player re-attach and the clip jump.
     */
    private var fullscreenSettle: Runnable? = null

    /** True while the current page load owns one inset hold. */
    private var pageLoadHoldsInsets = false

    private fun setupEdgeToEdge() {
        // Pad contentRoot - the feed - and not the root FrameLayout.
        //
        // This is the whole bug, and every earlier attempt was working around
        // it rather than removing it. root holds two children: contentRoot
        // (the feed) and customViewContainer (the fullscreen video). Padding
        // on root therefore applies to the video as well, so it *had* to drop
        // to zero for fullscreen and be restored on the way out. That restore
        // is a moving part, and any missed pass leaves the feed padded with
        // zero, sitting up under the status bar with an unpainted strip at the
        // bottom. Scrolling appeared to fix it only because a scroll forces a
        // fresh layout.
        //
        // customViewContainer is a sibling, so padding here never touches the
        // video: fullscreen is genuinely fullscreen and the feed's padding
        // never has to change. Nothing to restore means nothing to get wrong.
        //
        // The earlier comment here warned that contentRoot's visibility
        // toggles during fullscreen and that each toggle triggered a pass.
        // That is true, and it is why the container is now hidden with
        // INVISIBLE rather than GONE - see onShowCustomView. INVISIBLE keeps
        // the feed measured, so it comes back at the size it left at.
        var imeWasVisible = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { view, windowInsets ->
            // getInsetsIgnoringVisibility, not getInsets.
            //
            // getInsets reports zero the moment the bars are hidden, so a pass
            // taken during immersive playback - or during either animation -
            // says "no status bar". Asking for the space the bars occupy when
            // shown gives the same answer throughout, so a pass arriving at an
            // awkward moment cannot record the wrong thing.
            //
            // The keyboard genuinely does come and go, so the IME is still
            // read normally.
            val bars = windowInsets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars()
            )
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                top = bars.top,
                bottom = maxOf(bars.bottom, ime.bottom)
            )

            // The keyboard closing on its own way into Reels or Stories is
            // another moment the window is left stale with nothing to say
            // so - the lite renderer swaps the comment box for those screens
            // in place, no navigation and no fullscreen transition, so
            // neither of recoverWindowSizeIfStale()'s two existing call
            // sites ever runs. This listener already fires on every real IME
            // transition, which is the one signal that reliably does.
            if (imeWasVisible && !imeVisible) recoverWindowSizeIfStale()
            imeWasVisible = imeVisible

            windowInsets
        }

        // The offline / error screen is a sibling of contentRoot, not a child,
        // so it needs the same treatment or its message and buttons would sit
        // under the status bar.
        //
        // It carries 32dp of its own padding from the layout, which is what
        // keeps the text off the screen edges. Add the bars to that rather
        // than replacing it, and read the base once so repeated passes cannot
        // accumulate.
        val errorBasePadding = binding.errorView.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.errorView) { view, windowInsets ->
            val bars = windowInsets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars()
            )
            view.updatePadding(
                top = errorBasePadding + bars.top,
                bottom = errorBasePadding + bars.bottom
            )
            windowInsets
        }
        updateSystemBarIcons()
    }

    private fun updateSystemBarIcons() {
        val isNight = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addJavascriptInterface(JsBridge(), "FBPro")
            webViewClient = createWebViewClient()
            webChromeClient = createWebChromeClient()
        }
        applyWebSettings()
        registerEarlyScript()

        // V4 Step 3: Apply video playback optimizations
        VideoHelper.applyVideoOptimizations(binding.webView)

        // Facebook picks a different renderer per user agent, so a stored page
        // must be fetched with the same one the WebView uses. Read it after
        // applyWebSettings, not before, or it is the stock UA and the pages we
        // store are ones the WebView would never have been served.
        OfflineDocs.userAgent = binding.webView.settings.userAgentString
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }
    }

    /**
     * Register the GraphQL ad-stripper to run at document start, before any of
     * Facebook's own scripts. This is what lets us delete sponsored posts from
     * the API response instead of hiding them after they render.
     */
    private fun registerEarlyScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            earlyScriptHandle?.remove()
            earlyScriptHandle = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                binding.webView,
                AdBlocker.getEarlyScript(prefs.adBlock, prefs.blockAppPromo) + "\n" +
                    (if (prefs.adBlock && prefs.cosmeticFilter) MFacebookAds.script() else ""),
                setOf("https://*.facebook.com", "https://*.messenger.com")
            )
        } catch (e: Exception) {
            earlyScriptHandle = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyWebSettings() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true

            // Zoom is user configurable now (accessibility).
            val zoom = prefs.allowZoom
            setSupportZoom(zoom)
            builtInZoomControls = zoom
            displayZoomControls = false

            // Popups are handled by onCreateWindow, never silently dropped.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = !prefs.blockPopups

            // Prefer the HTTP cache so a relaunch paints from disk instead of
            // refetching everything, which is what made the app feel like a
            // browser rather than an installed app.
            cacheMode = if (isOnline) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            // Keep the rendering pipeline warm.
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            // Do not wait for the full layout before painting text.
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            allowFileAccess = false          // security: was true
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = !prefs.autoplayVideo
            safeBrowsingEnabled = true

            userAgentString = buildUserAgent(userAgentString)

            // Native-feel tuning
            setGeolocationEnabled(true)
            defaultTextEncodingName = "utf-8"
        }

        // Follow the app theme inside the page so Facebook renders dark mode
        // natively instead of flashing a white background.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                    binding.webView.settings, true
                )
            } catch (e: Exception) { /* not fatal */ }
        }
    }

    /**
     * Present as an ordinary Android Chrome browser.
     *
     * We strip only the "wv" token, which marks the client as an embedded
     * WebView. Nothing is invented: the Chrome version, Android version and
     * device string all stay as they really are. Spoofing the official
     * Facebook app's user agent was considered and rejected - a WebView
     * cannot back up that claim, and the mismatch is exactly what triggers
     * checkpoints. A normal mobile browser is a genuine, supported way to
     * use Facebook.
     */
    private fun buildUserAgent(current: String): String {
        val clean = current
            .replace("; wv", "")
            .replace(" wv", "")
            .replace(Regex("\\bwv\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (prefs.desktopMode) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36"
        } else {
            clean
        }
    }

    /**
     * Reading is always on; only saving follows the user's switches.
     *
     * These used to be one flag, so turning saving off also hid content that
     * was already downloaded. It was never deleted -- turning the switches
     * back on made it reappear -- so the app was simply refusing to show
     * something it still had.
     */
    /**
     * Re-apply window insets once a page has finished loading.
     *
     * Insets are suppressed from onPageStarted, so any change that arrives
     * during the load is dropped and root padding is left stale — content
     * then sits high, with the header and action row clipped. The fullscreen
     * handlers already ask for a fresh dispatch when they stop suppressing;
     * this is the same thing for an ordinary navigation.
     */
    /**
     * Start a fullscreen enter or exit.
     *
     * Cancels any settle still pending from the previous transition. Tapping
     * through reels produces exit/enter pairs far closer together than the
     * settle delay, and the old callback used to fire in the middle of the
     * next fullscreen and re-lay out the root underneath the player.
     */
    private fun beginFullscreenTransition() {
        fullscreenSettle?.let { binding.root.removeCallbacks(it) }
        fullscreenSettle = null
        if (!inFullscreenTransition) {
            inFullscreenTransition = true
            holdInsets()
        }
    }

    /** Release the transition once the animation and bar change have settled. */
    private fun endFullscreenTransition() {
        fullscreenSettle?.let { binding.root.removeCallbacks(it) }
        val r = Runnable {
            fullscreenSettle = null
            if (inFullscreenTransition) {
                inFullscreenTransition = false
                releaseInsets()
            }
            // Requested on root, which dispatches down to the listeners on
            // contentRoot and errorView.
            //
            // With the padding moved off root and measured ignoring bar
            // visibility, a badly-timed pass can no longer record the wrong
            // thing - this guard and the suppression counter are now belt and
            // braces rather than the thing holding the layout together.
            if (!suppressInsets && customView == null) {
                ViewCompat.requestApplyInsets(binding.root)
            }
        }
        fullscreenSettle = r
        binding.root.postDelayed(r, 500)
    }

    private fun refreshInsetsAfterLoad() {
        // Not while a video is fullscreen. An inset pass re-lays out the root,
        // and doing that under the fullscreen container made the player
        // re-attach — the clip jumped back to the start. The fullscreen
        // handlers already request their own pass when they finish, which is
        // the right moment for it.
        if (customView != null || inFullscreenTransition) return
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applyOfflineFlags() {
        val read = prefs.offlineRead
        val write = prefs.offlineMode
        OfflineCache.enabled = read
        OfflineFeed.enabled = read
        OfflineDocs.enabled = read
        OfflineCache.writeEnabled = write
        OfflineFeed.writeEnabled = write
        OfflineDocs.writeEnabled = write
    }

    private fun applyBlockerFlags() {
        AdBlocker.enabled = prefs.adBlock
        AdBlocker.cosmeticEnabled = prefs.cosmeticFilter
    }

    /**
     * The real Facebook app shows no navigation chrome until you are signed
     * in, so the login screen must be the bare page. Chrome is therefore
     * hidden whenever an auth page is showing, regardless of the setting.
     */
    /**
     * Decide whether we are on a logged-out screen.
     *
     * Three signals, because no single one is enough:
     *  - the URL (explicit /login, /reg, /checkpoint pages)
     *  - the session cookie (c_user only exists when signed in)
     *  - the DOM (Facebook serves the login form at / itself, so a password
     *    field on screen is the decisive marker)
     */
    private fun evaluateAuthState(url: String?) {
        val byUrl = UrlHelper.isAuthPage(url)
        val signedIn = UrlHelper.isLoggedIn()

        // The session cookie is authoritative, and that has to include the
        // URL test. Facebook posts the sign-in form to
        // /login/device-based/regular/login/ and redirects through
        // /login/?next=..., whose first path segment is "login", so
        // isAuthPage() returns true for the very page that is handing us the
        // session. Checking byUrl first therefore re-latched onAuthPage to
        // true immediately after a successful login: the native screen came
        // back with the fields already cleared and the user had to type the
        // credentials and press Log in a second time.
        //
        // c_user only exists once Facebook has accepted the credentials, so
        // when it is present the URL cannot mean "not signed in" - it only
        // means the redirect is still in flight.
        val auth = when {
            signedIn -> false
            byUrl -> true
            else -> domSaysLoggedOut || !signedIn
        }

        if (auth != onAuthPage) {
            val wasAuth = onAuthPage
            onAuthPage = auth
            updateChromeVisibility()

            // Just signed in: leave the login screen behind for good and make
            // sure we land on the feed rather than a leftover login URL.
            if (wasAuth && !auth) {
                // Always load homepage on auth→non-auth transition.
                // If Facebook's redirect lands the WebView on an
                // intermediate page (e.g. /login/?next=), that page
                // can still have "Get the app" banners. Loading the
                // homepage ensures the first visible frame is clean.
                binding.webView.loadUrl(prefs.homepage)

                // Just signed in, so there is a session for the first time.
                // This is the earliest honest moment to start filling the
                // offline store, and on a fresh install it is the only one
                // that reliably arrives.
                binding.root.postDelayed({
                    if (!isFinishing && !isDestroyed && isOnline) {
                        BackgroundSyncManager.start()
                        SyncService.startIfNeeded(applicationContext)
                    }
                }, 8000)
            }
        }
    }

    /** Re-run the auth probe a few times after a load or a navigation. */
    private fun scheduleAuthProbes(view: WebView?) {
        val target = view ?: binding.webView
        target.evaluateJavascript(AdBlocker.getAuthProbeScript(), null)
        for (delay in longArrayOf(400, 1200, 2500, 4000)) {
            target.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    target.evaluateJavascript(AdBlocker.getAuthProbeScript(), null)
                }
            }, delay)
        }
    }

    /**
     * The WebView is the only surface. Facebook renders its own login form,
     * its own header and its own tabs, and the app draws none of them.
     *
     * There used to be a native login screen stacked on top of this, mirroring
     * Facebook's layout, with the real page loading hidden behind it. Keeping
     * two sign-in surfaces in agreement needed a timer, and when the timer was
     * wrong the native screen was pulled away to reveal Facebook's own form
     * underneath -- which is what made people log in twice. One surface cannot
     * disagree with itself.
     */
    private fun updateChromeVisibility() {
        binding.swipeRefresh.visibility = View.VISIBLE
    }

    private fun applyRuntimeOptions() {
        updateChromeVisibility()
        binding.swipeRefresh.isEnabled = prefs.pullToRefresh
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (!prefs.showProgress) binding.progressBar.visibility = View.GONE
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.apply {
            setColorSchemeResources(R.color.primary, R.color.secondary)
            setOnRefreshListener { softRefresh() }

            // V4 Step 4: More robust scroll detection
            setOnChildScrollUpCallback { _, _ ->
                binding.webView.canScrollVertically(-1) ||
                    binding.webView.scrollY > 0 ||
                    !pageAtTop
            }
        }
    }

    /**
     * Refresh the content without reloading the document.
     *
     * A real reload makes Facebook re-run its bootstrap, which paints its own
     * blue splash screen - the app looks like a browser tab every time the
     * user pulls down. Asking the page to refresh itself keeps the shell on
     * screen, which is what the native app does. If the page cannot do it we
     * fall back to a reload, so pull-to-refresh always works.
     */
    private fun softRefresh() {
        // Offline: a live refresh is impossible. Rebuild the stored page so
        // the latest saved cards appear, then dismiss the spinner instantly.
        if (!isOnline) {
            OfflineDocs.invalidate()
            binding.swipeRefresh.isRefreshing = false
            if (!hasAnythingOffline()) return
            // navigableScreens, not savedScreens: a screen held only as cards
            // has no stored document, so it fell through to reload() — which
            // re-served the very page we had just invalidated, and the screen
            // never changed.
            val saved = OfflineDocs.navigableScreens()
            val cur = OfflineDocs.screenFor(binding.webView.url ?: "")
            if (cur != null && saved.contains(cur)) {
                binding.webView.loadUrl(OfflineDocs.urlFor(cur)
                    ?: binding.webView.url ?: "https://www.facebook.com")
            } else {
                binding.webView.reload()
            }
            return
        }

        softRefreshPending = true
        binding.webView.evaluateJavascript(SoftRefresh.script(), null)
        binding.webView.postDelayed({
            if (softRefreshPending) {
                softRefreshPending = false
                binding.swipeRefresh.isRefreshing = false
                binding.webView.reload()
            }
        }, 2500)
    }

    private fun setupErrorView() {
        binding.errorRetry.setOnClickListener {
            // A deliberate retry earns a fresh set of automatic attempts.
            mainFrameRetries = 0
            binding.errorView.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            binding.webView.reload()
        }

        // Browse the cached copy of the last page without a connection.
        // Show what we actually saved.
        //
        // This used to reload the last Facebook URL from the HTTP cache and
        // hope for the best, which is why the button appeared to do nothing:
        // with no connection the document request simply failed again. Now we
        // render our own page from the stored items.
        binding.errorOffline.setOnClickListener { showSavedContent() }
    }

    /**
     * Open the offline screen built from the items we hold. Prefers whichever
     * section the user was on; falls back to whichever has content.
     */
    /** True when there is any offline content at all - a page, or cards. */
    /**
     * True when there is a stored Facebook page to show.
     *
     * Cards on their own no longer count: without a stored document there is
     * no Facebook chrome to put them in, and the alternative - drawing a page
     * of our own around them - is what made offline look different from
     * online. Better to show nothing than something we invented.
     */
    private fun hasAnythingOffline(): Boolean =
        OfflineDocs.savedScreens().isNotEmpty() ||
        OfflineFeed.hasAnything()

    private fun showSavedContent() {
        // navigableScreens, not savedScreens: a screen held only as cards is
        // still reachable, because shellFor() builds a page from them.
        val saved = OfflineDocs.navigableScreens()
        val last = OfflineDocs.screenFor(prefs.lastUrl)

        val target = when {
            last != null && saved.contains(last) -> prefs.lastUrl
            saved.contains("home") -> "https://m.facebook.com/"
            saved.contains("reels") -> "https://m.facebook.com/reel/"
            saved.contains("stories") -> "https://m.facebook.com/stories/"
            saved.isNotEmpty() -> OfflineDocs.urlFor(saved.first())
            // No stored Facebook document, but we have cards. Load the
            // home URL anyway — shellFor() will serve them.
            OfflineFeed.hasAnything() -> prefs.homepage
            else -> null
        }

        if (target == null) {
            toast(getString(R.string.offline_nothing_saved))
            return
        }

        binding.errorView.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        // A toast, not a permanent bar. The bar sat across the bottom of
        // every screen and covered the video while it played.
        toast(getString(R.string.offline_banner))
        binding.webView.loadUrl(target)
    }

    /**
     * Fill the offline store by itself.
     *
     * The user should not have to scroll through content, or press a button,
     * for "keep this offline" to mean anything - so this runs on its own once
     * the visible page has settled, for whichever sections are enabled.
     */
    /** 
     * Collecting is owned by BackgroundSyncManager.
     * Legacy path kept for compatibility (e.g. network restored callbacks).
     * The main automatic fresh content preparation now happens on launch.
     */
    private fun maybeSyncOffline(force: Boolean = false) {
        if (!prefs.offlineMode || !isOnline) return
        if (!NetworkPolicy.canDownload(applicationContext, prefs)) return

        // BackgroundSyncManager owns collecting; OfflineManager is not
        // started here any more.
        //
        // Both build their own offscreen WebView, and a WebView must live on
        // the main thread — the same thread that draws the feed. Running two
        // of them alongside the visible page meant three WebViews competing
        // for one thread: the feed scrolled in steps, and right after signing
        // in the screen sat dimmed and ignored taps while they all loaded.
        //
        // start() is idempotent and already triggered from onResume, from
        // onPageFinished and from the sign-in transition, so nothing is lost
        // by not starting a second engine here.
        if (!BackgroundSyncManager.isRunning) {
            BackgroundSyncManager.start()
            SyncService.startIfNeeded(applicationContext)
        }

        // Still refresh the current visible page's documents lightly
        AppExecutors.background.execute {
            try {
                OfflineDocs.refresh(force = force)

                val screens = OfflineDocs.savedScreens()
                val media = screens.flatMap { OfflineDocs.mediaUrls(it) }.distinct()
                OfflineFeed.prefetchUrls(media, includeVideo = prefs.offlineVideo)

                // A second pass, once the stylesheets are on disk.
                //
                // The icon font's URL lives inside the CSS, so it can only be
                // discovered by reading a stylesheet we have already stored.
                // On the first pass those files were still downloading, which
                // is why the font was never found and every icon rendered as
                // a tofu box. Sweeping again afterwards picks it up.
                OfflineFeed.awaitPrefetch(45_000)
                val second = screens.flatMap { OfflineDocs.mediaUrls(it) }
                    .distinct()
                    .filterNot { OfflineCache.has(it) }
                if (second.isNotEmpty()) {
                    OfflineFeed.prefetchUrls(second, includeVideo = prefs.offlineVideo)
                }
            } catch (_: Exception) {}
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null ->
                        (binding.webView.webChromeClient)?.onHideCustomView()

                    binding.webView.canGoBack() -> {
                        binding.webView.goBack()
                        if (prefs.haptics) {
                            binding.root.performHapticFeedback(
                                android.view.HapticFeedbackConstants.VIRTUAL_KEY
                            )
                        }
                    }

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun observeViewModel() {
        viewModel.progress.observe(this) { p ->
            if (!prefs.showProgress) {
                binding.progressBar.visibility = View.GONE
                return@observe
            }
            binding.progressBar.progress = p
            binding.progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------- start url

    private fun resolveStartUrl(intent: Intent?): String {
        urlFromIntent(intent)?.let { return it }
        if (prefs.saveSession) {
            val last = prefs.lastUrl
            if (!last.isNullOrBlank() && UrlHelper.isInternal(last)) return last
        }
        return prefs.homepage
    }

    private fun urlFromIntent(intent: Intent?): String? {
        intent ?: return null
        // Deep link: fixes the old bug where intent.data was never read.
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.dataString
            if (!data.isNullOrBlank() && UrlHelper.isInternal(data)) return data
        }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                return "https://www.facebook.com/sharer/sharer.php?u=${Uri.encode(text)}"
            }
        }
        return null
    }

    // ---------------------------------------------------------------- clients

    private fun createWebViewClient(): android.webkit.WebViewClient {
        return object : android.webkit.WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Counted, so finishing this load cannot lift a hold that a
                // fullscreen transition is still relying on.
                if (!pageLoadHoldsInsets) { pageLoadHoldsInsets = true; holdInsets() }
                binding.errorView.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE

                // Decide chrome visibility before the page paints, so the
                // login screen never flashes a top bar.
                evaluateAuthState(url)

                // Inject the CSS blocks as early as possible.
                view?.evaluateJavascript(
                    AdBlocker.getStyleScript(
                        prefs.blockAppPromo,
                        prefs.adBlock,
                        hideSiteLoadingBar = !prefs.showProgress
                    ),
                    null
                )
                // Fallback for devices without DOCUMENT_START_SCRIPT support.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(
                        AdBlocker.getEarlyScript(prefs.adBlock, prefs.blockAppPromo), null
                    )
                }
                // Start the m.facebook ad remover as early as possible so a
                // sponsored video never gets to autoplay. Never on an offline
                // page: the saved content is not advertising, and the ad
                // remover has hidden it before (black offline feed).
                if (isOnline && prefs.adBlock && prefs.cosmeticFilter) {
                    view?.evaluateJavascript(CosmeticFilters.styleScript(), null)
                    view?.evaluateJavascript(MFacebookAds.script(), null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (pageLoadHoldsInsets) { pageLoadHoldsInsets = false; releaseInsets() }
                binding.swipeRefresh.isRefreshing = false
                mainFrameRetries = 0
                refreshInsetsAfterLoad()
                if (prefs.saveSession && UrlHelper.isInternal(url)) prefs.lastUrl = url
                injectAll(view)
                view?.postDelayed({ warmOfflineCache(url) }, 2500)

                // Offline preparation starts here, and only here: the Home
                // page has finished loading, so the user is looking at real
                // content and nothing is competing for the first paint.
                // Silent, background, never blocks scrolling.
                if (!onAuthPage && UrlHelper.isInternal(url)) {
                    view?.postDelayed({ maybeSyncOffline() }, 8_000)
                    BackgroundSyncManager.start()
                    SyncService.startIfNeeded(applicationContext)
                }
                // Re-probe several times: after a login submit Facebook swaps
                // the shell client-side, so the password field disappears
                // without another page load. A single probe would leave the
                // logged-out state latched.
                scheduleAuthProbes(view)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // SPA navigation - re-run the blocker.
                injectAll(view)
                scheduleAuthProbes(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                binding.swipeRefresh.isRefreshing = false
                if (request?.isForMainFrame != true) return

                // Offline: go straight to whatever is stored. Showing an
                // error and asking the user to press "View saved content" is
                // browser behaviour; the app just shows what it has.
                //
                // The home screen counts as available if there is either a
                // stored document or saved cards to put in one - requiring a
                // stored document alone meant a store full of reels still
                // showed "Can't load the page".
                // offlineRead, not offlineMode: the save switches decide what
                // is collected from now on, not whether what is already on
                // disk may be read.
                if (prefs.offlineRead && !isOnline && hasAnythingOffline()) {
                    showSavedContent()
                    return
                }

                // A single failed request is not a dead connection. On mobile
                // data the first load routinely fails while the radio is still
                // coming up or DNS has not settled — ERROR_HOST_LOOKUP,
                // ERROR_CONNECT, ERROR_TIMEOUT — and giving up immediately is
                // why the app worked on Wi-Fi and showed "Can't load the page"
                // on cellular. Wi-Fi is usually already associated and
                // resolving by the time the activity starts, so the same code
                // path never failed there.
                //
                // Retry the main frame a few times with a widening gap before
                // admitting defeat. The user sees a blank frame for a moment
                // instead of a dead end.
                val code = error?.errorCode
                    ?: android.webkit.WebViewClient.ERROR_UNKNOWN
                if (isOnline && isTransientNetworkError(code) &&
                    mainFrameRetries < MAX_MAIN_FRAME_RETRIES
                ) {
                    val attempt = ++mainFrameRetries
                    val failed = request.url?.toString()
                    binding.root.postDelayed({
                        if (isFinishing || isDestroyed) return@postDelayed
                        if (failed != null) binding.webView.loadUrl(failed)
                        else binding.webView.reload()
                    }, attempt * 1200L)
                    return
                }

                showErrorPage()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrl(url)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request ?: return null

                if (isOnline && AdBlocker.shouldBlockRequest(request)) {
                    viewModel.incrementBlocked()
                    return AdBlocker.createEmptyResponse()
                }

                // Offline main frame. Serve the real Facebook document we
                // stored while online, so the header, tab bar, counts and
                // post controls are all exactly where they are online -
                // because it is the same page. Without this the WebView's own
                // error page wins before a single cached asset is requested.
                if (prefs.offlineRead && !isOnline && request.isForMainFrame) {
                    OfflineDocs.serve(request)?.let { return it }
                }

                // Online, hand every subresource straight back to the WebView.
                //
                // This test has to come before isInterceptable(), not after.
                // isInterceptable() calls has(), which hashes the URL and stats
                // a file — disk work on the WebView's resource thread, for every
                // image, script and stylesheet on the page. It used to be
                // unreachable online because the store was disabled whenever
                // saving was off; now that reading is always enabled, leaving it
                // in front of this line made the whole feed crawl.
                if (isOnline) return null

                if (!prefs.offlineRead) return null
                if (!OfflineCache.isInterceptable(request)) return null

                // Offline only: serve whatever we already stored.
                // V4 Step 3: Try range first for video, fallback to normal get
                if (request.requestHeaders.keys.any { it.equals("Range", true) }) {
                    OfflineCache.range(request)?.let { return it }
                }
                return OfflineCache.get(request, offlineOnly = true)
            }
        }
    }

    /** @return true if the app consumed the navigation. */
    private fun handleUrl(url: String): Boolean {
        // 1. Play Store / app install links are killed outright.
        if (prefs.blockAppPromo && UrlHelper.isAppStoreLink(url)) {
            return true // swallow silently, no store, no chooser
        }
        // 2. tel:/mailto:/sms:
        if (UrlHelper.isSpecialScheme(url)) {
            return openExternally(url)
        }
        // 3. Messaging is deliberately left alone. The desktop-UA switch
        //    leaked into the rest of the session and broke the home feed
        //    ("Something went wrong"), so it was removed in 3.6.2.

        // 4. Facebook + auth providers stay inside.
        if (UrlHelper.isInternal(url)) return false
        // 5. Everything else, per user setting.
        return if (prefs.openLinksExternal) openExternally(url) else false
    }


    private fun openExternally(url: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app_to_open))
            true
        } catch (e: Exception) {
            true
        }
    }

    private fun injectAll(view: WebView?) {
        view ?: return

        // Re-apply the static sheet as well. It used to run only from
        // onPageStarted, so switching the loading bar off did nothing until
        // the next navigation — and switching it back on left Facebook's own
        // bar hidden for the rest of the session.
        view.evaluateJavascript(
            AdBlocker.getStyleScript(
                prefs.blockAppPromo,
                prefs.adBlock,
                hideSiteLoadingBar = !prefs.showProgress
            ),
            null
        )
        if (prefs.inspectAds) {
            view.evaluateJavascript(AdInspector.script(), null)
        }
        // Offline pages are saved content, never ads: the cosmetic ad
        // remover must not run on them (it has hidden the offline feed
        // before).
        if (isOnline && prefs.adBlock && prefs.cosmeticFilter) {
            view.evaluateJavascript(CosmeticFilters.styleScript(), null)
            view.evaluateJavascript(CosmeticFilters.proceduralScript(), null)
            view.evaluateJavascript(MFacebookAds.script(), null)
        }
        if (prefs.offlineRead) {
            // Capture only runs via OfflineSync background WebView.
            // The visible page never populates the offline store —
            // this ensures online-viewed content is never saved.
            if (!isOnline) {
                view.evaluateJavascript(VideoHelper.getOfflineVideoAssistScript(), null)
            }
        }
        view.evaluateJavascript(
            AdBlocker.getNativeFeelScript(), null
        )
        view.evaluateJavascript(
            AdBlocker.getCosmeticScript(
                blockAds = prefs.adBlock && prefs.cosmeticFilter,
                blockAppPromos = prefs.blockAppPromo,
                hideFlags = prefs.sectionFlags()
            ),
            null
        )
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                viewModel.setProgress(newProgress)
            }

            /**
             * Popups. The old build set window.open = null in JS and never
             * implemented this, so every target=_blank link was a dead tap.
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (prefs.blockPopups && !isUserGesture) return false

                // Load the popup target in the main WebView instead of a new one.
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?, request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        if (!handleUrl(url)) binding.webView.loadUrl(url)
                        // Destroy off the callback stack, never from inside it.
                        v?.post { temp.destroy() }
                        return true
                    }
                }
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // Always release a previous callback, never leave the input locked.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                cameraPhotoUri = null

                val contentIntent = params?.createIntent() ?: run {
                    filePathCallback = null
                    return false
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, getString(R.string.choose_file))
                    val extras = buildCaptureIntents(params)
                    if (extras.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
                    }
                }

                return try {
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (e: Exception) {
                    // Critical: unlock the <input type=file> before bailing out.
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val resources = request.resources
                val needed = mutableListOf<String>()

                if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                    !hasPermission(Manifest.permission.CAMERA)
                ) needed += Manifest.permission.CAMERA

                if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                    !hasPermission(Manifest.permission.RECORD_AUDIO)
                ) needed += Manifest.permission.RECORD_AUDIO

                if (needed.isEmpty()) {
                    // Grant capture permissions plus protected-media playback.
                    // RESOURCE_PROTECTED_MEDIA_ID is required for EME/DRM video
                    // decoding — Facebook's video and Reels content needs this to
                    // actually start playing. Without it the WebView can request
                    // the resource but never receives a grant, so the video stays
                    // stuck buffering and only the loading placeholder shows.
                    val safe = resources.filter {
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    }.toTypedArray()
                    request.grant(if (safe.isEmpty()) resources else safe)
                } else {
                    // Retry after the dialog instead of denying forever.
                    pendingWebRtcRequest = request
                    permissionLauncher.launch(needed.toTypedArray())
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                super.onPermissionRequestCanceled(request)
                pendingWebRtcRequest = null
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                // Guard against a second call
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                originalOrientation = requestedOrientation

                // The system's default video poster (the large play-triangle-in-a-ring
                // icon) is drawn by the platform's VideoView/MediaPlayer before the
                // first real frame decodes. Giving the view and its container an
                // opaque black background papers over that placeholder without
                // touching Facebook's own player UI or using any CSS/overlay hack —
                // it's just how the native container is normally painted.
                view?.setBackgroundColor(android.graphics.Color.BLACK)

                binding.customViewContainer.apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    addView(
                        view,
                        android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    visibility = View.VISIBLE
                }
                beginFullscreenTransition()
                // INVISIBLE, not GONE.
                //
                // GONE removes the feed from layout, so it is re-measured from
                // scratch on the way back - and that measurement happens while
                // the system bars are still animating in, giving it the full
                // screen height. The page then stays laid out for a taller
                // viewport than it has: content sits shifted up with a dead
                // strip at the bottom until a scroll forces a relayout.
                //
                // INVISIBLE keeps it measured at the size it had, so it comes
                // back exactly as it left. It costs nothing: the WebView is
                // covered by the fullscreen container and is not drawn.
                binding.contentRoot.visibility = View.INVISIBLE
                enterImmersive(true)
                // Reels/Stories are vertical (9:16) video. Forcing landscape here
                // shrinks/letterboxes that content instead of filling the screen.
                // Let the system rotate freely based on the device sensor instead
                // of locking to landscape, so vertical video stays fullscreen and
                // horizontal video (e.g. shared long-form clips) can still rotate.
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                endFullscreenTransition()
            }

            override fun onHideCustomView() {
                if (customView == null) return
                beginFullscreenTransition()
                binding.customViewContainer.apply {
                    removeAllViews()
                    visibility = View.GONE
                }
                binding.contentRoot.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                enterImmersive(false)
                requestedOrientation = originalOrientation
                endFullscreenTransition()

                // Before the page is asked to re-measure, make sure the thing
                // it will measure against is the right size. If the window is
                // still short, a reflow just re-fits the page to a short
                // window and the band stays.
                recoverWindowSizeIfStale()

                // Twice, because the size the page should settle at is not
                // known yet at the first one: the system bars are still
                // animating in and the WebView has not been through the inset
                // pass that endFullscreenTransition requests 500ms from now.
                //
                // The early one clears the immersive layout straight away so
                // the feed is not visibly wrong while the bars slide back.
                // The later one runs after that inset pass has resized the
                // WebView, and is the one that leaves it correct. Cheap
                // enough to do both: two layout reads, once per exit.
                forcePageRelayout(binding.webView)
                binding.root.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        recoverWindowSizeIfStale()
                        forcePageRelayout(binding.webView)
                    }
                }, 650)

                // V4 Step 3: Re-inject after exiting fullscreen (helps restore feed state)
                binding.root.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        injectAll(binding.webView)
                    }
                }, 300)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                ) {
                    callback?.invoke(origin, true, false)
                } else {
                    // Keep the callback and answer it once the user decides.
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
    }

    /**
     * Make the page re-measure itself.
     *
     * The native side of the fullscreen bug is fixed: the padding lives on
     * contentRoot, it is measured ignoring bar visibility, and the feed is
     * hidden with INVISIBLE so it keeps its measured size. What none of that
     * reaches is the layout *inside* the WebView. Facebook's lite renderer
     * caps its screen at a height it worked out earlier - the traces caught
     * it holding "min-height:100vh;width:360px" - and it only recomputes when
     * something makes it. Leaving fullscreen is not, by itself, one of those
     * things, so the page can come back still laid out for the immersive
     * viewport: content shifted up, dead strip at the bottom.
     *
     * Reading a layout property is what forces it. That is a deliberate
     * forced synchronous layout - normally a thing to avoid, here the entire
     * point - and it is why a scroll always appeared to cure this, and why
     * the bug hid for a day while the diagnostic tracer was installed: that
     * tracer read getBoundingClientRect() on a 400ms timer and was
     * accidentally doing this 2.5 times a second.
     *
     * Once, on exit. Not a poll: a timer that forces a layout several times
     * a second to paper over a stale one would cost battery on every screen
     * in the app to fix a bug on one.
     */
    private fun forcePageRelayout(view: WebView?) {
        view ?: return
        view.evaluateJavascript(
            """
            (function() {
              try {
                var de = document.documentElement;
                var b = document.body;
                // The read is the work. Assigning it to nothing would let a
                // minifier or the JIT drop the whole statement, so the values
                // are kept and handed back.
                var h = de ? de.clientHeight : 0;
                var bh = b ? b.getBoundingClientRect().height : 0;
                // Tell the page as well. The lite renderer listens for resize
                // to recompute its screen height, and coming out of immersive
                // the viewport really has changed size - it just was not
                // always told.
                try { window.dispatchEvent(new Event('resize')); } catch (e) {}
                return h + 'x' + Math.round(bh);
              } catch (e) { return 'err'; }
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * The window itself can be left short, and no pass says so.
     *
     * windowSoftInputMode is adjustResize. That does not pad the window, it
     * *shrinks* it, and the activity only learns it has grown back through an
     * inset pass. Reels and Stories are reached from a feed where a comment
     * box may have been focused, and the lite renderer swaps those screens in
     * place without navigating - no navigation, no resize, no pass. The
     * activity carries on laid out for the smaller window.
     *
     * Measured on the device, on a reel and on a story minutes apart: content
     * ends at y=2024 of 2400 on both, to the pixel, and the 375px below it is
     * RGB(5,5,5). root is painted @color/fb_bg (#18191A, reads as 24,25,27),
     * so that strip is not root and not the page - it is the bare window,
     * black because the AMOLED overlay is on by default. Being outside root
     * is why neither padding nor a page reflow can reach it.
     *
     * So: measure the window against the display, and if it is short with no
     * keyboard to account for it, ask for a pass.
     *
     * Deliberately not a listener on every layout - that risks a loop, since
     * requesting insets causes a layout. This is called at the few moments
     * the window is known to be suspect.
     */
    private fun recoverWindowSizeIfStale() {
        val root = binding.root
        val insets = ViewCompat.getRootWindowInsets(root) ?: return
        // A visible keyboard is a legitimate reason to be short.
        if (insets.isVisible(WindowInsetsCompat.Type.ime())) return

        val windowH = root.height
        if (windowH <= 0) return

        val screenH = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.heightPixels
        }

        // Split screen and freeform are genuinely smaller and must be left
        // alone, so only a shortfall in the range a keyboard leaves counts.
        // A tolerance keeps rounding and a cutout from triggering it.
        val short = screenH - windowH
        if (short > 24 && short < screenH / 2) {
            ViewCompat.requestApplyInsets(root)
            root.requestLayout()
        }
    }

    private fun enterImmersive(on: Boolean) {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            updateSystemBarIcons()
        }
    }

    // ---------------------------------------------------------------- camera

    private fun buildCaptureIntents(params: WebChromeClient.FileChooserParams?): List<Intent> {
        if (!hasPermission(Manifest.permission.CAMERA)) return emptyList()
        val accept = params?.acceptTypes?.joinToString(",")?.lowercase(Locale.ROOT) ?: ""
        val wantsImage = accept.isEmpty() || accept.contains("image") || accept.contains("*/*")
        if (!wantsImage) return emptyList()

        return try {
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "IMG_$stamp.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraPhotoUri = uri
            listOf(
                Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- downloads

    private fun setupDownloadManager() {
        binding.webView.setDownloadListener { url, userAgent, disposition, mimetype, _ ->
            when {
                url.startsWith("blob:") -> {
                    // Handled by the JS bridge; ask the page to convert it.
                    binding.webView.evaluateJavascript(blobFetchScript(url), null)
                }
                url.startsWith("data:") -> saveDataUrl(url)
                else -> enqueueDownload(url, userAgent, disposition, mimetype)
            }
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        disposition: String?,
        mimetype: String?
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }

            val name = uniqueName(URLUtil.guessFileName(url, disposition, mimetype))
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                addRequestHeader("User-Agent", userAgent ?: "")
                // Without cookies the Facebook CDN answers 403.
                CookieManager.getInstance().getCookie(url)?.let {
                    addRequestHeader("Cookie", it)
                }
                binding.webView.url?.let { addRequestHeader("Referer", it) }
                setTitle(name)
                setDescription(getString(R.string.download_started))
                setMimeType(mimetype)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(getString(R.string.download_started))
        } catch (e: Exception) {
            toast(getString(R.string.download_failed))
        }
    }

    private fun uniqueName(base: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var candidate = base
        var i = 1
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        while (File(dir, candidate).exists() && i < 500) {
            candidate = "$stem($i)$ext"
            i++
        }
        return candidate
    }

    private fun blobFetchScript(url: String) = """
        (function(){
          var x=new XMLHttpRequest();
          x.open('GET','$url',true);x.responseType='blob';
          x.onload=function(){
            var r=new FileReader();
            r.onloadend=function(){ FBPro.onBlobDownload(r.result,''); };
            r.readAsDataURL(x.response);
          };
          x.send();
        })();
    """.trimIndent()

    private fun saveDataUrl(dataUrl: String, suggested: String = "") {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return
            val meta = dataUrl.substring(5, comma)
            val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            val ext = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime) ?: "bin"
            val name = uniqueName(
                suggested.ifBlank { "FBPro_${System.currentTimeMillis()}.$ext" }
            )
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ).apply { mkdirs() }
            File(dir, name).outputStream().use { it.write(bytes) }
            toast(getString(R.string.download_completed))
        } catch (e: Exception) {
            toast(getString(R.string.download_failed))
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onBlobDownload(dataUrl: String, filename: String) {
            runOnUiThread { saveDataUrl(dataUrl, filename) }
        }

        /**
         * Reported by the page whenever its scroll position changes. Covers
         * the case where Facebook scrolls an inner div and the WebView's own
         * scrollY never moves.
         */
        @JavascriptInterface
        fun onScrollState(atTop: Boolean) {
            pageAtTop = atTop
        }

        /**
         * Inspect mode captured an element. Copy it to the clipboard and tell
         * the user, so it can be pasted straight into a bug report.
         */
        @JavascriptInterface
        fun onAdHtml(text: String) {
            runOnUiThread {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("Dustbook ad markup", text)
                    )
                    if (prefs.haptics) {
                        binding.root.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS
                        )
                    }
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.inspect_copied),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity, e.message ?: "copy failed", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        /**
         * The page finished a soft refresh. handled=false means it found no
         * way to refresh itself, so we do a real reload after all.
         */
        @JavascriptInterface
        fun onSoftRefresh(handled: Boolean) {
            if (!softRefreshPending) return
            softRefreshPending = false
            runOnUiThread {
                if (handled) {
                    binding.swipeRefresh.isRefreshing = false
                } else {
                    binding.webView.reload()
                }
            }
        }

        /**
         * The page reported the posts or reels it is showing. Merge them into
         * the offline store and pull their media down.
         */
        @JavascriptInterface
        fun onOfflineItems(section: String, json: String, done: Boolean) {
            if (!prefs.offlineMode || !isOnline) return
            // The pipeline runs strictly in sequence: while reels are being
            // downloaded, feed posts must not be captured or downloaded
            // alongside them. The post passes resume only after reels are
            // done (posts-300) — this is the owner's flow, never swapped.
            if (section == OfflineFeed.SECTION_FEED && BackgroundSyncManager.isRunning &&
                (BackgroundSyncManager.currentStep == "reels" ||
                 BackgroundSyncManager.currentStep == "wait-video")) return
            val target = prefs.offlineReelTarget.coerceAtLeast(30)
            val items = OfflineSync.parseItems(json)
            if (items.isEmpty()) return

            // Only save genuinely NEW content — skip everything we already
            // hold so viewed reels are never re-saved and served offline.
            val existingIds = OfflineFeed.knownIds(section).toSet()
            val newItems = items.filter {
                it.id.isBlank() || it.id !in existingIds
            }
            if (newItems.isEmpty()) return

            OfflineFeed.addItems(section, newItems, target)
            OfflineFeed.prefetch(newItems, includeVideo = prefs.offlineVideo)
        }


        /**
         * The page hands us its own document so it can be stored.
         *
         * Facebook answers m.facebook.com with HTTP 400 to any plain HTTP
         * client, so the app cannot fetch these pages itself - a live WebView
         * is the only place they exist.
         */
        @JavascriptInterface
        fun onOfflinePage(section: String, html: String) {
            if (!prefs.offlineMode || !isOnline) return
            AppExecutors.background.execute {
                OfflineDocs.storeFromPage(
                    when (section) {
                        OfflineFeed.SECTION_REELS -> "reels"
                        OfflineFeed.SECTION_STORIES -> "stories"
                        else -> "home"
                    },
                    html
                )
            }
        }

        /**
         * Offline, the user tapped one of Facebook's own tabs. Load the
         * stored copy of that screen - it is served from disk by
         * [OfflineDocs] through shouldInterceptRequest, exactly as the first
         * screen was.
         */
        @JavascriptInterface
        fun onOfflineNav(screen: String, url: String) {
            runOnUiThread { binding.webView.loadUrl(url) }
        }

        /**
         * Offline, the user tapped a tab we hold nothing for. Say so rather
         * than leaving the tap silently doing nothing, which is what the dead
         * action id did.
         */
        @JavascriptInterface
        fun onOfflineNavMissing(screen: String) {
            // Deliberately silent. A toast here was ours, not Facebook's, and
            // online no such message exists - the tap simply does nothing when
            // there is nothing to show. The app's own offline banner, outside
            // the WebView, already says why.
        }

        /**
         * Page reports whether a video or audio element is actually playing.
         *
         * This is what decides background audio now. The URL cannot: the lite
         * renderer swaps the Reels screen in place without navigating, so the
         * address stays on the home feed for the whole time a reel is playing.
         */
        @JavascriptInterface
        fun onMediaState(playing: Boolean) {
            mediaPlaying = playing
        }

        /**
         * Page reports a fillable login form is present.
         *
         * Nothing acts on this any more -- the native login screen it used to
         * gate is gone. The probe script still calls it on every load, so the
         * method has to stay: an injected script calling a missing
         * @JavascriptInterface method throws inside the page.
         */
        @JavascriptInterface
        fun onLoginFormReady(ready: Boolean) {
            // no-op
        }

        /** Page reports a password field / login form is on screen. */
        @JavascriptInterface
        fun onAuthState(loggedOut: Boolean) {
            if (domSaysLoggedOut == loggedOut) return
            domSaysLoggedOut = loggedOut
            runOnUiThread { evaluateAuthState(binding.webView.url) }
        }

        /**
         * The offline page reports where the user is so the next session can
         * resume from the same point. Called periodically while the user
         * scrolls through offline content.
         */
        @JavascriptInterface
        fun reportPosition(type: String, id: String) {
            if (isOnline) return
            when (type) {
                "reel" -> if (id.isNotBlank()) prefs.offlineResumeReel = id
                "stories", "story" -> if (id.isNotBlank()) prefs.offlineResumeStories = id
                "feed" -> prefs.offlineResumeFeed = "SCROLL:$id"
            }
        }
    }


    /**
     * Silent background update check, at most once every 12 hours.
     * Runs on the blocklist thread; never blocks startup and stays quiet
     * unless there is genuinely a newer release the user has not skipped.
     */
    /**
     * @param force true when the user asked for it from settings, which must
     *              bypass the 12-hour throttle.
     */
    // ================= connectivity / offline =================

    private fun setupConnectivity() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        isOnline = hasNetwork(cm)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val was = isOnline
                isOnline = true
                runOnUiThread {
                    // Coming back online: refresh only if we were showing the
                    // offline error page, never yank a page the user is reading.
                    if (!was && binding.errorView.visibility == View.VISIBLE) {
                        binding.errorView.visibility = View.GONE
                        binding.webView.visibility = View.VISIBLE
                        binding.webView.reload()
                    }
                    // Back online: one engine, not two. Starting
                    // OfflineManager here as well put a second offscreen
                    // WebView on the main thread.
                    if (!was) {
                        BackgroundSyncManager.onNetworkRestored()
                        SyncService.startIfNeeded(applicationContext)
                    }
                }
            }

            override fun onLost(network: Network) {
                isOnline = hasNetwork(cm)
                if (!isOnline) runOnUiThread {
                    toast(getString(R.string.offline_banner))
                }
            }
        }
        connectivityCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            connectivityCallback = null
        }
    }

    private fun hasNetwork(cm: ConnectivityManager): Boolean = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true
    }

    /**
     * Warm the offline store for the page we are on, on a background thread.
     * This runs after the page is already displayed, so it never delays
     * rendering the way the old inline fetch did.
     */
    private fun warmOfflineCache(url: String?) {
        if (!prefs.offlineMode || url == null || !isOnline) return
        AppExecutors.background.execute {
            try {
                binding.webView.post {
                    binding.webView.evaluateJavascript(
                        """
                        (function(){
                          var out=[];
                          var im=document.images;
                          for(var i=0;i<im.length && out.length<40;i++)
                            if(im[i].currentSrc) out.push(im[i].currentSrc);
                          var ls=document.querySelectorAll('link[rel=stylesheet][href]');
                          for(var j=0;j<ls.length && out.length<60;j++) out.push(ls[j].href);
                          return out.join('\n');
                        })();
                        """.trimIndent()
                    ) { res -> cacheUrlsAsync(res) }
                }
            } catch (e: Exception) { /* best effort */ }
        }
    }

    private fun cacheUrlsAsync(jsResult: String?) {
        val raw = jsResult ?: return
        if (raw.length < 4) return
        AppExecutors.heavyBackground.execute {
            val list = raw.trim('"').split("\\n").map {
                it.replace("\\/", "/").trim()
            }.filter { it.startsWith("http") }
            for (u in list.take(60)) {
                if (!isOnline) break
                try {
                    if (OfflineCache.has(u)) continue
                    val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 10000
                        setRequestProperty("Accept-Encoding", "identity")
                        setRequestProperty("User-Agent", binding.webView.settings.userAgentString)
                        CookieManager.getInstance().getCookie(u)?.let {
                            setRequestProperty("Cookie", it)
                        }
                    }
                    if (conn.responseCode != 200) { conn.disconnect(); continue }
                    val enc = conn.contentEncoding
                    if (enc != null && !enc.equals("identity", true)) {
                        conn.disconnect(); continue
                    }
                    val mime = conn.contentType ?: "application/octet-stream"
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    OfflineCache.put(u, mime, bytes)
                } catch (e: Exception) { /* skip this asset */ }
            }
            OfflineCache.trimIfNeeded()
        }
    }

    /**
     * Fetch a resource ourselves so the bytes can be stored for offline use.
     * Only runs for cacheable media; everything else falls through to the
     * WebView's own networking.
     */
    private fun fetchAndCache(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
                for ((k, v) in request.requestHeaders) {
                    // Never forward Accept-Encoding. If we do, the CDN returns
                    // gzip bytes which we would store verbatim and later replay
                    // without a Content-Encoding header - the WebView then reads
                    // compressed bytes as text, every stylesheet and script
                    // fails to parse, and the page comes up blank on the second
                    // launch. Asking for identity keeps the cache readable.
                    if (!k.equals("Accept-Encoding", true) &&
                        !k.equals("Range", true) &&
                        !k.equals("If-None-Match", true) &&
                        !k.equals("If-Modified-Since", true)
                    ) {
                        setRequestProperty(k, v)
                    }
                }
                setRequestProperty("Accept-Encoding", "identity")
                CookieManager.getInstance().getCookie(url)?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            // Only a plain 200 is cacheable. 206 partial content and 304 are not.
            if (conn.responseCode != 200) { conn.disconnect(); return null }

            // If the server compressed anyway, do not cache it: we cannot
            // replay it correctly.
            val encoding = conn.contentEncoding
            if (encoding != null && !encoding.equals("identity", true)) {
                conn.disconnect()
                return null
            }

            val mime = conn.contentType ?: "application/octet-stream"
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                var n = input.read(chunk)
                var total = 0
                while (n > 0) {
                    buf.write(chunk, 0, n)
                    total += n
                    if (total > 25 * 1024 * 1024) { conn.disconnect(); return null }
                    n = input.read(chunk)
                }
            }
            conn.disconnect()
            val bytes = buf.toByteArray()
            OfflineCache.put(url, mime, bytes)
            WebResourceResponse(
                mime.substringBefore(';'),
                "utf-8",
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream(bytes)
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- misc

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage(getString(R.string.permission_rationale))
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .setNegativeButton(getString(R.string.dismiss), null)
            .show()
    }

    private fun showErrorPage() {
        // Recoverable: the WebView comes back on retry.
        binding.webView.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE

        // Offer saved content only when we hold items whose media is really
        // on disk. Counting cache files was wrong: a store full of icons and
        // stylesheets made the button appear with nothing behind it.
        val canOffline = prefs.offlineRead && hasAnythingOffline()
        binding.errorOffline.visibility = if (canOffline) View.VISIBLE else View.GONE
    }

    /**
     * Remember where the user was in the offline content so the next session
     * picks up from the same point instead of starting from the beginning.
     *
     * Called from onPause, so it runs while the WebView is still alive and
     * the page can answer. Uses evaluateJavascript whose callback may not
     * complete before the process is killed, but the best-effort save is
     * enough: on the rare miss the user simply starts from the top (which
     * is what always happened before).
     */
    private fun saveOfflinePosition() {
        if (isOnline) return
        val url = binding.webView.url ?: return
        val isReel = url.contains("/reel") || url.contains("/reels") ||
            url.contains("fb.watch")
        val isStory = url.contains("/stories/") || url.contains("/story/")

        binding.webView.evaluateJavascript("""
        (function(){
          var container = document.querySelector('[data-type="vscroller"]');
          if (!container) return '';
          var mid = container.clientHeight / 2;
          if (container.scrollTop > 0) {
            return 'FEED:' + container.scrollTop;
          }
          var cards = container.querySelectorAll(
            '[data-video-id],[data-story-id]');
          var best = '', bestDist = 999999;
          for (var i = 0; i < cards.length; i++) {
            var r = cards[i].getBoundingClientRect();
            var dist = Math.abs((r.top + r.bottom) / 2 - mid);
            if (dist < bestDist) {
              bestDist = dist;
              best = cards[i].getAttribute('data-video-id') ||
                     cards[i].getAttribute('data-story-id') || '';
            }
          }
          return best;
        })();
        """.trimIndent()) { result ->
            val raw = result?.trim('"')?.takeIf { it.isNotBlank() } ?: return@evaluateJavascript
            if (raw.startsWith("FEED:")) {
                prefs.offlineResumeFeed = "SCROLL:" + raw.removePrefix("FEED:")
            } else if (isReel && raw.isNotBlank()) {
                prefs.offlineResumeReel = raw
            } else if (isStory && raw.isNotBlank()) {
                prefs.offlineResumeStories = raw
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startBgAudioService() {
        if (AudioService.running) return
        val intent = Intent(this, AudioService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) { /* permission may be denied */ }
    }

    private fun stopBgAudioService() {
        if (!AudioService.running) return
        val intent = Intent(this, AudioService::class.java).apply {
            action = AudioService.ACTION_STOP
        }
        try { startService(intent) } catch (e: Exception) {}
    }

    /**
     * Whether a main-frame failure is worth retrying.
     *
     * These are the codes a mobile radio produces while it is still coming up:
     * DNS not yet resolving, the connection refused mid-handover, or a slow
     * cell timing out. None of them mean the network is unusable a second
     * later. A certificate or file error, by contrast, will fail identically
     * however many times it is retried.
     */
    private fun isTransientNetworkError(code: Int): Boolean = when (code) {
        android.webkit.WebViewClient.ERROR_HOST_LOOKUP,
        android.webkit.WebViewClient.ERROR_CONNECT,
        android.webkit.WebViewClient.ERROR_TIMEOUT,
        android.webkit.WebViewClient.ERROR_IO,
        android.webkit.WebViewClient.ERROR_PROXY_AUTHENTICATION,
        android.webkit.WebViewClient.ERROR_UNKNOWN -> true
        else -> false
    }

    private companion object {
        /** Enough to ride out a radio coming up, short enough to stay honest. */
        const val MAX_MAIN_FRAME_RETRIES = 3
    }
}
