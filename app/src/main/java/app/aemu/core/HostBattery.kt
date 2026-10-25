package app.aemu.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/**
 * Зеркалирование батареи самого телефона в гостя. Источник — закреплённый broadcast
 * ACTION_BATTERY_CHANGED: пока приёмник зарегистрирован, система присылает свежие значения сразу,
 * как только они меняются (воткнули зарядку — в течение доли секунды), а таймер раз в 5 с
 * добивает то, что могло проскочить (на некоторых телефонах broadcast молчит, пока процент не
 * изменится на единицу, а зарядка/статус меняются и без этого).
 * Переписываем два места:
 *  1) хостовые записи в те же sys/class/power_supply/… файлы, что TreeFixer создаёт заранее
 *     (так их читает healthd старых гостевых версий);
 *  2) shell изнутри гостя, как ручной диалог батареи: те же echo + `dumpsys battery set
 *     level/status/ac/usb` — платформенный BatteryService на новых версиях читает sysfs только
 *     подсистемой health HAL, а dumpsys battery set переопределяет показ SystemUI и настроек
 *     напрямую.
 *
 * Внутрь гостя команда уходит только когда значения действительно изменились: спокойный телефон
 * не дёргает shell каждые 5 секунд. Значения, выставленные вручную из диалога
 * (GuestVm.batteryManual), не трогаем до перезагрузки гостя. В лог пишем только изменения/сбои.
 */
class HostBattery(private val ctx: Context, private val root: File, private val log: (String) -> Unit,
                  private val shell: (String, Long) -> String = { _, _ -> "" },
                  private val guestApi: Int = 21,
                  private val manual: () -> Boolean = { false }) {
    @Volatile private var running = false
    private var lastDumpsysFailLogged = false
    private var rx: BroadcastReceiver? = null
    // последнее, что сказали гостю: то же самое повторно не отправляем
    private var lastPct = -1
    private var lastStatus = -1
    private var lastPlugged = -1
    private var lastPushMs = 0L

    /** Последнее, что телефон сообщил о себе — диалог батареи берёт отсюда начальные значения. */
    companion object {
        @Volatile var hostLevel: Int? = null
            private set
        @Volatile var hostCharging: Boolean? = null
            private set
    }

    fun start() {
        if (running) return
        running = true
        // a registered receiver wakes us on every battery change; the sticky extra it carries makes the
        // framework hand one over at once, so plugging the cable is mirrored without waiting for a tick
        rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (running) runCatching { syncOnce(true) }.onFailure { log("battery mirror: $it") }
            }
        }
        runCatching { ctx.registerReceiver(rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
            .onFailure { rx = null; log("battery mirror: no broadcast, 5 s timer only: ${it.message}") }
        Thread({
            while (running) {
                runCatching { syncOnce(true) }.onFailure { log("battery mirror: $it") }
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "host-battery").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { rx?.let { ctx.unregisterReceiver(it) } }
        rx = null
    }

    /** One pass: the phone's values into the fake sysfs (and, with the guest up, its framework override too).
     *  The boot path calls it before the guest starts, so even a health HAL that reads sysfs only at startup
     *  sees the real percentage instead of the TreeFixer's 100 % / Full defaults. */
    fun syncOnce(withGuest: Boolean) {
        if (manual()) return // the values are set by hand from the dialog
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (level < 0) return
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = (level * 100 / scale).coerceIn(0, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        hostLevel = pct; hostCharging = charging
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val ac = if (plugged and BatteryManager.BATTERY_PLUGGED_AC != 0) 1 else 0
        val usb = if (plugged and (BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) 1 else 0

        // 1) fake sysfs (host tree is what the guest's healthd reads)
        val st = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Discharging"
        }
        write("battery/capacity", "$pct")
        write("battery/status", st)
        write("ac/online", "$ac")
        write("usb/online", "$usb")
        i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?.let { write("battery/batt_temp", "$it"); write("battery/temp", "$it") }
        i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) // мВ
            .takeIf { it > 0 }?.let { write("battery/batt_vol", "$it"); write("battery/voltage_now", "${it * 1000}") }

        // 2) inside the guest: the same path the manual battery dialog uses. dumpsys battery set
        //    overrides the platform BatteryService directly — on firmware where health HAL only
        //    reads sysfs at boot, this is what SystemUI and Settings really show.
        if (!withGuest) {
            if (lastPct < 0) log("battery mirror: the phone is at $pct% ${chargingString(status)}, guest files seeded")
            return
        }
        // the guest shell is the expensive part: a quiet phone is not poked again (and one re-send every
        // two minutes heals the rare "shell came up mid-command and swallowed the write" case)
        if (pct == lastPct && status == lastStatus && plugged == lastPlugged &&
            System.currentTimeMillis() - lastPushMs < 120_000) return
        val stn = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "2"
            BatteryManager.BATTERY_STATUS_FULL -> "5"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "4"
            else -> "3"
        }
        val cmd = StringBuilder().apply {
            append("echo $pct > /sys/class/power_supply/battery/capacity 2>/dev/null; ")
            append("echo $st > /sys/class/power_supply/battery/status 2>/dev/null; ")
            append("echo $ac > /sys/class/power_supply/ac/online 2>/dev/null; ")
            append("echo $usb > /sys/class/power_supply/usb/online 2>/dev/null")
            if (guestApi >= 19) append("; dumpsys battery set level $pct; dumpsys battery set status $stn; " +
                "dumpsys battery set ac $ac; dumpsys battery set usb $usb")
        }.toString()
        val out = runCatching { shell(cmd, 30_000) }.getOrElse {
            if (!lastDumpsysFailLogged) { log("battery mirror: guest shell not ready yet ($it)"); lastDumpsysFailLogged = true }
            return
        }
        lastDumpsysFailLogged = false
        lastPct = pct; lastStatus = status; lastPlugged = plugged; lastPushMs = System.currentTimeMillis()
        // the guest's shell tools answer an error as text, not as an exit code: leave a trace when they did
        val bad = out.lineSequence().firstOrNull {
            it.contains("Error", true) || it.contains("Exception", true) || it.contains("denied", true) || it.contains("Unknown", true)
        }
        log("battery mirror: phone ${pct}% ${chargingString(status)} → guest" + (bad?.let { " (guest said: ${it.trim().take(160)})" } ?: ""))
    }

    private fun chargingString(status: Int) = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "on battery"
    }

    private fun write(rel: String, v: String) {
        File(root, "sys/class/power_supply/$rel").writeText("$v\n")
    }
}
