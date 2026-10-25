package app.aemu.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.aemu.R
import app.aemu.ui.VmActivity

/**
 * Служба переднего плана процесса :vm. Пока она жива, Android не выгружает процесс
 * с прошивкой при сворачивании и не режет ему процессор.
 */
class Keeper : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val title = i?.getStringExtra("title") ?: "Android"
        runCatching { startForeground(ID, note(this, title, i?.getStringExtra("id"))) }
            .onFailure { android.util.Log.w("LineageKeeper", "startForeground failed", it) }
        if (i?.action == STOP) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        // restarted by the system if the phone ever reclaims the process: the guest's binder daemons are
        // children of it, and losing them takes the whole machine down
        return START_STICKY
    }

    companion object {
        private const val CH = "vm"
        private const val ID = 1
        const val STOP = "com.enderbk.aemu.KEEPER_STOP"

        fun ensureChannel(ctx: Context) {
            runCatching {
                ctx.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(CH, ctx.getString(R.string.channel_vm), NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun note(ctx: Context, title: String, imageId: String? = null): Notification {
            ensureChannel(ctx)
            val openIntent = Intent(ctx, VmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(VmActivity.EXTRA_KIOSK, imageId != null)
            if (imageId != null) openIntent.putExtra(VmActivity.EXTRA_ID, imageId)
            val open = PendingIntent.getActivity(ctx, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CH)
                .setContentTitle(ctx.getString(R.string.vm_running, title))
                .setContentText(ctx.getString(R.string.vm_running_hint))
                .setSmallIcon(R.drawable.ic_stat_vm)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }

        fun hold(ctx: Context, title: String, imageId: String? = null) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, Keeper::class.java).putExtra("title", title).putExtra("id", imageId))
            }.onFailure { android.util.Log.w("LineageKeeper", "hold failed", it) }
        }

        fun release(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, Keeper::class.java)) }
        }
    }
}
