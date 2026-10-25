package app.aemu.core

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * The real panel of the host phone. In the "Fullscreen" mode the guest screen is the whole panel
 * (the VM is drawn by an accessibility overlay above the system UI, status bar included), so the
 * resolution and density of the guest are inherited from the device instead of the stored settings.
 */
object HostScreen {
    /** The panel: physical resolution and density (dpi). */
    data class Panel(val w: Int, val h: Int, val dpi: Int)

    /** Panel of the phone; portrait values (the guest keeps the classic taller-screen shape). */
    private var cached: Panel? = null

    fun panel(ctx: Context): Panel {
        cached?.let { return it }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = DisplayMetrics()
        val ok = runCatching {
            @Suppress("DEPRECATION") // the panel's own pixels, system bars included; getRealMetrics exists on every API
            wm?.defaultDisplay?.getRealMetrics(dm)
            dm.widthPixels > 0 && dm.heightPixels > 0
        }.getOrDefault(false)
        if (!ok) {
            val m = ctx.resources.displayMetrics
            dm.widthPixels = m.widthPixels; dm.heightPixels = m.heightPixels; dm.densityDpi = m.densityDpi
        }
        // the guest framebuffer keeps the portrait shape: shorter side is its width
        var w = dm.widthPixels
        var h = dm.heightPixels
        if (w > h) { val t = w; w = h; h = t }
        val p = Panel(w.coerceIn(240, 4320), h.coerceIn(320, 7680), dm.densityDpi.coerceIn(96, 640))
        cached = p
        return p
    }

    /** Resolved guest screen for the stored settings: the panel in fullscreen, the settings values otherwise. */
    fun resolve(s: VmSettings, ctx: Context): Panel {
        if (!s.fullscreen) return Panel(s.width, s.height, s.density)
        val p = panel(ctx)
        // the density is taken from the device, but the field stays changeable: a value different from
        // the engine default (240) means the user picked one explicitly — that is honoured
        val dpi = if (s.density == 240) p.dpi else s.density
        return Panel(p.w, p.h, dpi)
    }
}
