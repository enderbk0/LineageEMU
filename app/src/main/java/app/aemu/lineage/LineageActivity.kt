package app.aemu.lineage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.ImageStore
import app.aemu.ui.AemuTheme
import app.aemu.ui.OverlayService
import app.aemu.ui.VmActivity
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/**
 * LineageEMU's whole host UI: the first-run walk (permissions → download → install → all set) and
 * the home screen of the installed system. The machine itself lives in [VmActivity]; this activity
 * only prepares it and then gets out of the way.
 */
class LineageActivity : ComponentActivity() {

    private enum class Scr { PERMS, READY, RUN, DONE, HOME }

    private data class Perms(
        val accessibility: Boolean = false,
        val mic: Boolean = false,
        val camera: Boolean = false,
        val notifications: Boolean = false,
        val battery: Boolean = false,
    ) {
        /** accessibility is the one hard requirement: it hosts the fullscreen window */
        val ready get() = accessibility
    }

    private data class ToggleSpec(
        val title: String,
        val sub: String,
        val checked: Boolean,
        val onChange: (Boolean) -> Unit,
    )

    private var screen by mutableStateOf(Scr.PERMS)
    private var perms by mutableStateOf(Perms())
    private var setupText by mutableStateOf("")
    private var setupFraction by mutableStateOf(0f)
    private var setupError by mutableStateOf<String?>(null)
    private var showStart by mutableStateOf(false)
    private var showUninstall by mutableStateOf(false)
    private var showReinstall by mutableStateOf(false)
    private var showReset by mutableStateOf(false)
    private var showAccessibility by mutableStateOf(false)
    private var warnRunning by mutableStateOf(false)
    private var deleting by mutableStateOf(false)
    private var resetting by mutableStateOf(false)

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        screen = when {
            LineagePrefs.image(this) != null -> Scr.HOME
            SetupStatus.running -> Scr.RUN
            LineagePrefs.setupDone(this) -> Scr.READY
            else -> Scr.PERMS
        }
        refresh()
        setContent { AemuTheme(forceDark = true) { Root() } }
    }

    override fun onResume() {
        super.onResume()
        // accessibility is granted in the system settings, in another window: the state comes back here
        refresh()
    }

    private fun refresh() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        perms = Perms(
            accessibility = OverlayService.enabled(this),
            mic = granted(Manifest.permission.RECORD_AUDIO),
            camera = granted(Manifest.permission.CAMERA),
            notifications = Build.VERSION.SDK_INT < 33 || granted("android.permission.POST_NOTIFICATIONS"),
            battery = pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun openAccessibility() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun askBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun startVm() {
        // the overlay service is what puts the machine over the whole screen: never boot without it
        if (!OverlayService.enabled(this)) {
            refresh()
            showStart = false
            showAccessibility = true
            return
        }
        val img = LineagePrefs.image(this) ?: return
        VmActivity.startKiosk(this, img.id,
            LineagePrefs.noCamera(this), LineagePrefs.noMic(this),
            LineagePrefs.noAudio(this), LineagePrefs.noNetwork(this),
            LineagePrefs.usePhoneIdentity(this))
    }

    private fun askStart() {
        refresh()
        if (!perms.accessibility) { showAccessibility = true; return }
        if (LineagePrefs.hideStartWarning(this)) startVm() else showStart = true
    }

    /** the machine of this image is alive in the :vm process: its data must not be pulled from under it */
    private fun busy(): Boolean = VmActivity.runningImage(this) != null && VmActivity.vmProcessAlive(this)

    private fun uninstall() {
        if (busy()) { warnRunning = true; return }
        showUninstall = true
    }

    private fun doUninstall() {
        val img = LineagePrefs.image(this) ?: return
        if (busy()) { warnRunning = true; return }
        showUninstall = false
        deleting = true
        thread(name = "lineage-delete") {
            runCatching { ImageStore.delete(this, img.id) }
            runOnUiThread {
                deleting = false
                refresh()
                screen = if (LineagePrefs.image(this) != null) Scr.HOME else Scr.READY
            }
        }
    }

    private fun doReinstall() {
        val img = LineagePrefs.image(this) ?: return
        if (busy()) { warnRunning = true; return }
        showReinstall = false
        deleting = true
        thread(name = "lineage-delete") {
            runCatching { ImageStore.delete(this, img.id) }
            runOnUiThread {
                deleting = false
                if (LineagePrefs.image(this) != null) {
                    // the wipe did not go through: keep the system as it is
                    screen = Scr.HOME
                    return@runOnUiThread
                }
                SetupStatus.error = null
                SetupStatus.done = false
                SetupService.start(this)
                screen = Scr.RUN
            }
        }
    }

    private fun doReset() {
        val img = LineagePrefs.image(this) ?: return
        if (busy()) { warnRunning = true; return }
        showReset = false
        resetting = true
        thread(name = "lineage-reset") {
            val error = runCatching { ImageStore.resetData(this, img.id) }.exceptionOrNull()
            runOnUiThread {
                resetting = false
                refresh()
                screen = Scr.HOME
                val text = if (error == null) getString(R.string.ln_reset_done)
                    else getString(R.string.ln_reset_failed, error.message ?: error.javaClass.simpleName)
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ Compose

    @Composable
    private fun Root() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Scr.PERMS -> PermsScreen()
                    Scr.READY -> ReadyScreen()
                    Scr.RUN -> RunScreen()
                    Scr.DONE -> DoneScreen()
                    Scr.HOME -> HomeScreen()
                }
                if (showStart) StartDialog()
                if (showUninstall) UninstallDialog()
                if (showReinstall) ReinstallDialog()
                if (showReset) ResetDialog()
                if (showAccessibility) AccessibilityDialog()
                if (deleting || resetting) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(if (resetting) R.string.ln_resetting else R.string.ln_deleting),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (warnRunning) AlertDialog(
                    onDismissRequest = { warnRunning = false },
                    title = { Text(stringResource(R.string.ln_uninstall)) },
                    text = { Text(stringResource(R.string.ln_running_note)) },
                    confirmButton = { TextButton(onClick = { warnRunning = false }) { Text(stringResource(R.string.ln_ok)) } },
                )
            }
        }
        // the setup service lives outside this activity: follow its status into composition state
        LaunchedEffect(Unit) {
            while (true) {
                setupText = SetupStatus.text
                setupFraction = SetupStatus.fraction
                setupError = SetupStatus.error
                if (screen == Scr.RUN && SetupStatus.done) {
                    // one disk read, only when the service says the image is there
                    if (LineagePrefs.image(this@LineageActivity) != null) screen = Scr.DONE
                } else if (screen == Scr.READY && SetupStatus.running) {
                    screen = Scr.RUN
                }
                delay(300)
            }
        }
    }

    @Composable
    private fun Page(title: String, subtitle: String?, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun PermsScreen() {
        Page(stringResource(R.string.ln_welcome_title), stringResource(R.string.ln_welcome_sub)) {
            Text(stringResource(R.string.ln_perm_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.ln_perm_sub), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column {
                    PermRow(Icons.Rounded.Accessibility, stringResource(R.string.ln_acc), stringResource(R.string.ln_acc_sub),
                        perms.accessibility, stringResource(R.string.ln_grant)) { openAccessibility() }
                    HorizontalDivider()
                    PermRow(Icons.Rounded.Mic, stringResource(R.string.ln_mic), stringResource(R.string.ln_mic_sub),
                        perms.mic, stringResource(R.string.ln_grant)) { askMic.launch(Manifest.permission.RECORD_AUDIO) }
                    HorizontalDivider()
                    PermRow(Icons.Rounded.CameraAlt, stringResource(R.string.ln_cam), stringResource(R.string.ln_cam_sub),
                        perms.camera, stringResource(R.string.ln_grant)) { askCamera.launch(Manifest.permission.CAMERA) }
                    HorizontalDivider()
                    PermRow(Icons.Rounded.Notifications, stringResource(R.string.ln_notif), stringResource(R.string.ln_notif_sub),
                        perms.notifications, stringResource(R.string.ln_grant)) {
                        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch("android.permission.POST_NOTIFICATIONS")
                    }
                    HorizontalDivider()
                    PermRow(Icons.Rounded.BatterySaver, stringResource(R.string.ln_batt), stringResource(R.string.ln_batt_sub),
                        perms.battery, stringResource(R.string.ln_grant)) { askBattery() }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { LineagePrefs.setSetupDone(this@LineageActivity, true); screen = Scr.READY }, enabled = perms.ready,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.ln_continue), style = MaterialTheme.typography.titleMedium)
            }
            if (!perms.mic || !perms.camera) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ln_perm_skip), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun PermRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String,
                        ok: Boolean, action: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !ok) { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (ok) Text(stringResource(R.string.ln_granted), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = onClick) { Text(action) }
        }
    }

    @Composable
    private fun ReadyScreen() {
        val have = SetupService.partial(this@LineageActivity)
        val free = runCatching { android.os.StatFs(filesDir.path).availableBytes }.getOrDefault(0L)
        Page(stringResource(R.string.ln_download_title), null) {
            Text(stringResource(R.string.ln_download_sub), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.ln_storage_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (have > 0) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ln_resume_at, (have * 100 / SetupService.total()).toInt()),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (free > 0 && free < 5L * 1024 * 1024 * 1024) {
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.ln_free_space, gb(free)), Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
            if (setupError != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ln_error), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(setupError ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Button(onClick = { SetupStatus.error = null; SetupService.start(this@LineageActivity); screen = Scr.RUN },
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (have > 0) R.string.ln_resume else R.string.ln_download),
                    style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    private fun gb(bytes: Long): String = String.format("%.1f GB", bytes / 1073741824.0)

    @Composable
    private fun RunScreen() {
        val running = SetupStatus.running
        Page(stringResource(R.string.ln_download_title), null) {
            if (setupError != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ln_error), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(setupError ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { SetupStatus.error = null; SetupService.start(this@LineageActivity) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ln_retry), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text(setupText.ifEmpty { stringResource(R.string.ln_downloading) },
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                if (setupFraction < 0f) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { setupFraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(setupFraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ln_installing_sub), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun DoneScreen() {
        Page(stringResource(R.string.ln_all_set), stringResource(R.string.ln_all_set_sub)) {
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(88.dp))
            Spacer(Modifier.height(24.dp))
            Button(onClick = { LineagePrefs.setSetupDone(this@LineageActivity, true); screen = Scr.HOME },
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.ln_ok), style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    private fun androidName(api: Int): String = when (api) {
        37 -> "17"
        36 -> "16"
        35 -> "15"
        34 -> "14"
        33 -> "13"
        32 -> "12L"
        31 -> "12"
        30 -> "11"
        29 -> "10"
        28 -> "9"
        27 -> "8.1"
        26 -> "8.0"
        25 -> "7.1"
        24 -> "7.0"
        23 -> "6.0"
        22 -> "5.1"
        21 -> "5.0"
        else -> api.toString()
    }

    @Composable
    private fun HomeScreen() {
        val ctx = this@LineageActivity
        val img = LineagePrefs.image(ctx)
        var noCamera by remember { mutableStateOf(LineagePrefs.noCamera(ctx)) }
        var noMic by remember { mutableStateOf(LineagePrefs.noMic(ctx)) }
        var noAudio by remember { mutableStateOf(LineagePrefs.noAudio(ctx)) }
        var noNetwork by remember { mutableStateOf(LineagePrefs.noNetwork(ctx)) }
        var useIdentity by remember { mutableStateOf(LineagePrefs.usePhoneIdentity(ctx)) }
        val toggles = listOf(
            ToggleSpec(stringResource(R.string.ln_dis_camera), stringResource(R.string.ln_dis_camera_sub),
                noCamera) { noCamera = it; LineagePrefs.setNoCamera(ctx, it) },
            ToggleSpec(stringResource(R.string.ln_dis_mic), stringResource(R.string.ln_dis_mic_sub),
                noMic) { noMic = it; LineagePrefs.setNoMic(ctx, it) },
            ToggleSpec(stringResource(R.string.ln_dis_audio), stringResource(R.string.ln_dis_audio_sub),
                noAudio) { noAudio = it; LineagePrefs.setNoAudio(ctx, it) },
            ToggleSpec(stringResource(R.string.ln_dis_net), stringResource(R.string.ln_dis_net_sub),
                noNetwork) { noNetwork = it; LineagePrefs.setNoNetwork(ctx, it) },
            ToggleSpec(stringResource(R.string.ln_identity), stringResource(R.string.ln_identity_sub),
                useIdentity) { useIdentity = it; LineagePrefs.setUsePhoneIdentity(ctx, it) },
        )
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        ) {
            item {
                Text(stringResource(R.string.ln_download_title), style = MaterialTheme.typography.headlineMedium)
                img?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ln_installed_api, androidName(it.api), it.api),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { askStart() }, modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = MaterialTheme.shapes.large) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ln_start), style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ln_start_sub), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!perms.accessibility) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(stringResource(R.string.ln_acc_required), Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.ln_session), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(toggles) { index, toggle ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ToggleRow(toggle.title, toggle.sub, toggle.checked, toggle.onChange)
                }
                if (index != toggles.lastIndex) HorizontalDivider()
            }
            item {
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.ln_manage), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showReinstall = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ln_reinstall))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showReset = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.RestartAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ln_reset_data))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { uninstall() }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ln_uninstall))
                }
            }
        }
    }

    @Composable
    private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun StartDialog() {
        var hide by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showStart = false },
            title = { Text(stringResource(R.string.ln_start)) },
            text = {
                Column {
                    Text(stringResource(R.string.ln_confirm_start))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.clickable { hide = !hide }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = hide, onCheckedChange = { hide = it })
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ln_do_not_show), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (hide) LineagePrefs.setHideStartWarning(this@LineageActivity, true)
                    showStart = false
                    startVm()
                }) { Text(stringResource(R.string.ln_start)) }
            },
            dismissButton = {
                TextButton(onClick = { showStart = false }) { Text(stringResource(R.string.ln_cancel)) }
            },
        )
    }

    @Composable
    private fun UninstallDialog() {
        AlertDialog(
            onDismissRequest = { showUninstall = false },
            title = { Text(stringResource(R.string.ln_uninstall_title)) },
            text = { Text(stringResource(R.string.ln_uninstall_warn)) },
            confirmButton = {
                TextButton(onClick = { doUninstall() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.ln_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstall = false }) { Text(stringResource(R.string.ln_cancel)) }
            },
        )
    }

    @Composable
    private fun ReinstallDialog() {
        AlertDialog(
            onDismissRequest = { showReinstall = false },
            title = { Text(stringResource(R.string.ln_reinstall_title)) },
            text = { Text(stringResource(R.string.ln_reinstall_warn)) },
            confirmButton = {
                TextButton(onClick = { doReinstall() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.ln_reinstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReinstall = false }) { Text(stringResource(R.string.ln_cancel)) }
            },
        )
    }

    @Composable
    private fun ResetDialog() {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.ln_reset_title)) },
            text = { Text(stringResource(R.string.ln_reset_warn)) },
            confirmButton = {
                TextButton(onClick = { doReset() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.ln_reset_data))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text(stringResource(R.string.ln_cancel)) }
            },
        )
    }

    @Composable
    private fun AccessibilityDialog() {
        AlertDialog(
            onDismissRequest = { showAccessibility = false },
            title = { Text(stringResource(R.string.ln_acc_needed_title)) },
            text = { Text(stringResource(R.string.ln_acc_needed)) },
            confirmButton = {
                TextButton(onClick = { showAccessibility = false; openAccessibility() }) {
                    Text(stringResource(R.string.ln_enable_accessibility))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibility = false }) { Text(stringResource(R.string.ln_cancel)) }
            },
        )
    }
}
