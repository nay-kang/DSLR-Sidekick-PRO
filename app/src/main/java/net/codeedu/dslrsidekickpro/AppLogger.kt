package net.codeedu.dslrsidekickpro

import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Thin wrapper around Sentry for manual log / breadcrumb / error reporting.
 *
 * Usage:
 *   AppLogger.d("CameraService", "USB device attached: $device")
 *   AppLogger.e("CameraService", "Connect failed", exception)
 *   AppLogger.capture("Camera model not recognised", mapOf("vendorId" to vendorId))
 */
object AppLogger {

    private const val MAX_TAG_LEN = 23 // Android Logcat limit

    // ── breadcrumbs (light structured log events) ─────────────────────────────

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        Sentry.addBreadcrumb(breadcrumb(SentryLevel.DEBUG, tag, msg))
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        Sentry.addBreadcrumb(breadcrumb(SentryLevel.INFO, tag, msg))
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        Sentry.addBreadcrumb(breadcrumb(SentryLevel.WARNING, tag, msg))
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        // errors get captured immediately so they always appear in Sentry
        Sentry.withScope { scope ->
            scope.setTag("component", tag)
            if (t != null) {
                scope.setExtra("message", msg)
                Sentry.captureException(t)
            } else {
                Sentry.captureMessage(msg, SentryLevel.ERROR)
            }
        }
    }

    // ── manual event capture with extra key/value pairs ───────────────────────

    /**
     * Capture a named event (non-exception) with optional extra data.
     * e.g. AppLogger.capture("usb_connect_fail", mapOf("vendorId" to "0x04B0"))
     */
    fun capture(eventName: String, extras: Map<String, Any?> = emptyMap()) {
        Log.w("AppLogger", "capture: $eventName $extras")
        Sentry.withScope { scope ->
            scope.setTag("event", eventName)
            extras.forEach { (k, v) -> scope.setExtra(k, v.toString()) }
            Sentry.captureMessage(eventName, SentryLevel.WARNING)
        }
    }

    /**
     * Set a user identifier so you can filter events by device/user in Sentry dashboard.
     * Call this once at startup (e.g. using Android ID).
     */
    fun setUserId(id: String) {
        val user = io.sentry.protocol.User().apply { this.id = id }
        Sentry.setUser(user)
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private fun breadcrumb(
        level: SentryLevel,
        tag: String,
        msg: String,
    ): io.sentry.Breadcrumb {
        return io.sentry.Breadcrumb().apply {
            this.level = level
            category = tag.take(MAX_TAG_LEN)
            message = msg
        }
    }
}
