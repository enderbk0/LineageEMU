package app.aemu.core

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Зеркалирование яркости экрана телефона в гостя.
 *
 * Телефонная яркость читается из Settings.System (шкала 0…255; на новых прошивках ещё и
 * screen_brightness_float 0…1) и уходит в гостя теми же `settings put system ...`, которые
 * меняет его собственный ползунок. Наблюдатель ловит движение ползунка и авто-яркость,
 * таймер раз в 5 секунд добивает пропущенное. Повторно одно и то же значение не отправляется
 * (кроме редкой проверки раз в две минуты), чтобы не плодить процессы guest shell.
 *
 * В обратную сторону ничего не пишется: настройки самого телефона не меняются.
 */
class HostBrightness(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val shell: (String, Long) -> String = { _, _ -> "" },
) {
    @Volatile private var running = false
    /** Called with the phone's current 0…1 brightness, for the host-side dimmer over the guest screen. */
    @Volatile var onLevel: ((Float) -> Unit)? = null
    private var observer: ContentObserver? = null
    private var lastLevel = -1
    private var lastMode = -1
    private var lastNorm = -1f
    private var lastPushMs = 0L
    private var failLogged = false

    fun start() {
        if (running) return
        running = true
        val h = Handler(Looper.getMainLooper())
        observer = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) {
                if (!running) return
                Thread({
                    runCatching { syncOnce(true) }.onFailure { log("brightness mirror: $it") }
                }, "host-brightness").apply { isDaemon = true; start() }
            }
        }
        runCatching {
            val cr = ctx.contentResolver
            cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer!!)
            cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer!!)
            cr.registerContentObserver(Settings.System.getUriFor("screen_brightness_float"), false, observer!!)
        }.onFailure { log("brightness mirror: no observer: ${it.message}") }
        Thread({
            while (running) {
                runCatching { syncOnce(true) }.onFailure { log("brightness mirror: $it") }
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "host-brightness-poll").apply { isDaemon = true; start() }
        log("brightness mirror: phone brightness → guest")
    }

    fun stop() {
        running = false
        runCatching { observer?.let { ctx.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    /** One pass. [withGuest] false only refreshes the remembered value (used before the guest is up). */
    fun syncOnce(withGuest: Boolean) {
        val cr = ctx.contentResolver
        val level = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(-1)
        if (level < 0) return
        val mode = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrDefault(0)
        // some builds keep 0…65535 in SCREEN_BRIGHTNESS and the real 0…1 value in the float setting
        val float = runCatching { Settings.System.getFloat(cr, "screen_brightness_float") }.getOrNull()
        val norm = if (float != null && float >= 0f) float.coerceIn(0f, 1f)
            else (level / 255f).coerceIn(0f, 1f)
        val raw = (norm * 255f).roundToInt().coerceIn(0, 255)
        if (norm != lastNorm) {
            lastNorm = norm
            runCatching { onLevel?.invoke(norm) }
        }
        if (!withGuest) { lastLevel = raw; lastMode = mode; return }
        if (raw == lastLevel && mode == lastMode && System.currentTimeMillis() - lastPushMs < 120_000) return
        val f = String.format(Locale.US, "%.4f", norm)
        // both the setting and the display service: some builds keep showing the old level until
        // DisplayManager is told directly. Ignore the command on older guests (it is best effort).
        val cmd = "settings put system screen_brightness_mode 0; " +
            "settings put system screen_brightness $raw; " +
            "settings put system screen_brightness_float $f; " +
            "cmd display set-brightness $f >/dev/null 2>&1"
        val out = runCatching { shell(cmd, 30_000) }.getOrElse {
            if (!failLogged) { log("brightness mirror: guest shell not ready yet ($it)"); failLogged = true }
            return
        }
        failLogged = false
        lastLevel = raw; lastMode = mode; lastPushMs = System.currentTimeMillis()
        val bad = out.lineSequence().firstOrNull {
            it.contains("Error", true) || it.contains("Exception", true) || it.contains("denied", true) || it.contains("Unknown", true)
        }
        log("brightness mirror: phone $raw/255 → guest" + (bad?.let { " (guest said: ${it.trim().take(160)})" } ?: ""))
    }
}
