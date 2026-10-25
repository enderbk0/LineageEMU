package app.aemu.lineage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import app.aemu.R
import app.aemu.core.ImageStore
import app.aemu.importer.Importer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The once-only download of the LineageOS GSI and its installation into the machine store.
 *
 * Runs as a foreground service: 779 MB over a phone connection outlives the setup screen, and the
 * archive on disk is resumable — a killed service continues with an HTTP Range request. The image is
 * installed by the very same [Importer] the library uses (xz, ext4/erofs, sparse, super), so a GSI
 * lands in the store exactly like a firmware the user could have added by hand.
 */
object SetupStatus {
    @Volatile var running = false
    @Volatile var done = false
    @Volatile var fraction = 0f
    @Volatile var text = ""
    @Volatile var error: String? = null

    /** the archive at rest (partial) is on disk: the next start continues instead of restarting */
    @Volatile var partial = false
}

class SetupService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIF, note(SetupStatus.text.ifEmpty { getString(R.string.ln_downloading) }, 0, 0)) }
        if (worker?.isAlive == true) return START_NOT_STICKY
        if (LineagePrefs.image(this) != null) {
            SetupStatus.running = false
            SetupStatus.done = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        SetupStatus.running = true
        SetupStatus.done = false
        SetupStatus.error = null
        worker = thread(name = "lineage-setup", isDaemon = false) { work() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (worker?.isAlive != true) SetupStatus.running = false
        super.onDestroy()
    }

    private fun work() {
        val lock = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LineageEMU:setup").apply { acquire(3600_000L) }
        }.getOrNull()
        try {
            val dir = File(filesDir, "rom").apply { mkdirs() }
            val xz = File(dir, "lineage-24.0.img.xz")
            if (!xz.isFile || xz.length() < TOTAL) {
                download(xz)
            } else {
                SetupStatus.partial = false
            }
            xz.takeIf { it.isFile } ?: throw IOException("archive missing")
            SetupStatus.text = getString(R.string.ln_installing)
            SetupStatus.fraction = -1f
            notify(getString(R.string.ln_installing), 0, 0)
            val importer = Importer(this, { text, f ->
                SetupStatus.text = text
                SetupStatus.fraction = if (f < 0f) -1f else f.coerceIn(0f, 1f)
            }, { line ->
                runCatching { File(filesDir, "setup.log").appendText(line + "\n") }
            })
            val img = importer.import(Uri.fromFile(xz), LineagePrefs.IMAGE_NAME)
            // the kiosk answers for the whole screen: no menus of its own, no letterbox, the panel's top mode
            ImageStore.update(this, img.id) {
                it.copy(settings = it.settings.copy(fullscreen = true, showNavBar = false, gpu = true, keepScreenOn = true))
            }
            runCatching { xz.delete() }
            runCatching { dir.delete() }
            SetupStatus.fraction = 1f
            SetupStatus.text = getString(R.string.ln_all_set)
            SetupStatus.done = true
            LineagePrefs.setSetupDone(this, true)
            notify(getString(R.string.ln_all_set), 1, 1)
        } catch (t: Throwable) {
            SetupStatus.error = t.message ?: t.toString()
        } finally {
            SetupStatus.running = false
            runCatching { lock?.release() }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    // ------------------------------------------------------------------ the archive

    private fun download(dst: File) {
        val part = File(dst.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have >= TOTAL) {
            // the whole archive is down but the last run stopped before the rename
            if (!part.renameTo(dst)) throw IOException("cannot rename the archive")
            SetupStatus.partial = false
            SetupStatus.fraction = 1f
            return
        }
        if (have > TOTAL) { part.delete(); have = 0L }
        SetupStatus.text = getString(R.string.ln_downloading)
        SetupStatus.partial = have > 0
        SetupStatus.fraction = have.toFloat() / TOTAL
        var conn = open(have)
        if (have > 0 && conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            // the server would not continue the file: start it over
            conn.disconnect()
            part.delete()
            have = 0L
            conn = open(0)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = have + (conn.contentLengthLong.takeIf { it > 0 } ?: (TOTAL - have))
            var lastNote = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = have
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val f = (done.toFloat() / total).coerceIn(0f, 1f)
                        SetupStatus.fraction = f
                        val pct = (f * 100).toInt()
                        SetupStatus.text = getString(R.string.ln_downloading_pct, pct)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNote > 700) {
                            lastNote = now
                            notify(SetupStatus.text, (f * 1000).toInt(), 1000)
                        }
                    }
                }
            }
            if (part.length() < total) throw IOException("incomplete: ${part.length()} of $total bytes")
            if (!part.renameTo(dst)) throw IOException("cannot rename the archive")
            SetupStatus.partial = false
        } finally {
            conn.disconnect()
        }
    }

    private fun open(from: Long): HttpURLConnection {
        val c = (URL(URL_TEXT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "LineageEMU")
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        c.connect()
        return c
    }

    // ------------------------------------------------------------------ the notification

    private fun notify(text: String, progress: Int, max: Int) {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF, note(text, progress, max))
        }
    }

    private fun note(text: String, progress: Int, max: Int): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH) == null) {
            runCatching {
                nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.ln_channel_setup), NotificationManager.IMPORTANCE_LOW))
            }
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, LineageActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_vm)
            .setContentTitle(getString(R.string.ln_download_title))
            .setContentText(text.ifEmpty { getString(R.string.ln_downloading) })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (max > 0) b.setProgress(max, progress.coerceIn(0, max), false)
        return b.build()
    }

    companion object {
        private const val CH = "lineage_setup"
        private const val NOTIF = 41

        /** the GSI: LineageOS 24.0 (Android 16), arm64_ab vanilla */
        private const val URL_TEXT =
            "https://github.com/Kyros70/Lineage_treble/releases/download/20261008-r2/" +
                "LineageOS-24.0-UNOFFICIAL-20261008-r2-GSI-arm64_ab-vanilla.img.xz"
        private const val TOTAL = 816_901_820L

        /** bytes of the archive already on disk (a stopped download resumes from here) */
        fun partial(ctx: android.content.Context): Long =
            File(ctx.filesDir, "rom/lineage-24.0.img.xz.part").let { if (it.isFile) it.length() else 0L }

        fun total(): Long = TOTAL

        fun start(ctx: android.content.Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, SetupService::class.java)) }
        }

        fun cancel(ctx: android.content.Context) {
            runCatching { ctx.stopService(Intent(ctx, SetupService::class.java)) }
        }
    }
}
