package app.aemu.ui

import app.aemu.R
import java.io.File
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aemu.core.Engine
import app.aemu.lineage.LineagePrefs
import app.aemu.core.GuestLog
import app.aemu.core.GuestVm
import app.aemu.core.HostBattery
import app.aemu.core.ImageStore
import app.aemu.core.InputService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** Процесс :vm держит ровно одну машину. */
object VmHost {
    @Volatile var vm: GuestVm? = null
}

class VmActivity : ComponentActivity() {
    private lateinit var vm: GuestVm
    private lateinit var surfaceView: SurfaceView
    private lateinit var guest: GuestScreen
    /** the guest's screen size, for what takes frames off it (VNC, recording) */
    private var guestW = 0
    private var guestH = 0
    private var vnc: app.aemu.core.VncServer? = null
    private var recorder: app.aemu.core.ScreenRecorder? = null
    private val vncOn = androidx.compose.runtime.mutableStateOf(false)
    private val recOn = androidx.compose.runtime.mutableStateOf(false)
    private val grabThread by lazy { android.os.HandlerThread("frame-grab").apply { start() } }
    private var grabScratch: android.graphics.Bitmap? = null
    private lateinit var box: FrameLayout
    /** black scrim over the guest: its brightness follows the phone's panel */
    private lateinit var dim: View
    /** wrapper that can rotate the guest's portrait framebuffer on a landscape phone */
    private lateinit var holder: FrameLayout
    private var landscape = false
    private var guestRotation = 0
    private var lastOrientationAt = 0L
    private var orientationListener: android.view.OrientationEventListener? = null
    /** the window content of the VM screen: moved into the accessibility overlay in fullscreen */
    private var root: FrameLayout? = null
    /** the machine's display is currently hosted by the overlay service (fullscreen mode) */
    private var overlayShown = false
    /** the user chose the plain activity window for this run (fullscreen without accessibility) */
    private var overlayDeclined = false
    /** LineageEMU's kiosk run: only the machine on screen; the key gesture opens the small menu */
    /** kiosk when asked, or when this is the notification bringing a kiosk machine back on screen */
    private val kiosk by lazy {
        intent.getBooleanExtra(EXTRA_KIOSK, false) ||
            (VmHost.vm != null && File(filesDir, RUNNING_FLAG).isFile)
    }
    // the home screen's toggles are the source of truth once the run is restored from its notification
    private val kioskNoCamera by lazy { intent.getBooleanExtra(EXTRA_NO_CAMERA, false) || LineagePrefs.noCamera(this) }
    private val kioskNoMic by lazy { intent.getBooleanExtra(EXTRA_NO_MIC, false) || LineagePrefs.noMic(this) }
    private val kioskNoAudio by lazy { intent.getBooleanExtra(EXTRA_NO_AUDIO, false) || LineagePrefs.noAudio(this) }
    private val kioskNoNetwork by lazy { intent.getBooleanExtra(EXTRA_NO_NETWORK, false) || LineagePrefs.noNetwork(this) }
    private val kioskUsePhoneIdentity by lazy {
        intent.getBooleanExtra(EXTRA_USE_PHONE_IDENTITY, false) || LineagePrefs.usePhoneIdentity(this)
    }
    /** the little kiosk menu opened by the hidden key gesture (drawn in-tree, like the dialogs) */
    private var kioskMenu by mutableStateOf(false)
    /** the guest's display is asleep: the host keeps the last frame otherwise, so cover it in black */
    private var guestAsleep by mutableStateOf(false)
    @Volatile private var awakeGen = 0
    /** the user pressed "Minimize": the overlay is down for now, the machine keeps running */
    private var kioskMinimized = false
    /** last heartbeat written for a live machine (the home screen checks it before deleting the image) */
    private var flagAt = 0L
    /** the volume keys currently held, the pair presses of the guest's power shortcut, the keys whose
     *  down the shortcut swallowed (their up must not reach the guest either), and the key that is
     *  holding the guest's power button down */
    private var volHeld = 0
    private var volSwallowed = 0
    private var volPairs = 0
    private var volPairAt = 0L
    private var powerCode = 0
    private var safeTopPx = 0
    private var safeBottomPx = 0
    private var state by mutableStateOf(GuestVm.State.STOPPED)
    /** the guest's notification LED and where the guest screen starts (the LED sits just above it) */
    private var led by mutableStateOf<app.aemu.core.Lights.Led?>(null)
    /** this system's setting "Notification light" (the settings sheet changes it while the system runs) */
    private var showLed by mutableStateOf(true)
    private var guestTopPx by mutableStateOf(0)
    /** the boot card hidden with the X while the machine keeps booting: the small "booting…" pill
     *  stays, tapping it brings the card back; a new boot attempt or a failure shows the card again */
    private var bootCardHidden by mutableStateOf(false)
    private val logLines = mutableStateListOf<String>()

