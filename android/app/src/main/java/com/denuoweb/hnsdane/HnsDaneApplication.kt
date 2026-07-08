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
import com.denuoweb.hnsdane.ui.BrowserThemePreferences
import java.util.concurrent.Executors

class HnsDaneApplication : Application() {
    private val webViewStartupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hns-webview-startup")
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ThemeLifecycleCallbacks)
        startWebViewInitialization()
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

private object ThemeLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        BrowserThemePreferences.applyTo(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
