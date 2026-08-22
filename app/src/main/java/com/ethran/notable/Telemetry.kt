package com.ethran.notable

import android.app.Application
import com.ethran.notable.data.datastore.AppSettings

/**
 * The one place that decides whether logs may leave the device.
 *
 * ShipBook is a log *shipper*, not a crash reporter bolted on at the edge: once it is started
 * every line the app logs is uploaded. So the gate has to be on `start()` itself rather than on
 * individual call sites — which is also why nothing here needs the 65 `ShipBook.getLogger`
 * call sites to change. An un-started SDK is a no-op uploader, and `io.shipbook.shipbooksdk.Log`
 * still writes to logcat, so a device that has said no keeps its in-app log viewer and its
 * bug reports (both read logcat) and sends nothing.
 *
 * The consent itself is tri-state — see [AppSettings.TelemetryConsent]. "Not asked yet" is not
 * "no": it is what makes the prompt appear exactly once.
 */
object Telemetry {

    @Volatile
    private var started = false

    /** True once the uploader is up. Read by the settings screen to describe the current state. */
    val isRunning: Boolean get() = started

    /**
     * Starts the uploader if — and only if — the user has agreed to it.
     *
     * Idempotent, so it can be called again when consent is granted later without a second
     * session being opened. Returns whether the uploader is running as a result.
     */
    fun startIfConsented(app: Application, consent: AppSettings.TelemetryConsent): Boolean {
        if (started) return true
        if (!consent.allowsUpload) return false
        if (BuildConfig.SHIPBOOK_APP_ID.isBlank() || BuildConfig.SHIPBOOK_APP_KEY.isBlank()) {
            // A build without credentials (a fork, or a local debug build) has nowhere to send
            // anything. Treat it as consented-but-inert rather than failing.
            return false
        }

        io.shipbook.shipbooksdk.ShipBook.start(
            app, BuildConfig.SHIPBOOK_APP_ID, BuildConfig.SHIPBOOK_APP_KEY
        )
        started = true

        // ShipBook installs its own uncaught handler in start(); re-install ours on top so it is
        // outermost and still writes the durable crash file even if ShipBook doesn't chain back,
        // and flush any startup telemetry (crash-loop signal) that predated ShipBook being up.
        //
        // Only meaningful once the SDK has actually replaced the handler — when telemetry is off
        // nothing displaces the handler installed in NotableApp.onCreate, so there is nothing to
        // reinstall over and no upload to flush.
        (app as? NotableApp)?.onShipBookStarted()
        return true
    }
}
