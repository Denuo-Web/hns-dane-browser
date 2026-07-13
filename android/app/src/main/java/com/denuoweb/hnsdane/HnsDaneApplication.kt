package com.denuoweb.hnsdane

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.denuoweb.hnsdane.net.BundledHeaderSyncBridge
import com.denuoweb.hnsdane.net.HnsSyncScheduler
import com.denuoweb.hnsdane.net.HnsSyncSnapshot
import com.denuoweb.hnsdane.ui.BrowserThemePreferences
import com.denuoweb.hnsdane.ui.HnsResolutionPreferences
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class HnsDaneApplication : Application() {
    private val webViewStartupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hns-webview-startup")
    }
    private val syncListeners = CopyOnWriteArraySet<(HnsSyncSnapshot) -> Unit>()
    private val foregroundActivities = ForegroundActivityCounter(
        onForeground = ::startForegroundSync,
        onBackground = ::stopForegroundSync,
    )
    @Volatile
    private var foregroundSync: HnsSyncScheduler? = null

    @Volatile
    private var latestSyncSnapshot: HnsSyncSnapshot? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppLifecycleCallbacks(foregroundActivities))
        startWebViewInitialization()
    }

    internal fun observeSync(listener: (HnsSyncSnapshot) -> Unit): Closeable {
        syncListeners += listener
        latestSyncSnapshot?.let(listener)
        return Closeable { syncListeners -= listener }
    }

    internal fun onHandshakeNetworkChanged() {
        if (!foregroundActivities.isForeground) {
            latestSyncSnapshot = null
            return
        }
        stopForegroundSync()
        latestSyncSnapshot = null
        startForegroundSync()
    }

    private fun startForegroundSync() {
        if (foregroundSync != null) {
            return
        }

        val scheduler = HnsSyncScheduler(
            filesDir,
            bridge = BundledHeaderSyncBridge(this),
            network = { HnsResolutionPreferences.handshakeNetworkId(this) },
        )
        foregroundSync = scheduler
        scheduler.start sync@{ snapshot ->
            val publish = synchronized(this) {
                if (foregroundSync !== scheduler) {
                    false
                } else {
                    latestSyncSnapshot = snapshot
                    true
                }
            }
            if (!publish) {
                return@sync
            }
            syncListeners.forEach { listener -> listener(snapshot) }
        }
    }

    @Synchronized
    private fun stopForegroundSync() {
        val scheduler = foregroundSync ?: return
        foregroundSync = null
        scheduler.close()
    }

    private fun startWebViewInitialization() {
        val startupConfig = WebViewStartUpConfig.Builder(webViewStartupExecutor).build()
        runCatching {
            WebViewCompat.startUpWebView(
                this,
                startupConfig,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        logStartupAudit(result)
                    }

                    override fun onError(error: WebViewStartupException) {
                        Log.w(TAG, "WebView asynchronous startup failed", error)
                    }
                },
            )
        }.onFailure { error ->
            Log.w(TAG, "WebView asynchronous startup is unavailable", error)
        }
    }

    private fun logStartupAudit(result: WebViewStartUpResult) {
        if (!BuildConfig.DEBUG) {
            return
        }

        result.uiThreadBlockingStartUpLocations.orEmpty().forEach { location ->
            Log.d(TAG, "WebView startup blocked the UI thread", location.stackInformation)
        }
        result.nonUiThreadBlockingStartUpLocations.orEmpty().forEach { location ->
            Log.d(TAG, "WebView startup waited on a background thread", location.stackInformation)
        }
    }

    private companion object {
        const val TAG = "HnsDaneApplication"
    }
}

private class AppLifecycleCallbacks(
    private val foregroundActivities: ForegroundActivityCounter,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        BrowserThemePreferences.applyTo(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
        foregroundActivities.activityStarted()
    }
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        foregroundActivities.activityStopped(activity.isChangingConfigurations)
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal class ForegroundActivityCounter(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) {
    private var startedActivities = 0
    private var changingConfigurations = 0

    val isForeground: Boolean
        get() = startedActivities > 0 || changingConfigurations > 0

    fun activityStarted() {
        val wasBackgrounded = startedActivities == 0
        startedActivities += 1
        if (changingConfigurations > 0) {
            changingConfigurations -= 1
        } else if (wasBackgrounded) {
            onForeground()
        }
    }

    fun activityStopped(isChangingConfigurations: Boolean) {
        check(startedActivities > 0) { "activity stop received without a matching start" }
        startedActivities -= 1
        if (isChangingConfigurations) {
            changingConfigurations += 1
        } else if (startedActivities == 0 && changingConfigurations == 0) {
            onBackground()
        }
    }
}