    @SuppressLint("ClickableViewAccessibility")
    override fun attachBaseContext(base: Context) = super.attachBaseContext(app.aemu.AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the guest is shown in the phone's physical orientation; the framebuffer itself stays portrait
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        // the overlay pads itself (status bar, navigation bar, keyboard): the terminal stays above the keyboard
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val id = intent.getStringExtra(EXTRA_ID)
        val cur = VmHost.vm
        val img = ((if (id != null) ImageStore.get(this, id) else null) ?: cur?.img)
            ?.let { if (cur?.img?.id == it.id) it else app.aemu.importer.Analyzer.refresh(this, it).effective() }
        if (img == null) { finish(); return }
        if (cur != null && cur.img.id != img.id && cur.state != GuestVm.State.STOPPED) {
            // в процессе уже живёт другая машина — её надо сначала остановить
            cur.stop()
            restartProcess(img.id)
            return
        }
        vm = if (cur != null && cur.img.id == img.id) cur
            else GuestVm(applicationContext, img).also { it.recoveryMode = intent.getBooleanExtra(EXTRA_RECOVERY, false); VmHost.vm = it }
        vm.onPower = { reboot, reason -> runOnUiThread { if (reboot) rebootVm(reason == "recovery") else stopVm() } }
        if (kiosk) {
            // the toggles of the home screen switch parts of the phone off for the whole run
            vm.noCamera = kioskNoCamera
            vm.noMic = kioskNoMic
            vm.noAudio = kioskNoAudio
            vm.noNetwork = kioskNoNetwork
            vm.usePhoneIdentity = kioskUsePhoneIdentity
            OverlayService.onUnlock = { runOnUiThread { if (kiosk) kioskMenu = true } }
            runCatching { File(filesDir, RUNNING_FLAG).writeText(img.id) }
        }
        val s = vm.settings
        showLed = s.showLed
        if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // the machine runs at the panel's own rate (read before the guest started): ask the fallback
        // window for that same display mode instead of letting it sit at 60 while the guest runs faster
        if (vm.displayRate.id != 0) runCatching {
            val a = window.attributes
            a.preferredDisplayModeId = vm.displayRate.id
            window.attributes = a
        }
        val (gw, gh) = if (vm.engine == Engine.GB) 480 to 800 else vm.screen.w to vm.screen.h
        root = FrameLayout(this).apply { setBackgroundColor(0xff000000.toInt()) }
        val r = root!!
        // the guest framebuffer stays portrait; holder can turn it when the phone is landscape
        holder = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        box = FrameLayout(this)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { vm.surface = h.surface }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { vm.surface = h.surface }
            // only the surface the machine is drawing to: a stale view (the overlay moving back into the
            // activity, a notification opening the same machine) must not blank a newer one
            override fun surfaceDestroyed(h: SurfaceHolder) { if (vm.surface === h.surface) vm.surface = null }
        })
        guestW = gw; guestH = gh
        guest = GuestScreen(this, gw, gh).apply {
            input = vm.input
            fb = if (vm.recoveryMode) app.aemu.core.RecoveryImage.fb(vm.paths) else vm.paths.fb
            if (vm.recoveryMode) pages = 2
        }
        // 5.0+ renders through the standalone glserverd into fb0 (see GuestVm.glUp), shown like the software path
        val useBridge = vm.engine == Engine.KK && s.gpu && !vm.recoveryMode && vm.img.api < 21
        // the arm64 engine's GLES bridge shows SurfaceFlinger's frames in the same view, straight from the GPU; until
        // the first of them (and with the software renderer) the screen is fb0 as before
        val direct = vm.engine == Engine.N64 && vm.kernel.gpuBridge64 && s.gpu && !vm.recoveryMode
        if (direct) { surfaceView.holder.setFixedSize(gw, gh); guest.direct = { app.aemu.core.GlHost.presented() } }
        surfaceView.visibility = if (useBridge || direct) View.VISIBLE else View.GONE
        guest.passthrough = useBridge
        box.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        box.addView(guest, FrameLayout.LayoutParams(-1, -1))
        dim = View(this).apply {
            setBackgroundColor(0xff000000.toInt())
            alpha = 0f
            isClickable = false
            isFocusable = false
        }
        box.addView(dim, FrameLayout.LayoutParams(-1, -1))
        holder.addView(box, FrameLayout.LayoutParams(-1, -1))
        r.addView(holder, FrameLayout.LayoutParams(0, 0))
        val overlay = ComposeView(this).apply { setContent { AemuTheme(forceDark = true) { Overlay() } } }
        r.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // Fullscreen: the machine's display is an accessibility overlay window above everything, status
        // bar included (OverlayService hosts it, the view tree is built here in :vm just like before);
        // VmActivity's own window keeps only a black placeholder. Without the connected overlay service
        // — or after the user takes the fallback — the plain activity window is used as before.
        if (vm.settings.fullscreen && !overlayDeclined && applyOverlay()) {
            setContentView(View(this))
        } else {
            setContentView(r)
        }

        // экран гостя вписываем с сохранением пропорций над панелью кнопок
        // (in fullscreen the emulated bar stays ON per the user's navbar setting: it overlays the guest's own bar area,
        // its height is again reserved in the letterbox)
        val navDp = if (!s.showNavBar) 0 else if (s.navSize == 0) 46 else 64
        // the window draws edge to edge (for the overlay's keyboard padding): the guest screen keeps out of the
        // camera cutout and system bars; the keyboard is not counted, so the screen does not jump when it opens
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(r) { v, ins ->
            if (!overlayShown) {
                val bars = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                if (bars.top != safeTopPx || bars.bottom != safeBottomPx) {
                    safeTopPx = bars.top; safeBottomPx = bars.bottom; v.requestLayout()
                }
            }
            ins
        }
        r.addOnLayoutChangeListener { v, l, t, right, b, _, _, _, _ ->
            if (overlayShown) updateGestureExclusion(v)
            val top = if (overlayShown) 0 else safeTopPx
            val bottom = if (overlayShown) 0 else safeBottomPx
            val navPx = (navDp * resources.displayMetrics.density).toInt()
            val aw = right - l
            val ah = b - t - navPx - top - bottom
            if (aw <= 0 || ah <= 0) return@addOnLayoutChangeListener
            val scale = if (landscape) minOf(aw.toFloat() / gh, ah.toFloat() / gw)
                else minOf(aw.toFloat() / gw, ah.toFloat() / gh)
            val w = (gw * scale).toInt()
            val h = (gh * scale).toInt()
            val targetW = if (landscape) h else w
            val targetH = if (landscape) w else h
            val topMargin = top + (ah - targetH) / 2
            if (guestTopPx != topMargin) guestTopPx = topMargin
            val hlp = holder.layoutParams as FrameLayout.LayoutParams
            if (hlp.width != targetW || hlp.height != targetH || hlp.topMargin != topMargin) {
                hlp.width = targetW; hlp.height = targetH
                hlp.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                hlp.topMargin = topMargin
                v.post { holder.layoutParams = hlp }
            }
            val lp = box.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
                v.post { box.layoutParams = lp }
            }
            box.rotation = -guestRotation * 90f
        }

        vm.onFrame = { guest.rings++; guest.poke() }
        vm.setBrightnessListener { level -> runOnUiThread {
            // the guest's backlight is virtual: dim the host's frame with the phone's real level
            dim.alpha = ((1f - level) * 0.85f).coerceIn(0f, 0.85f)
        } }
        state = vm.state
        logLines.addAll(vm.lines().takeLast(200))
        vm.onState = { st -> runOnUiThread {
            state = st
            if (st == GuestVm.State.RUNNING) { refreshGuestAwake(); applyGuestRotation() } else guestAsleep = false
        } }
        vm.mic.onPermissionNeeded = { runOnUiThread { askMic.launch(android.Manifest.permission.RECORD_AUDIO) } }
        vm.camera.onPermissionNeeded = { runOnUiThread { askCamera.launch(android.Manifest.permission.CAMERA) } }
        led = vm.lights.current
        vm.lights.onChange = { l -> runOnUiThread { led = l } }
        vm.onLog = { line -> runOnUiThread { logLines.add(line); if (logLines.size > 400) logLines.removeRange(0, logLines.size - 400) } }
        guest.start()
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rot = when {
                    orientation in 60..120 -> 1
                    orientation in 150..210 -> 2
                    orientation in 240..300 -> 3
                    else -> 0
                }
                if (rot == guestRotation) return
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastOrientationAt < 350) return
                lastOrientationAt = now
                guestRotation = rot
                landscape = rot == 1 || rot == 3
                root?.requestLayout()
                applyGuestRotation()
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
        loadMenuOffset()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { vm.input.press(InputService.KEY_BACK) }
        })

        registerShell()
        if (vm.state == GuestVm.State.STOPPED || vm.state == GuestVm.State.FAILED) {
            thread(name = "vm-boot") { vm.boot() }
        }
    }

    /** Отладка: adb shell am broadcast -a com.enderbk.aemu.SHELL --es cmd "dumpsys power" → run/shell.out */
    private var shellRx: android.content.BroadcastReceiver? = null
    private fun registerShell() {
        if (!app.aemu.BuildConfig.DEBUG) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // --es keys "116d 115 116u": raw guest key codes, d/u = down/up only, bare = press
                i.getStringExtra("keys")?.let { ks ->
                    thread {
                        for (k in ks.split(' ').filter { it.isNotBlank() }) {
                            val code = k.trimEnd('d', 'u').toIntOrNull() ?: continue
                            when { k.endsWith("d") -> vm.input.key(code, true); k.endsWith("u") -> vm.input.key(code, false)
                                else -> { vm.input.key(code, true); Thread.sleep(80); vm.input.key(code, false) } }
                            Thread.sleep(150)
                        }
                    }
                    return
                }
                i.getStringExtra("sms")?.let { sendSms("+15550001234", it); return }
                if (i.getBooleanExtra("minimize", false)) { runOnUiThread { minimizeKiosk() }; return }
                if (i.getBooleanExtra("shot", false)) { runOnUiThread { screenshot() }; return }
                // --ez frames true --es out <name>: the count of frames shown and the clock, for the testers
                if (i.getBooleanExtra("frames", false)) {
                    val name = i.getStringExtra("out")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: "frames.out"
                    java.io.File(vm.paths.bin, name).writeText("shown=${guest.shown} direct=${guest.passthrough} ms=${android.os.SystemClock.uptimeMillis()}\n")
                    return
                }
                if (i.hasExtra("adb")) { if (i.getBooleanExtra("adb", false)) vm.adb.start() else vm.adb.stop(); return }
                val cmd = i.getStringExtra("cmd") ?: return
                // своё имя файла на каждую команду: медленная предыдущая команда не затрёт ответ
                val name = i.getStringExtra("out")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: "shell.out"
                thread {
                    val out = runCatching { vm.guestShell(cmd, 120_000) }.getOrElse { it.toString() }
                    val tmp = java.io.File(vm.paths.bin, "$name.tmp")
                    tmp.writeText(out + "\n<<done>>\n"); tmp.renameTo(java.io.File(vm.paths.bin, name))
                }
            }
        }
        shellRx = rx
        val f = android.content.IntentFilter("com.enderbk.aemu.SHELL")
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED) else registerReceiver(rx, f)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKey(event)) return true
        val code = map(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) vm.input.key(code, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKey(event)) return true
        val code = map(keyCode) ?: return super.onKeyUp(keyCode, event)
        vm.input.key(code, false)
        return true
    }

    /**
     * The phone's volume keys while a machine is on screen. Inside the kiosk overlay they arrive from
     * the accessibility key filter (the overlay window has no activity key callback); in the plain
     * window the activity delivers them. Either way the guest gets the key and the host's own volume
     * never moves.
     */
    private fun volumeKey(event: KeyEvent): Boolean {
        if (volumePair(event)) return true
        // in the kiosk the volume keys are the hidden power gesture: never leak the first key of a
        // pair to the guest, or Power+Volume Down would take a screenshot instead of pressing Power
        if (kiosk) return true
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> InputService.KEY_VOLUMEUP
            KeyEvent.KEYCODE_VOLUME_DOWN -> InputService.KEY_VOLUMEDOWN
            else -> return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) vm.input.key(code, true)
            KeyEvent.ACTION_UP -> vm.input.key(code, false)
            else -> return false
        }
        return true
    }

    /**
     * A phone cannot give an app the power button, so the guest's power button is the two volume keys
     * pressed together twice: a pair within two seconds of the previous one presses POWER into LineageOS.
     * The press lasts as long as the key that completed the second pair: a tap turns the screen off,
     * holding it opens the guest's power menu. Returns true for the pair's second key — that stroke is
     * the shortcut's, not a volume change.
     */
    private fun volumePair(event: KeyEvent): Boolean {
        val bit = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> 2
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            volHeld = volHeld and bit.inv()
            val swallowed = volSwallowed and bit != 0
            volSwallowed = volSwallowed and bit.inv()
            // the key that started the guest's power button ends it: a quick tap is a short press,
            // keeping it down is the long press that opens LineageOS's power menu
            if (powerCode == event.keyCode) {
                powerCode = 0
                android.util.Log.i("LineageKeys", "POWER up after ${android.os.SystemClock.uptimeMillis()} ms")
                if (state == GuestVm.State.RUNNING) {
                    vm.input.key(InputService.KEY_POWER, false)
                    refreshGuestAwake()
                }
            }
            return swallowed
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        // a key down while the power button is still held (a lost up, or a third pair): end it first
        if (powerCode != 0) {
            powerCode = 0
            if (state == GuestVm.State.RUNNING) vm.input.key(InputService.KEY_POWER, false)
        }
        volSwallowed = volSwallowed and bit.inv()
        val both = volHeld and (if (bit == 1) 2 else 1) != 0
        volHeld = volHeld or bit
        if (!both) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - volPairAt > 2000) volPairs = 0
        volPairAt = now
        volPairs++
        volSwallowed = volSwallowed or bit
        if (volPairs >= 2) {
            volPairs = 0
            android.util.Log.i("LineageKeys", "second pair, state=$state")
            if (state == GuestVm.State.RUNNING) {
                // power down now, up when this key is released
                powerCode = event.keyCode
                android.util.Log.i("LineageKeys", "POWER down (held)")
                vm.input.key(InputService.KEY_POWER, true)
            }
        }
        return true
    }

    private fun map(k: Int): Int? = when (k) {
        KeyEvent.KEYCODE_VOLUME_UP -> InputService.KEY_VOLUMEUP
        KeyEvent.KEYCODE_VOLUME_DOWN -> InputService.KEY_VOLUMEDOWN
        KeyEvent.KEYCODE_MENU -> InputService.KEY_MENU
        KeyEvent.KEYCODE_SEARCH -> InputService.KEY_SEARCH
        else -> null
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.sensors.resume(true)
        // the user comes back to a minimized kiosk run (notification, launcher): cover the screen again
        if (kiosk && kioskMinimized) restoreKiosk()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (kiosk && kioskMinimized) restoreKiosk()
    }

    /** Show the kiosk display again after "Minimize" or the document picker, keeping the surface alive. */
    private fun restoreKiosk() {
        kioskMinimized = false
        revealOverlay()
    }

    /** Put the (possibly hidden) overlay window back on screen; detach only when it is really gone. */
    private fun revealOverlay() {
        val rr = root
        if (overlayShown && rr != null && OverlayService.setHidden(rr, false, vm.settings.keepScreenOn)) {
            enableKioskKeys()
            refreshGuestAwake()
            return
        }
        overlayShown = false
        if (!applyOverlay()) {
            // no accessibility window (refused or switched off): the activity window takes the tree back
            rr?.let { setContentView(it) }
        }
    }

    /** Hide the overlay while the document picker is in front: detaching it would kill the guest's surface. */
    private fun hideOverlayForPicker() {
        pickerUp = true
        val rr = root
        if (overlayShown && rr != null && OverlayService.setHidden(rr, true, false)) return
        unapplyOverlay()
    }

    /** Rotate the guest's logical display to match the phone, then turn the portrait framebuffer in the host. */
    private fun applyGuestRotation() {
        if (!::vm.isInitialized || vm.state != GuestVm.State.RUNNING) return
        val rot = guestRotation
        thread(name = "guest-rotation") {
            vm.guestShell("settings put system accelerometer_rotation 0; settings put system user_rotation $rot", 10_000)
        }
    }

    /** Ask the guest whether its display is on; the host draws last frame while it sleeps. */
    private fun refreshGuestAwake() {        if (!::vm.isInitialized || state != GuestVm.State.RUNNING) return
        val gen = ++awakeGen          // a quick wake/sleep pair must not apply the older answer
        thread {
            Thread.sleep(500)
            val out = runCatching {
                vm.guestShell("dumpsys power 2>/dev/null | grep -m1 mWakefulness", 20_000)
            }.getOrDefault("")
            if (gen != awakeGen) return@thread
            val asleep = when {
                out.contains("mWakefulness=Asleep") || out.contains("mWakefulness=Dozing") -> true
                out.contains("mWakefulness=Awake") -> false
                else -> null
            }
            if (asleep != null) runOnUiThread { if (!isFinishing && gen == awakeGen) guestAsleep = asleep }
        }
    }

    /** The hidden gesture and the phone's volume keys belong to the machine while its screen is on. */
    private fun enableKioskKeys() {
        if (!kiosk) return
        OverlayService.onUnlock = { runOnUiThread { if (kiosk && !kioskMinimized) kioskMenu = true } }
        OverlayService.onKey = { ev -> volumeKey(ev) }
    }

    override fun onPause() {
        if (::vm.isInitialized) vm.sensors.resume(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (kiosk) {
            OverlayService.onUnlock = null
            OverlayService.onKey = null
        }
        vnc?.stop(); vnc = null
        recorder?.let { r -> thread { r.stop() } }; recorder = null
        if (::guest.isInitialized) guest.stop()
        shellRx?.let { runCatching { unregisterReceiver(it) } }
        // the display this instance built: on a real teardown (finish, task removal) the overlay window
        // goes down with it — stopVm has already taken it; a fresh VmActivity puts its own tree into a
        // fresh overlay window. Rotations keep the window: isChangingConfigurations.
        if (!isChangingConfigurations) unapplyOverlay(settleBack = false)
        super.onDestroy()
    }

    /** "Minimize": hide the overlay, leave the machine running, and go back to the phone's own screen */
    private fun minimizeKiosk() {
        kioskMinimized = true
        OverlayService.onUnlock = null          // the hidden gesture belongs to the machine on screen
        OverlayService.onKey = null
        val rr = root
        if (overlayShown && rr != null) {
            // not detached: removing the window destroys the SurfaceView the machine draws into, and the
            // guest's display dies with it (surfaceflinger then takes the system down)
            if (!OverlayService.setHidden(rr, true, false)) unapplyOverlay(settleBack = false)
        } else {
            unapplyOverlay(settleBack = false)
        }
        toast(getString(R.string.ln_minimize_hint))
        moveTaskToBack(true)
    }

    private fun stopVm() {
        // the machine's display is the overlay: a clean stop (fs sync, killing the guest processes and the
        // leftovers) takes seconds — drop the frozen screen first so a shutdown from inside Android feels
        // immediate; the plain window is blank already (setContentView placeholder) until the process exits
        runOnUiThread { unapplyOverlay(settleBack = false) }
        thread {
            vm.stop()
            VmHost.vm = null
            if (kiosk) runCatching { File(filesDir, RUNNING_FLAG).delete() }
            runOnUiThread { finishAndRemoveTask() }
            Thread.sleep(300)
            // GL-мост нельзя поднять повторно в том же процессе — процесс :vm уходит вместе с машиной
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private enum class Dlg { NONE, SMS, CALL, BATTERY, ADB, DEVICE }
    /** open dialog — a field (not remember): it survives the composition rebuild while moving the tree between windows */
    private val dialogState = mutableStateOf(Dlg.NONE)
    /** the system document chooser for APK install is up: keep the overlay down until its result returns */
    @Volatile private var pickerUp = false

    // position of the floating menu button, kept across launches
    private val uiPrefs by lazy { getSharedPreferences("vm_ui", MODE_PRIVATE) }
    private var menuOffset by mutableStateOf(Offset.Zero)
    private fun loadMenuOffset() { menuOffset = Offset(uiPrefs.getFloat("menu_x", 0f), uiPrefs.getFloat("menu_y", 0f)) }
    private fun saveMenuOffset() { uiPrefs.edit().putFloat("menu_x", menuOffset.x).putFloat("menu_y", menuOffset.y).apply() }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.mic.permissionGranted() }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun guestAsync(cmd: String, done: ((String) -> Unit)? = null) {
        thread { val out = runCatching { vm.guestShell(cmd, 60_000) }.getOrElse { it.toString() }; done?.invoke(out) }
    }

    /** Quote for the guest's /system/bin/sh. */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun pasteToGuest() {
        val text = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) { toast(getString(R.string.m_clip_empty)); return }
        // Normalize line endings and truncate extreme paste payloads
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val safeLines = if (lines.size > 150) lines.take(150) else lines
        val script = buildString {
            for ((i, line) in safeLines.withIndex()) {
                if (line.isNotEmpty()) {
                    val escaped = line.replace(" ", "%s")
                    append("input text ").append(q(escaped)).append("; ")
                }
                if (i < safeLines.lastIndex) {
                    append("input keyevent 66; ")
                }
            }
        }
        guestAsync(script)
    }

    /**
     * The frame is taken on the host (the fb0 copy, or the GPU bridge's surface through PixelCopy): `screencap` does
     * not exist before 4.0 and cannot read the bridge's frames. The PNG lands in the guest's Pictures folder.
     */
    private fun screenshot() {
        val name = "aemu-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".png"
        val hostDir = app.aemu.core.Sdcard.hostDir(vm.paths)?.let { java.io.File(it, "Pictures") }
        if (hostDir == null) { toast(getString(R.string.m_failed)); return }
        fun save(b: android.graphics.Bitmap) = thread {
            val ok = runCatching {
                hostDir.mkdirs()
                java.io.File(hostDir, name).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            toast(if (ok) getString(R.string.m_screenshot_saved, vm.img.sdcardPath.trimEnd('/') + "/Pictures/$name") else getString(R.string.m_failed))
            // the guest's media scanner picks the file up for its gallery
            if (ok) guestAsync("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://${vm.img.sdcardPath.trimEnd('/')}/Pictures/$name")
        }
        guest.snapshot()?.let { save(it); return }
        if (surfaceView.width <= 0 || surfaceView.height <= 0) { toast(getString(R.string.m_failed)); return }
        val b = android.graphics.Bitmap.createBitmap(surfaceView.width, surfaceView.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(surfaceView, b, { r ->
            if (r == android.view.PixelCopy.SUCCESS) save(b) else toast(getString(R.string.m_failed))
        }, android.os.Handler(mainLooper))
    }

    /**
     * The current frame at the guest's resolution: the fb0 copy, or the GPU bridge's surface through PixelCopy
     * (waited for here, so not to be called on the main thread).
     */
    private fun grabFrame(into: android.graphics.Bitmap): Boolean {
        val dst = android.graphics.Rect(0, 0, into.width, into.height)
        guest.snapshot()?.let { android.graphics.Canvas(into).drawBitmap(it, null, dst, null); it.recycle(); return true }
        val sw = surfaceView.width
        val sh = surfaceView.height
        if (sw <= 0 || sh <= 0 || !surfaceView.holder.surface.isValid) return false
        val tmp = grabScratch?.takeIf { it.width == sw && it.height == sh }
            ?: android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888).also { grabScratch = it }
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        runCatching {
            android.view.PixelCopy.request(surfaceView, tmp, { r -> ok = r == android.view.PixelCopy.SUCCESS; done.countDown() },
                android.os.Handler(grabThread.looper))
        }.onFailure { return false }
        if (!done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) || !ok) return false
        android.graphics.Canvas(into).drawBitmap(tmp, null, dst, null)
        return true
    }

    /** VNC on/off. Each session gets a password of its own, shown here and in the log. */
    private fun toggleVnc() {
        vnc?.let { it.stop(); vnc = null; vncOn.value = false; return }
        if (guestW <= 0 || guestH <= 0) { toast(getString(R.string.m_failed)); return }
        val password = (1..8).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
        val bmp = android.graphics.Bitmap.createBitmap(guestW, guestH, android.graphics.Bitmap.Config.ARGB_8888)
        val server = app.aemu.core.VncServer(app.aemu.core.VncServer.DEFAULT_PORT, password, guestW, guestH, vm.img.name,
            { px -> if (grabFrame(bmp)) { bmp.getPixels(px, 0, guestW, 0, 0, guestW, guestH); true } else false }, vm.input, vm::log)
        if (!server.start()) { toast(getString(R.string.m_failed)); return }
        vnc = server; vncOn.value = true
        val ip = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }.firstOrNull { it is java.net.Inet4Address }?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
        val where = getString(R.string.m_vnc_started, "$ip:${app.aemu.core.VncServer.DEFAULT_PORT}", password)
        vm.log("vnc: $where")
        runOnUiThread { Toast.makeText(this, where, Toast.LENGTH_LONG).show() }
    }

    /** Screen recording on/off: an MP4 in the Movies folder of the guest's card (the phone's shared folder). */
    private fun toggleRecording() {
        recorder?.let { r ->
            recorder = null; recOn.value = false
            thread {
                r.stop()
                toast(getString(R.string.m_screenshot_saved, vm.img.sdcardPath.trimEnd('/') + "/Movies/" + r.file.name))
                guestAsync("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://${vm.img.sdcardPath.trimEnd('/')}/Movies/${r.file.name}")
            }
            return
        }
        val dir = app.aemu.core.Sdcard.hostDir(vm.paths)?.let { java.io.File(it, "Movies") }
        if (dir == null || guestW <= 0 || guestH <= 0) { toast(getString(R.string.m_failed)); return }
        val name = "aemu-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
        val r = app.aemu.core.ScreenRecorder(java.io.File(dir, name), guestW, guestH, ::grabFrame, vm::log)
        if (!r.start()) { toast(getString(R.string.m_failed)); return }
        recorder = r; recOn.value = true
    }

    // APK picked on the host: copied into the shared card folder, then installed by the guest's package manager
    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // the document picker opened below the overlay: the tree was moved back — cover the machine again
        pickerUp = false
        revealOverlay()
        if (uri == null) return@registerForActivityResult
        val dir = app.aemu.core.Sdcard.hostDir(vm.paths)
        if (dir == null) { toast(getString(R.string.m_failed)); return@registerForActivityResult }
        toast(getString(R.string.m_install_apk_start))
        thread {
            val ok = runCatching {
                contentResolver.openInputStream(uri)!!.use { i -> java.io.File(dir, "lineage-install.apk").outputStream().use { o -> i.copyTo(o) } }
            }.isSuccess
            if (!ok) { toast(getString(R.string.m_failed)); return@thread }
            val out = runCatching { vm.guestShell("pm install -r " + vm.img.sdcardPath.trimEnd('/') + "/lineage-install.apk", 300_000) }.getOrElse { it.toString() }
            runCatching { app.aemu.core.TreeFixer(this@VmActivity, vm.paths, vm.img, vm::log).seedOwners() }
            toast(out.trim().lines().lastOrNull { it.isNotBlank() }?.take(200) ?: getString(R.string.m_failed))
        }
    }

    private fun sendSms(from: String, body: String) {
        // through the emulated modem: stored, notified and rung like a real SMS on every version
        if (vm.ril.sms(from, body)) { toast(getString(R.string.m_sms_sent)); return }
        // radio emulation off: the message is written straight into the SMS provider inbox (4.1+)
        val cmd = "content insert --uri content://sms/inbox --bind address:s:${q(from)} --bind body:s:${q(body)} " +
            "--bind read:i:0 --bind date:l:${System.currentTimeMillis()}"
        guestAsync(cmd) { out -> toast(if (out.isBlank()) getString(R.string.m_sms_sent) else out.trim().take(200)) }
    }

    @Composable
    private fun SmsDialog(onDone: () -> Unit) {
        var from by remember { mutableStateOf("+10000000000") }
        var body by remember { mutableStateOf("Hello from LineageEMU") }
        // an in-tree panel, no AlertDialog: its separate window sits under the accessibility overlay and
        // the repeated open could stay invisible; the host card scrolls and taps keep working
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.m_sms), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(from, { from = it }, label = { Text(stringResource(R.string.m_sms_from)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.m_sms_body)) },
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onDone) { Text(stringResource(R.string.close)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { sendSms(from, body); onDone() }) { Text(stringResource(R.string.m_send)) }
            }
        }
    }

    @Composable
    private fun DeviceDialog(onDone: () -> Unit) {
        var airplane by remember { mutableStateOf(false) }
        var awake by remember { mutableStateOf(false) }
        val ns = if (vm.img.api >= 17) "global" else "system"
        // the switches show what the guest has now
        LaunchedEffect(Unit) {
            guestAsync("settings get $ns airplane_mode_on; settings get $ns stay_on_while_plugged_in") { out ->
                val v = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
                runOnUiThread { airplane = v.getOrNull(0) == "1"; awake = (v.getOrNull(1)?.toIntOrNull() ?: 0) != 0 }
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.m_device), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.m_airplane), Modifier.weight(1f))
                Switch(airplane, { v ->
                    airplane = v
                    guestAsync("settings put $ns airplane_mode_on ${if (v) 1 else 0}; " +
                        "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $v")
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.m_stay_awake), Modifier.weight(1f))
                Switch(awake, { v ->
                    awake = v
                    guestAsync("settings put $ns stay_on_while_plugged_in ${if (v) 7 else 0}; svc power stayon ${if (v) "true" else "false"}")
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onDone) { Text(stringResource(R.string.close)) }
            }
        }
    }

    @Composable
    private fun AdbDialog(onDone: () -> Unit) {
        var on by remember { mutableStateOf(vm.adb.running) }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.m_adb), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.m_adb_enable), Modifier.weight(1f))
                Switch(on, { v -> if (v) vm.adb.start() else vm.adb.stop(); on = vm.adb.running })
            }
            if (on) {
                val ips = app.aemu.core.AdbServer.addresses()
                for (ip in ips.ifEmpty { listOf("127.0.0.1") })
                    Text("adb connect $ip:${vm.adb.port}", style = MaterialTheme.typography.bodyLarge,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            Text(stringResource(R.string.m_adb_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDone) { Text(stringResource(R.string.close)) }
            }
        }
    }

    @Composable
    private fun CallDialog(onDone: () -> Unit) {
        var from by remember { mutableStateOf("+10000000000") }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.m_call), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(from, { from = it }, label = { Text(stringResource(R.string.m_sms_from)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onDone) { Text(stringResource(R.string.close)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (!vm.ril.ring(from.trim())) toast(getString(R.string.m_failed))
                    onDone()
                }) { Text(stringResource(R.string.m_call_ring)) }
            }
        }
    }

    @Composable
    private fun BatteryDialog(onDone: () -> Unit) {
        // начальные значения — прямо из телефона (HostBattery читает их для зеркала),
        // без опроса гостя: диалог открывается мгновенно
        var level by remember { mutableStateOf(HostBattery.hostLevel?.toFloat() ?: 80f) }
        var charging by remember { mutableStateOf(HostBattery.hostCharging ?: true) }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.m_battery), style = MaterialTheme.typography.titleLarge)
            Text("${level.toInt()}%")
            Slider(level, { level = it }, valueRange = 0f..100f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.m_charging), Modifier.weight(1f)); Switch(charging, { charging = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    guestAsync("dumpsys battery reset 2>/dev/null")
                    vm.batteryManual = false // зеркало снова ведёт батарею от телефона
                    onDone()
                }) { Text(stringResource(R.string.m_reset)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val lvl = level.toInt()
                    val ac = if (charging) 1 else 0
                    val cmd = "echo $lvl > /sys/class/power_supply/battery/capacity 2>/dev/null; " +
                        "echo $ac > /sys/class/power_supply/ac/online 2>/dev/null; " +
                        "dumpsys battery set level $lvl 2>/dev/null; " +
                        "dumpsys battery set ac $ac 2>/dev/null; " +
                        "dumpsys battery set usb 0 2>/dev/null"
                    guestAsync(cmd)
                    // вручную выставленная батарея главнее зеркала (до перезагрузки гостя)
                    vm.batteryManual = true
                    onDone()
                }) { Text(stringResource(R.string.m_apply)) }
            }
        }
    }

    private fun rebootVm(recovery: Boolean = false) {
        val id = vm.img.id
        thread {
            vm.stop()
            VmHost.vm = null
            // the GL bridge cannot come up twice in one process, so a reboot restarts the :vm process
            runOnUiThread { restartProcess(id, recovery) }
        }
    }

    private fun restartProcess(id: String, recovery: Boolean = false) {
        val i = Intent(this, VmActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_RECOVERY, recovery)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(this, 1, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT)
        (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ------------------------------------------------------------------ fullscreen: overlay of the whole phone

    /**
     * Move the machine's display (the whole view tree: guest screen + UI overlay) into the accessibility
     * overlay window. Compose state that lived purely inside the overlay composable is recreated on the
     * move (open log/terminal panels just close); everything that matters (the machine, the guest screen,
     * the log) lives in objects of this process and is not touched.
     */
    private fun applyOverlay(): Boolean {
        val rr = root ?: return false
        if (overlayShown) return true
        if (!vm.settings.fullscreen || overlayDeclined || !OverlayService.connected()) return false
        (rr.parent as? ViewGroup)?.let { p -> p.removeView(rr) }
        // inside the new window there is no activity around: give the tree the owners compose needs
        rr.setViewTreeLifecycleOwner(this)
        rr.setViewTreeViewModelStoreOwner(this)
        rr.setViewTreeSavedStateRegistryOwner(this)
        // focused so hardware keys reach the guest; this listener feeds them the way activity windows did
        rr.isFocusable = true
        rr.setOnKeyListener { _, code, ev -> onOverlayKey(code, ev) }
        if (!OverlayService.show(rr, vm.settings.keepScreenOn, vm.displayRate.id)) {
            // the system refused the window: back into the activity window (the normal fallback)
            setContentView(rr)
            return false
        }
        overlayShown = true
        enableKioskKeys()
        // the host's own gestures reach the machine through the overlay window (back swipes from the side
        // edges, home from the bottom): the focused window's immersive request keeps the system bars and
        // their gesture zones away, and the exclusion rects cover the side edges on top of that
        androidx.core.view.ViewCompat.getWindowInsetsController(rr)?.let { c ->
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        updateGestureExclusion(rr)
        rr.requestLayout()
        return true
    }

    /** Ask the system not to take the side edges for its back gesture while the machine covers the screen. */
    private fun updateGestureExclusion(v: View) {
        if (android.os.Build.VERSION.SDK_INT >= 29)
            v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
    }

    /** Take the display back from the overlay into the activity's own window. */
    private fun unapplyOverlay(settleBack: Boolean = true) {
        if (!overlayShown) return
        overlayShown = false
        if (kiosk) {
            OverlayService.onUnlock = null
            OverlayService.onKey = null
        }
        val rr = root ?: return
        OverlayService.hide(rr)
        rr.setOnKeyListener(null)
        // the overlay was the focused window: its hidden system bars come back for the plain window
        androidx.core.view.ViewCompat.getWindowInsetsController(rr)?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        if (settleBack && !isFinishing) {
            // the activity window's insets flow again; the listener re-fills safeTopPx/safeBottomPx
            setContentView(rr)
            androidx.core.view.ViewCompat.requestApplyInsets(rr)
        }
    }

    /** Hardware keys while the display is an overlay window: the activity window holds no focus here. */
    private fun onOverlayKey(code: Int, ev: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return volumeKey(ev)
        return when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (code == KeyEvent.KEYCODE_BACK) { vm.input.press(InputService.KEY_BACK); true }
                else {
                    val mapped = map(code)
                    if (mapped != null) {
                        if (ev.repeatCount == 0) vm.input.key(mapped, true)
                        true
                    } else false
                }
            }
            KeyEvent.ACTION_UP -> {
                val mapped = map(code)
                if (mapped != null) { vm.input.key(mapped, false); true } else false
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------ интерфейс поверх экрана

    /** Consumes every pointer event the node's own handlers left over — in the FINAL pass, after the children
     *  (the button, the nav keys) had their Main-pass shot. Consuming in Main would cancel their taps outright
     *  (the 3-dot menu stopped opening). Without the guard the events fall through to the guest's view, which
     *  forwards everything to the machine — the menu button "glitched" the guest once its UI was up. */
    private fun Modifier.noGuestTouch(): Modifier = pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Final)
                for (c in e.changes) if (c.pressed) c.consume()
            }
        }
    }

    @Composable
    private fun Overlay() {
        var showLog by remember { mutableStateOf(false) }
        var showTerm by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var dialog by dialogState
        // recovery is "running" too, but has no Android behind am/pm/settings
        val running = state == GuestVm.State.RUNNING && !vm.recoveryMode
        var seconds by remember { mutableStateOf(0L) }
        var frames by remember { mutableStateOf(0L) }
        var bootPrev by remember { mutableStateOf(GuestVm.State.STOPPED) }
        LaunchedEffect(Unit) { while (true) {
            // a new boot attempt or a failure brings the boot card back: the X only hides it for one boot run
            if (state != bootPrev) {
                if (state == GuestVm.State.PREPARING || state == GuestVm.State.FAILED) bootCardHidden = false
                bootPrev = state
            }
            if (vm.recoveryMode) guest.format = vm.recoveryFormat else if (vm.engine == app.aemu.core.Engine.N64) guest.format = 1
            // fullscreen: the overlay goes up as soon as the accessibility service is connected, and comes
            // down again here if the user switches the service off while the machine runs; the plain
            // activity window in between is the fallback. (the dialogs are drawn inside this tree, so
            // they need no window of their own; only the apk picker is a real activity window below the
            // overlay and keeps the tree in the plain window while it is up)
            if (vm.settings.fullscreen && !overlayDeclined && !pickerUp && !kioskMinimized) {
                if (OverlayService.connected() && !overlayShown) applyOverlay()
                else if (!OverlayService.connected() && overlayShown) unapplyOverlay()
            } else if (overlayDeclined && overlayShown) {
                // the plain activity window is the chosen display; minimized and picker states keep the
                // window attached (hidden), so the guest's SurfaceView and its display survive
                unapplyOverlay()
            }
            // the home screen must not pull the image from under a live machine: keep its flag fresh
            if (kiosk) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - flagAt > 30_000) {
                    flagAt = now
                    runCatching { File(filesDir, RUNNING_FLAG).setLastModified(System.currentTimeMillis()) }
                }
            }
            seconds = vm.bootSeconds(); frames = if (vm.glInApp) dev.lk.m7sense.GlBridge.frames() else guest.rings; delay(500)
        } }
        // как только гость начал рисовать — карточку убираем, остаётся маленький индикатор
        val drawing = frames > 30
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // the menu button stays on screen: 40 dp button + 8 dp padding, anchored at the top end
            val keep = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
            val maxX = (constraints.maxWidth - keep).coerceAtLeast(0f)
            val maxY = (constraints.maxHeight - keep * 2).coerceAtLeast(0f)
            val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
            fun clamp(o: Offset) = Offset(if (rtl) o.x.coerceIn(0f, maxX) else o.x.coerceIn(-maxX, 0f), o.y.coerceIn(0f, maxY))
            LaunchedEffect(maxX, maxY) { menuOffset = clamp(menuOffset) }
            led?.takeIf { it.lit && showLed }?.let { LedIndicator(it, Modifier.align(Alignment.TopCenter)) }
            // карточка загрузки: скрываемая кнопкой X, пока машина продолжает грузиться (клик по компактной
            // плашке «booting…» возвращает её)
            val cardVisible = !kiosk && state != GuestVm.State.RUNNING && !showLog &&
                !(drawing && state == GuestVm.State.BOOTING) &&
                !(bootCardHidden && state != GuestVm.State.FAILED)
            AnimatedVisibility(
                visible = cardVisible,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) { BootCard(seconds) { bootCardHidden = true } }

            // while booting the compact "booting…" pill: the guest has started drawing, or the boot card
            // was hidden with the X — tapping it brings the card back
            if (!kiosk && !showLog && state != GuestVm.State.RUNNING && state != GuestVm.State.FAILED && !cardVisible && vm.screenUpAt == 0L) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopStart).displayCutoutPadding().statusBarsPadding().padding(8.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).clickable { bootCardHidden = false },
                        verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.vm_booting_short, seconds.toInt()), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // журнал
            AnimatedVisibility(visible = showLog, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                LogPanel(onClose = { showLog = false })
            }
            AnimatedVisibility(visible = showTerm, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                TerminalPanel(onClose = { showTerm = false })
            }

            // top menu button (hidden while the log is open so it does not cover the log toolbar); drag to move it
            if (!kiosk && !showLog && !showTerm) Box(Modifier.align(Alignment.TopEnd).displayCutoutPadding().statusBarsPadding().padding(8.dp)
                .noGuestTouch()
                .offset { IntOffset(menuOffset.x.roundToInt(), menuOffset.y.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { saveMenuOffset() }) { ch, d -> ch.consume(); menuOffset = clamp(menuOffset + d) }
                }) {
                FilledTonalIconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.menu))
                }
            }

            // inside the fullscreen accessibility overlay the dropdown's separate popup window would
            // render UNDER that overlay: the menu is drawn in place so scrolling and clicks work the
            // same way as in the plain activity window
            // no fade animation: a second opening after a quick close could stay fully transparent
            // (clicks worked, the panel was not visible) — a plain conditional has no such state to lose
            if (menu) {
                Box(Modifier.matchParentSize().noGuestTouch().pointerInput(Unit) { detectTapGestures { menu = false } })
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth().padding(horizontal = 8.dp, vertical = 72.dp)
                        .statusBarsPadding().displayCutoutPadding(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 6.dp, tonalElevation = 2.dp,
                ) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.log)) }, leadingIcon = { Icon(Icons.Rounded.Terminal, null) },
                            onClick = { menu = false; showLog = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.m_terminal)) }, leadingIcon = { Icon(Icons.Rounded.Code, null) },
                            enabled = running, onClick = { menu = false; showTerm = true })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.vol_up)) }, leadingIcon = { Icon(Icons.Rounded.VolumeUp, null) },
                            onClick = { vm.input.press(InputService.KEY_VOLUMEUP) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.vol_down)) }, leadingIcon = { Icon(Icons.Rounded.VolumeDown, null) },
                            onClick = { vm.input.press(InputService.KEY_VOLUMEDOWN) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.power)) }, leadingIcon = { Icon(Icons.Rounded.PowerSettingsNew, null) },
                            onClick = { menu = false; vm.input.press(InputService.KEY_POWER) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.m_power_menu)) }, leadingIcon = { Icon(Icons.Rounded.PowerSettingsNew, null) },
                            onClick = { menu = false; vm.input.press(InputService.KEY_POWER, 1500) })
                        // long-press Menu makes 2.x–4.x call InputMethodManager.toggleSoftInput: the firmware's own keyboard
                        if (!vm.recoveryMode) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_keyboard)) }, leadingIcon = { Icon(Icons.Rounded.Keyboard, null) },
                                enabled = running, onClick = {
                                    menu = false
                                    // 5.0+ hides the soft keyboard while it sees hardware keys and has no long-Menu toggle
                                    if (vm.img.api >= 21) guestAsync("settings put secure show_ime_with_hard_keyboard 1")
                                    else vm.input.press(InputService.KEY_MENU, 1000)
                                })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_paste)) }, leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                                enabled = running, onClick = { menu = false; pasteToGuest() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_sms)) }, leadingIcon = { Icon(Icons.Rounded.Sms, null) },
                                enabled = running, onClick = { menu = false; dialog = Dlg.SMS })
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_call)) }, leadingIcon = { Icon(Icons.Rounded.Call, null) },
                                enabled = running, onClick = { menu = false; dialog = Dlg.CALL })
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_battery)) }, leadingIcon = { Icon(Icons.Rounded.BatteryStd, null) },
                                enabled = running, onClick = { menu = false; dialog = Dlg.BATTERY })
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_install_apk)) }, leadingIcon = { Icon(Icons.Rounded.InstallMobile, null) },
                                enabled = running, onClick = {
                                    menu = false
                                    // the document chooser opens below the accessibility overlay: fall back
                                    // to the plain window first, applyOverlay() returns in the picker result
                                    hideOverlayForPicker()
                                    pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                                })
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_device)) }, leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                                enabled = running, onClick = { menu = false; dialog = Dlg.DEVICE })
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_adb)) }, leadingIcon = { Icon(Icons.Rounded.Lan, null) },
                                enabled = running, onClick = { menu = false; dialog = Dlg.ADB })
                            DropdownMenuItem(text = { Text(stringResource(R.string.m_settings)) }, leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                enabled = running, onClick = { menu = false; guestAsync("am start -a android.settings.SETTINGS") })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.m_screenshot)) }, leadingIcon = { Icon(Icons.Rounded.Screenshot, null) },
                            enabled = state == GuestVm.State.RUNNING, onClick = { menu = false; screenshot() })
                        DropdownMenuItem(text = { Text(stringResource(if (recOn.value) R.string.m_record_stop else R.string.m_record)) },
                            leadingIcon = { Icon(Icons.Rounded.Videocam, null) },
                            enabled = state == GuestVm.State.RUNNING || recOn.value, onClick = { menu = false; toggleRecording() })
                        DropdownMenuItem(text = { Text(stringResource(if (vncOn.value) R.string.m_vnc_stop else R.string.m_vnc)) },
                            leadingIcon = { Icon(Icons.Rounded.Cast, null) },
                            onClick = { menu = false; toggleVnc() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.m_reset_menu_pos)) }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                            onClick = { menu = false; menuOffset = Offset.Zero; saveMenuOffset() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.m_reboot)) }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                            onClick = { menu = false; rebootVm() })
                        DropdownMenuItem(text = { Text(stringResource(if (vm.recoveryMode) R.string.m_reboot_system else R.string.m_reboot_recovery)) },
                            leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                            enabled = vm.recoveryMode || app.aemu.core.RecoveryImage.installed(vm.paths),
                            onClick = { menu = false; rebootVm(!vm.recoveryMode) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.shutdown)) }, leadingIcon = { Icon(Icons.Rounded.Close, null) },
                            onClick = { menu = false; stopVm() })
                    }
                }
            }

            // the dialogs are drawn here in place, as the menu: a dialog's separate window sits UNDER the
            // accessibility overlay and after the first open it could come out invisible. The same card
            // serves both windows (a plain run shows the very same thing without moving anywhere)
            if (dialog != Dlg.NONE) {
                Box(Modifier.matchParentSize().noGuestTouch().background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(dialog) { detectTapGestures { dialog = Dlg.NONE } })
                Surface(shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 80.dp).statusBarsPadding().displayCutoutPadding()
                        .noGuestTouch()
                        // the card swallows taps on its own empty space: only the dimmed outside closes the dialog
                        .pointerInput(Unit) { detectTapGestures { } },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 6.dp, tonalElevation = 2.dp,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        when (dialog) {
                            Dlg.SMS -> SmsDialog { dialog = Dlg.NONE }
                            Dlg.CALL -> CallDialog { dialog = Dlg.NONE }
                            Dlg.BATTERY -> BatteryDialog { dialog = Dlg.NONE }
                            Dlg.ADB -> AdbDialog { dialog = Dlg.NONE }
                            Dlg.DEVICE -> DeviceDialog { dialog = Dlg.NONE }
                            Dlg.NONE -> {}
                        }
                    }
                }
            }

            // the guest's display is off: do not leave the last frame of LineageOS frozen on screen
            if (kiosk && state == GuestVm.State.RUNNING && guestAsleep) {
                Box(Modifier.matchParentSize().background(Color.Black))
            }

            // kiosk: the boot log takes the whole screen until the guest has finished booting
            if (kiosk && state != GuestVm.State.RUNNING) {
                LogPanel(onClose = { }, bare = true)
            }

            // kiosk menu: the hidden key gesture (VOL UP DOWN UP DOWN + both keys, DOWN UP DOWN UP + both keys) brings it up
            if (kiosk && kioskMenu) {
                Box(Modifier.matchParentSize().noGuestTouch().background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { kioskMenu = false } })
                Surface(shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 80.dp).statusBarsPadding().displayCutoutPadding()
                        .noGuestTouch().pointerInput(Unit) { detectTapGestures { } },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 6.dp, tonalElevation = 2.dp,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.ln_menu_title), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
                        DropdownMenuItem(text = { Text(stringResource(R.string.ln_install_apk)) },
                            leadingIcon = { Icon(Icons.Rounded.InstallMobile, null) },
                            enabled = running, onClick = {
                                kioskMenu = false
                                // the document chooser opens below the accessibility overlay: fall back to the
                                // plain window first, applyOverlay() returns in the picker result
                                hideOverlayForPicker()
                                pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ln_minimize)) },
                            leadingIcon = { Icon(Icons.Rounded.Minimize, null) },
                            onClick = { kioskMenu = false; minimizeKiosk() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ln_shutdown_vm)) },
                            leadingIcon = { Icon(Icons.Rounded.PowerSettingsNew, null) },
                            onClick = { kioskMenu = false; stopVm() })
                    }
                }
            }

            // панель кнопок Android (при открытом диалоге его затемнение перекрывает и её)
    if (!kiosk && vm.settings.showNavBar && !showLog && !showTerm && dialog == Dlg.NONE) {
        NavBar(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().noGuestTouch()
            .padding(bottom = if (vm.settings.navSize == 0) 3.dp else 6.dp))
    }
        }
    }

    /** The bar of guest buttons: the user's own set, each held down in the guest for as long as it is held here. */
    @Composable
    private fun NavBar(modifier: Modifier) {
        val compact = vm.settings.navSize == 0
        val keys = remember { vm.settings.navKeys.split(',').filter { it in app.aemu.core.VmSettings.NAV_ALL } }
        if (keys.isEmpty()) return
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), modifier = modifier) {
            Row(Modifier.padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 2.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 4.dp)) {
                for (k in keys) NavKey(k, if (compact) 36.dp else 48.dp)
            }
        }
    }

    @Composable
    private fun NavKey(k: String, size: androidx.compose.ui.unit.Dp) {
        var pressed by remember { mutableStateOf(false) }
        val bg by androidx.compose.animation.animateColorAsState(
            if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent, label = "key")
        val code = when (k) {
            "back" -> InputService.KEY_BACK; "home" -> InputService.KEY_HOME; "menu" -> InputService.KEY_MENU
            "search" -> InputService.KEY_SEARCH; "volup" -> InputService.KEY_VOLUMEUP; "voldown" -> InputService.KEY_VOLUMEDOWN
            "power" -> InputService.KEY_POWER; else -> 0
        }
        val step = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
        Box(Modifier.size(size).background(bg, CircleShape).pointerInput(k) {
            awaitEachGesture {
                val down = awaitFirstDown()
                pressed = true
                val t0 = android.os.SystemClock.uptimeMillis()
                if (k == "home") vm.input.home(true)
                else if (code != 0) vm.input.key(code, true)
                // the D-pad button is a little trackball: sliding the finger sends arrows, a tap selects
                var acc = Offset.Zero
                var moved = false
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (k == "dpad") {
                        acc += c.position - c.previousPosition
                        while (kotlin.math.abs(acc.x) >= step || kotlin.math.abs(acc.y) >= step) {
                            moved = true
                            if (kotlin.math.abs(acc.x) >= kotlin.math.abs(acc.y)) {
                                vm.input.press(if (acc.x > 0) InputService.KEY_RIGHT else InputService.KEY_LEFT, 30)
                                acc = Offset(acc.x - step * kotlin.math.sign(acc.x), acc.y)
                            } else {
                                vm.input.press(if (acc.y > 0) InputService.KEY_DOWN else InputService.KEY_UP, 30)
                                acc = Offset(acc.x, acc.y - step * kotlin.math.sign(acc.y))
                            }
                        }
                    }
                    c.consume()
                    if (!c.pressed) break
                }
                pressed = false
                if (k == "home") vm.input.home(false)
                else if (code != 0) vm.input.key(code, false)
                else if (k == "recents") vm.input.recents(vm.img.api, vm.img.skin)
                else if (k == "dpad" && !moved) vm.input.press(InputService.KEY_CENTER, if (android.os.SystemClock.uptimeMillis() - t0 > 500) 700 else 80)
            }
        }, contentAlignment = Alignment.Center) {
            val d = navKeyLabel(k)
            val m = Modifier.size(if (size < 40.dp) 20.dp else 24.dp)
            when (k) {
                "back" -> Icon(Icons.AutoMirrored.Rounded.ArrowBack, d, m)
                "home" -> Icon(Icons.Rounded.Circle, d, m)
                "recents" -> Icon(Icons.Rounded.CropSquare, d, m)
                "menu" -> Icon(Icons.Rounded.Menu, d, m)
                "search" -> Icon(Icons.Rounded.Search, d, m)
                "volup" -> Icon(Icons.Rounded.VolumeUp, d, m)
                "voldown" -> Icon(Icons.Rounded.VolumeDown, d, m)
                "power" -> Icon(Icons.Rounded.PowerSettingsNew, d, m)
                else -> Icon(Icons.Rounded.Gamepad, d, m)
            }
        }
    }

    /**
     * The guest's LED above its screen: Sony's illumination bar as a glowing strip, other phones' notification LED as
     * a dot. Blinks with the guest's on/off times.
     */
    @Composable
    private fun LedIndicator(l: app.aemu.core.Lights.Led, modifier: Modifier) {
        var on by remember(l) { mutableStateOf(true) }
        LaunchedEffect(l) {
            if (l.onMs > 0 && l.offMs > 0) while (true) { on = true; delay(l.onMs.toLong()); on = false; delay(l.offMs.toLong()) }
        }
        val alpha by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0.08f,
            androidx.compose.animation.core.tween(if (on) 120 else 260), label = "led")
        val c = Color(0xff000000.toInt() or (l.color and 0xffffff))
        val sony = vm.img.skin.contains("Xperia", true) || vm.img.brand.contains("Sony", true)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val y = with(density) { (guestTopPx.toDp() - 10.dp).coerceAtLeast(4.dp) }
        if (sony) {
            Box(
                modifier.offset(y = y)
                    .width(160.dp)
                    .height(6.dp)
                    .alpha(alpha)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                c.copy(alpha = 0.4f),
                                c,
                                c.copy(alpha = 0.4f),
                                Color.Transparent
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier.offset(y = y).size(14.dp)
                    .alpha(alpha)
                    .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(c, c.copy(alpha = 0.55f), Color.Transparent)), CircleShape)
            )
        }
    }

    /** Boot progress; [onHide] hides it while the machine keeps booting (not offered on failure). */
    @Composable
    private fun BootCard(seconds: Long, onHide: () -> Unit) {
        Card(
            modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurface),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Box {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state == GuestVm.State.FAILED) {
                    Text(stringResource(R.string.failed_start), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(vm.failure ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { stopVm() }) { Text(stringResource(R.string.close)) }
                        Button(onClick = { thread { vm.stop(); vm.boot() } }) { Text(stringResource(R.string.retry)) }
                    }
                } else {
                    LoadingIndicator(Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(vm.img.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(vm.img.displayVersion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    val phase = when (state) {
                        GuestVm.State.PREPARING -> stringResource(R.string.st_preparing)
                        GuestVm.State.BOOTING -> stringResource(R.string.st_booting, seconds.toInt())
                        GuestVm.State.STOPPING -> stringResource(R.string.st_stopping)
                        else -> "…"
                    }
                    Text(phase, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    // fullscreen requested, but the overlay service is not enabled: tell how to bring
                    // the machine over the whole phone, and offer the plain window for this run
                    if (vm.settings.fullscreen && !overlayDeclined && !app.aemu.ui.OverlayService.enabled(this@VmActivity)) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.vm_overlay_missing), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            }) { Text(stringResource(R.string.vm_overlay_enable)) }
                            OutlinedButton(onClick = { overlayDeclined = true; unapplyOverlay() }) { Text(stringResource(R.string.vm_overlay_window)) }
                        }
                    }
                    if (vm.img.bootCount == 0 && state == GuestVm.State.BOOTING) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.first_boot_hint),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(logLines.lastOrNull()?.substringAfter(' ') ?: "", style = Mono, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
                // hide while booting keeps running: the small "booting…" pill then carries the feedback
                if (state != GuestVm.State.FAILED) {
                    IconButton(onClick = onHide, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                    }
                }
            }
        }
    }

    @Composable
    private fun LogPanel(onClose: () -> Unit, bare: Boolean = false) {
        var guestTab by remember { mutableStateOf(false) }
        val guestLines = remember { mutableStateListOf<String>() }
        // the kiosk's boot screen is the guest's own logcat: read its ring buffer while the machine is
        // still booting (and while the log panel's guest tab is open) a few times a second
        LaunchedEffect(bare, guestTab) {
            if (!bare && !guestTab) return@LaunchedEffect
            while (true) {
                // reading and byte-scanning the ring buffer off the main thread: doing it inline stuttered
                // the panel (and the whole overlay window) on every refresh
                val recs = withContext(Dispatchers.IO) {
                    GuestLog.tail(vm.paths.root, if (bare) 192 * 1024 else 128 * 1024)
                }.takeLast(if (bare) 400 else 300)
                guestLines.clear(); guestLines.addAll(recs.map { "${it.prio} ${it.tag}: ${it.msg}" })
                delay(if (bare) 600 else 1500)
            }
        }
        val list = if (bare || guestTab) guestLines else logLines
        Surface(Modifier.fillMaxSize(), color = if (bare) Color.Transparent else Color(0xF0101410),
            contentColor = Color(0xFFE2E3DD)) {
            // bare: no card, no insets, no padding — the log is the whole screen, cutout included, and
            // the guest's boot animation stays visible through the text
            Column(if (bare) Modifier.fillMaxSize()
                else Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
                if (!bare) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.log), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { copyLog(list.toList()) }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.log_copy)) }
                        IconButton(onClick = { pendingExport = list.toList(); exportLog.launch("aemu-${vm.img.id}-${if (guestTab) "logcat" else "host"}.log") }) {
                            Icon(Icons.Rounded.Download, stringResource(R.string.log_export))
                        }
                        FilledTonalIconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !guestTab, onClick = { guestTab = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                            Text(stringResource(R.string.host), maxLines = 1)
                        }
                        SegmentedButton(selected = guestTab, onClick = { guestTab = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                            Text(stringResource(R.string.guest_log), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val st = rememberLazyListState()
                LaunchedEffect(list.size) { if (list.isNotEmpty()) st.scrollToItem(list.size - 1) }
                if (bare && list.isEmpty()) {
                    Text("waiting for the guest log…", style = Mono, color = Color(0xFF62705F))
                    Spacer(Modifier.height(6.dp))
                }
                // no SelectionContainer here: its selection bookkeeping over hundreds of Text nodes is what
                // made the panel crawl on open; the header buttons still copy/export the whole log
                val line = if (bare) Mono.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f)) else Mono
                LazyColumn(state = st, modifier = Modifier.fillMaxSize(), userScrollEnabled = !bare) {
                    items(list) { Text(it, style = line, color = Color(0xFFCFE8CF), modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }

    // shell terminal into the guest: its lines survive closing and reopening the panel
    private val termLines = mutableStateListOf<String>()
    private val termHistory = ArrayList<String>()

    @Composable
    private fun TerminalPanel(onClose: () -> Unit) {
        var cmd by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var histPos by remember { mutableStateOf(-1) }
        fun run() {
            val c = cmd.trim()
            if (c.isEmpty() || busy) return
            cmd = ""; histPos = -1
            termHistory.remove(c); termHistory.add(c)
            if (c == "clear") { termLines.clear(); return }
            termLines.add("$ $c")
            busy = true
            thread {
                val out = runCatching { vm.guestShell(c, 180_000) }.getOrElse { it.toString() }
                runOnUiThread {
                    termLines.addAll(out.trimEnd('\n').lines())
                    while (termLines.size > 3000) termLines.removeAt(0)
                    busy = false
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = Color(0xF0101410), contentColor = Color(0xFFE2E3DD)) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.m_terminal), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (busy) LoadingIndicator(Modifier.size(32.dp))
                    IconButton(onClick = { copyLog(termLines.toList()) }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.log_copy)) }
                    FilledTonalIconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                }
                Spacer(Modifier.height(8.dp))
                val st = rememberLazyListState()
                LaunchedEffect(termLines.size) { if (termLines.isNotEmpty()) st.scrollToItem(termLines.size - 1) }
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
                        items(termLines) { Text(it, style = Mono, color = if (it.startsWith("$ ")) Color(0xFF9AD0FF) else Color(0xFFCFE8CF)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (termHistory.isNotEmpty()) {
                            histPos = if (histPos <= 0) termHistory.size - 1 else histPos - 1
                            cmd = termHistory[histPos]
                        }
                    }) { Icon(Icons.Rounded.History, null) }
                    OutlinedTextField(cmd, { cmd = it }, modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = Mono, placeholder = { Text("getprop ro.build.version.release", style = Mono) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                            autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { run() }))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = { run() }, enabled = !busy && cmd.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.m_send))
                    }
                }
            }
        }
    }

    private var pendingExport: List<String> = emptyList()
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val lines = pendingExport
        thread {
            val ok = runCatching { contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w -> lines.forEach { w.write(it); w.write("\n") } } }.isSuccess
            runOnUiThread { Toast.makeText(this, if (ok) R.string.log_exported else R.string.log_export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun copyLog(lines: List<String>) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("LineageEMU log", lines.joinToString("\n")))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_RECOVERY = "recovery"
        const val EXTRA_KIOSK = "app.aemu.lineage.kiosk"
        const val EXTRA_NO_CAMERA = "app.aemu.lineage.noCamera"
        const val EXTRA_NO_MIC = "app.aemu.lineage.noMic"
        const val EXTRA_NO_AUDIO = "app.aemu.lineage.noAudio"
        const val EXTRA_NO_NETWORK = "app.aemu.lineage.noNetwork"
        const val EXTRA_USE_PHONE_IDENTITY = "app.aemu.lineage.usePhoneIdentity"

        /** written while a kiosk machine lives in the :vm process, its image id inside */
        const val RUNNING_FLAG = "lineage-running"

        fun start(ctx: Context, id: String, recovery: Boolean = false) {
            ctx.startActivity(Intent(ctx, VmActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_RECOVERY, recovery)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** LineageEMU's start: the machine over the whole screen, the home screen's toggles as limits */
        fun startKiosk(ctx: Context, id: String, noCamera: Boolean, noMic: Boolean, noAudio: Boolean,
                       noNetwork: Boolean, usePhoneIdentity: Boolean) {
            ctx.startActivity(Intent(ctx, VmActivity::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_KIOSK, true)
                putExtra(EXTRA_NO_CAMERA, noCamera)
                putExtra(EXTRA_NO_MIC, noMic)
                putExtra(EXTRA_NO_AUDIO, noAudio)
                putExtra(EXTRA_NO_NETWORK, noNetwork)
                putExtra(EXTRA_USE_PHONE_IDENTITY, usePhoneIdentity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        /** the image id of a kiosk machine that is still alive (its heartbeat is fresh), or null */
        fun runningImage(ctx: Context): String? = runCatching {
            val f = File(ctx.filesDir, RUNNING_FLAG)
            if (!f.isFile) return@runCatching null
            if (System.currentTimeMillis() - f.lastModified() > 120_000) return@runCatching null
            f.readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

        /** whether the :vm process (the machine itself) is alive right now */
        fun vmProcessAlive(ctx: Context): Boolean = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.any { it.processName == ctx.packageName + ":vm" } == true
        }.getOrDefault(false)
    }
}

@Composable
internal fun navKeyLabel(k: String) = stringResource(when (k) {
    "back" -> R.string.back; "home" -> R.string.home; "recents" -> R.string.recents; "menu" -> R.string.menu
    "search" -> R.string.nav_search; "volup" -> R.string.vol_up; "voldown" -> R.string.vol_down
    "power" -> R.string.power; else -> R.string.nav_dpad
})
