package app.aemu.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.roundToInt

/**
 * Refresh rate of the phone's own display, read once before the guest boots.
 *
 * The machine's emulated display follows the phone: the engine gets the rate through
 * DHD_FB_HZ / AEMU64_FB_HZ / AEMU64_HZ, and the activity/overlay windows ask for the same
 * display mode, so the panel and the guest do not end up at different rates. A mode the
 * engine does not support just keeps 60.
 */
object DisplayRate {
    /** [id] is the system display mode id (0 when unknown), [hz] the rounded refresh rate. */
    data class Mode(val id: Int, val hz: Int)

    fun current(ctx: Context): Mode {
        val d = runCatching {
            ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        val mode = runCatching { d?.mode }.getOrNull()
        val hz = (mode?.refreshRate?.takeIf { it > 0f }
            ?: runCatching { d?.refreshRate }.getOrNull()?.takeIf { it > 0f }
            ?: 60f).roundToInt().coerceIn(30, 240)
        return Mode(mode?.modeId ?: 0, hz)
    }
}
