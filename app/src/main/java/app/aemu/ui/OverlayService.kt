package app.aemu.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager

/**
 * The accessibility service that hosts the "Fullscreen" VM display.
 *
 * It owns nothing itself: VmActivity (the same process, :vm) builds the whole machine's screen —
 * the guest framebuffer view, the SurfaceView of the GPU bridge and the UI overlay — and hands the
 * view tree here. The service attaches it to a window of type TYPE_ACCESSIBILITY_OVERLAY, which an
 * accessibility service may add with no "draw over other apps" permission at all, and which covers
 * the phone's own screen, the status bar included. While the machine runs the overlay is on top of
 * everything; when the VM shuts down, VmActivity detaches the view and the window disappears with
 * it. The window is focusable so the guest also receives the phone's hardware keys.
 */
class OverlayService : AccessibilityService() {

    private var added: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        svc = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (svc === this) svc = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (svc === this) svc = null
        super.onDestroy()
    }

    // the service neither reads the screen nor reacts to events: it is only a window host
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * The hidden key gesture of a kiosk run. A phone has no way to hand KEYCODE_POWER to an app — the
     * system keeps it — so the two POWER strokes of the gesture are the two volume keys pressed
     * together instead. The accessibility key filter sees the volume keys before the guest does: the
     * pair that belongs to the sequence is swallowed (the machine must not read it as its own
     * "volume up + down twice → power" shortcut) and the menu opens without any visible control.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val gesture = onUnlock != null
        val machine = onKey
        if (!gesture && machine == null) {
            progress = 0; saved = 0; upHeld = false; downHeld = false; swallowed = 0
            return false
        }
        var consumed = false
        if (gesture && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                    val both = downHeld
                    upHeld = true
                    consumed = stroke(if (both) BOTH else UP)
                    if (consumed) swallowed = swallowed or 1
                } else {
                    val both = upHeld
                    downHeld = true
                    consumed = stroke(if (both) BOTH else DOWN)
                    if (consumed) swallowed = swallowed or 2
                }
            }
        } else if (gesture && event.action == KeyEvent.ACTION_UP) {
            val bit = if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else 2
            if (code == KeyEvent.KEYCODE_VOLUME_UP) upHeld = false else downHeld = false
            // the key's down never reached the guest, so its up must not either
            consumed = swallowed and bit != 0
            swallowed = swallowed and bit.inv()
        }
        if (machine != null) {
            // the kiosk overlay window has no activity key callback of its own: the key filter forwards the
            // volume keys to the machine (and takes them from the host's volume, which must never move)
            android.util.Log.i("LineageKeys", "filter ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} $code repeat=${event.repeatCount} consumed=$consumed")
            if (!consumed) runCatching { machine(event) }
            return true
        }
        return consumed
    }

    /** one stroke of the gesture: a single volume key or the two pressed together */
    private fun stroke(token: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt > STEP_MS) { progress = 0; saved = 0 }
        lastAt = now
        if (token == BOTH) progress = saved          // the first key of the pair already moved the sequence
        else saved = progress
        if (token == UNLOCK[progress]) {
            progress++
            if (progress == UNLOCK.size) {
                progress = 0
                saved = 0
                onUnlock?.invoke()
                return token == BOTH
            }
            // swallow the second key of a pair that is part of the sequence
            return token == BOTH
        }
        // a wrong stroke: a key that starts the sequence begins a new attempt, anything else clears it
        progress = if (token == UNLOCK[0]) 1 else 0
        return false
    }

    /** Attach [view] as the full-screen overlay window; false when the system refuses it. */
    fun attach(view: View, keepScreenOn: Boolean, modeId: Int = 0): Boolean {
        detach()                                   // one display window per machine
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // the screen must not fall asleep while the machine draws (the guest's own wishes are unknown)
        if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.OPAQUE)
        // the VM screen crosses the camera cutout by design in fullscreen; the IME never resizes or
        // pans the window itself — the log/terminal panels pad with imePadding from the window insets,
        // exactly how the activity's adjustResize is undone in the letterbox math
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        // many phones downshift legacy apps (targetSdk < 29) to half the panel refresh with their "dynamic
        // refresh" power saving — ask for the same mode the machine was built for (read before boot), so the
        // overlay and the guest stay at one rate; without a detected mode fall back to the display's best
        runCatching {
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            p.preferredDisplayModeId = if (modeId != 0) modeId
                else d.supportedModes.maxByOrNull { it.refreshRate }?.modeId ?: 0
        }
        return try { wm.addView(view, p); added = view; true } catch (_: Throwable) { false }
    }

    /**
     * Hide or show the overlay window **without detaching it**. Detaching destroys the SurfaceView the
     * machine presents into, and the guest's display does not survive losing its host surface: the
     * window is kept attached with zero alpha while the phone shows its own screen instead.
     */
    fun setHidden(view: View, hidden: Boolean, keepScreenOn: Boolean): Boolean {
        val p = view.layoutParams as? WindowManager.LayoutParams ?: return false
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val notTouch = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        var target = p
        if (hidden) {
            // the guest's frames are a SurfaceView, a layer of its own: alpha alone does not always take
            // it off the screen, so the whole window is moved past the left edge as well
            target.alpha = 0f
            target.x = -view.resources.displayMetrics.widthPixels
            target.flags = (target.flags or notTouch) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        } else {
            target.alpha = 1f
            target.x = 0
            target.flags = target.flags and notTouch.inv()
            if (keepScreenOn) target.flags = target.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        return runCatching { wm.updateViewLayout(view, p); true }.getOrDefault(false)
    }

    /** Remove [view] (or the one currently shown). */
    fun detach(view: View? = null) {
        val gone = view ?: added ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeViewImmediate(gone) }
        if (view == null || view === added) added = null
    }

    companion object {
        @Volatile private var svc: OverlayService? = null

        /** the two-part key sequence that opens the kiosk menu:
         *  VOL UP, DOWN, UP, DOWN, UP+DOWN — then DOWN, UP, DOWN, UP, UP+DOWN */
        private const val UP = KeyEvent.KEYCODE_VOLUME_UP
        private const val DOWN = KeyEvent.KEYCODE_VOLUME_DOWN
        /** the two volume keys pressed together — the stand-in for the POWER stroke a phone never gives us */
        private const val BOTH = -1
        private val UNLOCK = intArrayOf(UP, DOWN, UP, DOWN, BOTH, DOWN, UP, DOWN, UP, BOTH)
        /** two strokes further apart than this are not one gesture */
        private const val STEP_MS = 1600L

        /** set by VmActivity while a kiosk machine is on screen; null switches the gesture off */
        @Volatile var onUnlock: (() -> Unit)? = null

        /** set by VmActivity while the kiosk overlay window is up: delivers the phone's volume keys to the
         *  machine straight from the key filter (the overlay window has no activity key callback) */
        @Volatile var onKey: ((KeyEvent) -> Unit)? = null

        @Volatile private var progress = 0
        /** the sequence position before the last single key, restored when that key turns out to be one
         *  half of an UP+DOWN pair */
        @Volatile private var saved = 0
        @Volatile private var lastAt = 0L
        @Volatile private var upHeld = false
        @Volatile private var downHeld = false
        /** volume keys whose down the gesture swallowed (their up is swallowed too) */
        @Volatile private var swallowed = 0

        /** Whether the service is connected in this moment and can host a view. */
        fun connected(): Boolean = svc != null

        /**
         * Whether the service is enabled in the system accessibility settings at all; the connection
         * comes a moment later, so display setup asks [connected] too.
         */
        fun enabled(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            return runCatching {
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                    val si = it.resolveInfo?.serviceInfo
                    si != null && si.packageName == ctx.packageName &&
                        si.name == OverlayService::class.java.name
                }
            }.getOrDefault(false)
        }

        /** Attach a view from anywhere in this process (used by VmActivity). */
        fun show(view: View, keepScreenOn: Boolean, modeId: Int = 0): Boolean {
            val s = svc ?: return false
            return runCatching { s.attach(view, keepScreenOn, modeId) }.getOrDefault(false)
        }

        /** Take the view down; called when the VM stops so the overlay disappears with it. */
        fun hide(view: View? = null) {
            val s = svc ?: return
            runCatching { s.detach(view) }
        }

        /** Hide/show without detaching: the SurfaceView (and the guest's display) stays alive. */
        fun setHidden(view: View, hidden: Boolean, keepScreenOn: Boolean): Boolean {
            val s = svc ?: return false
            return runCatching { s.setHidden(view, hidden, keepScreenOn) }.getOrDefault(false)
        }
    }
}
