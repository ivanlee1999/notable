package com.ethran.notable

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.defaultToolbarPosition
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.StrokeMigrationHelper
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.couch.CouchSyncController
import com.ethran.notable.ui.AppEventUiBridge
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.SyncWorkUiBridge
import com.ethran.notable.ui.components.NotableApp
import com.ethran.notable.ui.components.crashLogsAsText
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.utils.hasUsableStorage
import com.onyx.android.sdk.api.device.epd.EpdController
import dagger.hilt.android.AndroidEntryPoint
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


private const val TAG = "MainActivity"
const val APP_SETTINGS_KEY = "APP_SETTINGS"
const val PACKAGE_NAME = "com.ethran.notable"

// TODO: Check if migrating to LocalConfiguration in Compose is good idea
var SCREEN_WIDTH = EpdController.getEpdHeight().toInt()
var SCREEN_HEIGHT = EpdController.getEpdWidth().toInt()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Delay the init till we have the permissions required
    @Inject
    lateinit var kvProxy: dagger.Lazy<KvProxy>

    @Inject
    lateinit var strokeMigrationHelper: dagger.Lazy<StrokeMigrationHelper>

    @Inject
    lateinit var editorSettingCacheManager: dagger.Lazy<EditorSettingCacheManager>

    @Inject
    lateinit var appRepositoryLazy: dagger.Lazy<AppRepository>

    @Inject
    lateinit var exportEngineLazy: dagger.Lazy<ExportEngine>

    @Inject
    lateinit var pageDataManager: dagger.Lazy<PageDataManager>

    @Inject
    lateinit var syncScheduler: dagger.Lazy<SyncScheduler>

    @Inject
    lateinit var couchSyncController: dagger.Lazy<CouchSyncController>

    @Inject
    lateinit var snackDispatcher: SnackDispatcher

    @Inject
    lateinit var appEventUiBridge: AppEventUiBridge

    @Inject
    lateinit var syncWorkUiBridge: SyncWorkUiBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreen()
        // Telemetry deliberately does NOT start here. Nothing may be uploaded before the stored
        // consent has been read, and that read needs the database — so it happens in the init
        // effect below, next to the settings load. See [Telemetry].
        maybeShowCrashLoopHint()

        Log.i(TAG, "Notable started")

        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels

        trackSystemGestureBlocking()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                syncWorkUiBridge.syncUiEvents.collect { event ->
                    val message = event.errorArg?.let { arg ->
                        getString(event.messageResId, arg)
                    } ?: getString(event.messageResId)
                    // A failure message is longer and more important, so give it time to be read.
                    snackDispatcher.showOrUpdateSnack(
                        SnackConf(text = message, duration = if (event.isError) 7000 else 3000)
                    )
                }
            }
        }

        val snackState = SnackState()

        setContent {
            var isInitialized by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (hasUsableStorage(this@MainActivity)) {
                    withContext(Dispatchers.IO) {
                        // Init app settings, also do migration
                        // The default is only reached on a first run, so the rail's starting
                        // edge can follow the display it is starting on — see
                        // defaultToolbarPosition. An install that already has settings keeps
                        // whatever it stored.
                        val savedSettings =
                            kvProxy.get().getOrDefault(
                                APP_SETTINGS_KEY,
                                AppSettings.serializer(),
                                AppSettings(
                                    version = 1,
                                    toolbarPosition = defaultToolbarPosition(
                                        with(resources.displayMetrics) { (widthPixels / density).dp }
                                    ),
                                )
                            )

                        GlobalAppSettings.update(savedSettings)
                        // The first moment we are entitled to an opinion about uploading, because
                        // it is the first moment the stored answer is known.
                        Telemetry.startIfConsented(application, savedSettings.telemetryConsent)
                        strokeMigrationHelper.get().reencodeStrokePointsToBinary()
                        pageDataManager.get()
                            .registerComponentCallbacks(this@MainActivity.applicationContext)
                        editorSettingCacheManager.get().init()
                    }
                    restorePeriodicSyncSchedule()
                    // Trigger initial sync on app startup (fails silently if offline)
                    triggerInitialSync()
                }
                isInitialized = true
            }
            InkaTheme {
                CompositionLocalProvider(LocalSnackContext provides snackState) {
                    if (isInitialized) {
                        NotableApp(
                            // Call .get() here so they are only instantiated AFTER the permission check runs
                            exportEngine = exportEngineLazy.get(),
                            snackState = snackState,
                            snackDispatcher = snackDispatcher,
                            appRepository = appRepositoryLazy.get()
                        )
                    } else {
                        ShowInitMessage()
                    }
                }
            }
        }
    }


    /**
     * If startup detected a likely crash loop, show one dismissible snack with a "Copy logs" action
     * that copies the local crash files to the clipboard. Simplest possible surface — the full
     * viewer lives in Settings → Debug. Returns whether a loop was detected (so a test
     * harness can crash only on the non-recovery launch).
     */
    private fun maybeShowCrashLoopHint(): Boolean {
        if ((application as? NotableApp)?.consumeCrashLoopDetected() != true) return false
        snackDispatcher.showOrUpdateSnack(
            SnackConf(
                text = "Notable restarted after repeated crashes.",
                duration = null, // stays until dismissed
                actions = listOf(
                    "Copy logs" to {
                        val text = crashLogsAsText(this)
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Notable crash logs", text))
                        Toast.makeText(this, "Copied crash logs", Toast.LENGTH_SHORT).show()
                    }
                )
            )
        )
        return true
    }

    private fun triggerInitialSync() {
        lifecycleScope.launch {
            try {
                val settings = kvProxy.get().getSyncSettings()
                // Each backend decides for itself. WebDAV honours "sync on app start", because a
                // whole-tree reconcile is expensive enough to be worth a setting; CouchDB always
                // catches up, because its startup pass is one cheap change-feed read. Neither
                // fires when the backend is OFF.
                val shouldSync = when {
                    settings.couchActive -> true
                    settings.webdavActive -> settings.syncOnAppStart
                    else -> false
                }
                if (shouldSync) {
                    Log.i(TAG, "Triggering one-time sync on app startup via WorkManager")
                    syncScheduler.get().triggerImmediateSync()
                }
            } catch (e: Exception) {
                Log.i(TAG, "Initial sync setup failed: ${e.message}")
            }
        }
    }

    private fun restorePeriodicSyncSchedule() {
        lifecycleScope.launch {
            try {
                val settings = kvProxy.get().getSyncSettings()
                syncScheduler.get().reconcilePeriodicSync(settings)
            } catch (e: Exception) {
                Log.i(TAG, "Periodic sync reconcile failed: ${e.message}")
            }
        }
    }

    /**
     * The CouchDB change feed runs only while notable is on screen.
     *
     * This is the activity's own `onStart`/`onStop` rather than a process-lifecycle observer:
     * `androidx.lifecycle:lifecycle-process` is not a dependency, and notable is a single-activity
     * app, so these two callbacks *are* "foregrounded" and "not foregrounded". A long poll held
     * open by a backgrounded app is a socket and a wakelock nobody asked for.
     */
    override fun onStart() {
        super.onStart()
        // No-op unless CouchDB is the selected backend; the controller checks for itself.
        couchSyncController.get().start()
    }

    override fun onStop() {
        super.onStop()
        val controller = couchSyncController.get()
        // Read before `stop()`, which cancels the debounce timer this asks about.
        val unfinished = controller.hasUnfinishedWork
        controller.stop()

        // Enqueued *before* the push, and deliberately not instead of it. WorkManager writes the
        // request to disk as it is enqueued; the fire-and-forget push below holds its intent only
        // in a process Android may kill at any moment after this returns. So the worker is what
        // remembers, and the push is the fast path that usually makes it a no-op.
        //
        // Not a foreground service: near-real-time sync is the on-screen loop's job, and holding a
        // notification-bearing service to keep it running while notable is not on screen would be
        // spending the user's battery on latency they cannot see. A queued worker is the right
        // shape — it costs nothing until it runs, and it survives the process dying.
        if (unfinished) {
            Log.i(TAG, "Backgrounding with work still queued; scheduling a durable catch-up")
            syncScheduler.get().triggerImmediateSync()
        }

        // Last chance before the process may be killed. The push is per-document, so a truncated
        // run leaves the rest queued rather than a half-written notebook.
        controller.requestPushNow()
    }

    override fun onRestart() {
        super.onRestart()
        // redraw after device sleep
        this.lifecycleScope.launch {
            CanvasEventBus.reinitSignal.emit(Unit)
        }
    }

    override fun onPause() {
        super.onPause()
        this.lifecycleScope.launch {
            Log.d("QuickSettings", "App is paused - maybe quick settings opened?")
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        Log.d(TAG, "OnWindowFocusChanged: $hasFocus")
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullScreen()
        }
        lifecycleScope.launch {
            CanvasEventBus.onFocusChange.emit(hasFocus)
        }
    }

    override fun onResume() {
        super.onResume()
        enableFullScreen()
        lifecycleScope.launch {
            CanvasEventBus.onFocusChange.emit(true)
        }
    }

    // Onyx SystemUI's TouchInteractionService swallows multi-finger touches for its
    // global gestures (three-finger screenshot), which steals our three-finger
    // swipes. Raising this flag makes it stand down — but the same pipeline serves
    // the side/bottom edge navigation swipes, so blocking is opt-in via settings.
    // The flag is global and sticky (nothing else clears it), so it is held only
    // while the activity is resumed AND the setting is on, released otherwise.
    private var onyxSystemGesturesBlocked = false

    private fun trackSystemGestureBlocking() {
        if (!DeviceCompat.isOnyxDevice) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    snapshotFlow { GlobalAppSettings.current.blockSystemGestures }
                        .collect { setOnyxSystemGesturesBlocked(it) }
                } finally {
                    setOnyxSystemGesturesBlocked(false)
                }
            }
        }
    }

    private fun setOnyxSystemGesturesBlocked(blocked: Boolean) {
        if (blocked == onyxSystemGesturesBlocked) return
        onyxSystemGesturesBlocked = blocked
        val action =
            if (blocked) "onyx.action.INTERCEPT_GESTURE"
            else "onyx.action.DO_NOT_INTERCEPT_GESTURE"
        sendBroadcast(Intent(action))
    }


    // when the screen orientation is changed, set new screen width restart is not necessary,
    // as we need first to update page dimensions which is done in EditorView()
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.i(TAG, "Switched to Landscape")
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.i(TAG, "Switched to Portrait")
        }
        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels
    }

    private fun enableFullScreen() {
        // Clearer intent broadcasting syntax
        val optimizeIntent = Intent("com.onyx.app.optimize.setting").apply {
            putExtra("optimize_fullScreen", true)
            putExtra(
                "optimize_pkgName", packageName
            ) // Use Context.packageName instead of hardcoding
        }
        sendBroadcast(optimizeIntent)

        // Modern, backwards-compatible AndroidX way to handle fullscreen / insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
@Preview(showBackground = true)
fun ShowInitMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Initializing...",
            color = Color.Black,
            fontSize = 30.sp
        )
    }
}