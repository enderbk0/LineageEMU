package app.aemu.lineage

import android.content.Context
import app.aemu.core.GuestImage
import app.aemu.core.ImageStore

/**
 * LineageEMU's own little state: whether the first run is over, whether the start warning was
 * silenced, and the home screen's session switches. Nothing of the emulator itself lives here.
 */
object LineagePrefs {
    /** the one system this app installs */
    const val IMAGE_NAME = "LineageOS 24.0"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("lineage", Context.MODE_PRIVATE)

    fun setupDone(ctx: Context): Boolean = sp(ctx).getBoolean("setup_done", false)
    fun setSetupDone(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("setup_done", v).apply()

    /** the "Start LineageOS now? …" popup, hidden for good with its checkbox */
    fun hideStartWarning(ctx: Context): Boolean = sp(ctx).getBoolean("hide_start_warning", false)
    fun setHideStartWarning(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("hide_start_warning", v).apply()

    fun noCamera(ctx: Context): Boolean = sp(ctx).getBoolean("no_camera", false)
    fun noMic(ctx: Context): Boolean = sp(ctx).getBoolean("no_mic", false)
    fun noAudio(ctx: Context): Boolean = sp(ctx).getBoolean("no_audio", false)
    fun noNetwork(ctx: Context): Boolean = sp(ctx).getBoolean("no_network", false)
    fun usePhoneIdentity(ctx: Context): Boolean = sp(ctx).getBoolean("use_phone_identity", true)

    fun setNoCamera(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("no_camera", v).apply()
    fun setNoMic(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("no_mic", v).apply()
    fun setNoAudio(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("no_audio", v).apply()
    fun setNoNetwork(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("no_network", v).apply()
    fun setUsePhoneIdentity(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("use_phone_identity", v).apply()

    /** the installed LineageOS, if any */
    fun image(ctx: Context): GuestImage? = ImageStore.list(ctx).firstOrNull()
}
