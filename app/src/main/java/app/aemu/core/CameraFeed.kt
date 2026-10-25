package app.aemu.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.net.LocalSocket
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The phone's cameras for the guest. The emulator's camera HAL (native/camerahal) connects to /dev/socket/aemu_cam
 * and sends "open <0 back | 1 front>", "start", "stop", "shot", "close"; frames go back as
 *     int32 type, width, height, length + data — type 1: RGBA_8888 then NV21 (preview), type 2: JPEG (picture).
 * Frames are 640×480 straight from the sensor (the HAL reports the sensor orientation, the guest rotates).
 */
class CameraFeed(private val ctx: Context, private val paths: VmPaths, private val log: (String) -> Unit) {
    private val server = UnixServer(paths.socket("aemu_cam"), "camera") { c -> Session(c).run() }
    @Volatile var onPermissionNeeded: (() -> Unit)? = null
    /** the session that has (or is opening) the phone's camera: torch commands arrive on a separate connection */
    @Volatile private var active: Session? = null
    @Volatile private var lastCameraId: String? = null
    /** serializes camera handovers: a front/back switch must wait for the old device to close */
    private val cameraLock = Any()

    fun start(): Boolean {
        val ok = server.start(log)
        val perm = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        log("camera: " + (if (ok) "serving ${paths.socket("aemu_cam")}" else "socket not started") +
            ", host permission " + (if (perm) "granted" else "MISSING"))
        return ok
    }
    fun stop() = server.stop()

    /** The guest camera's torch switch. It comes on its own socket connection, so the open preview session has to
     *  carry it: the camera manager refuses setTorchMode with CAMERA_IN_USE while that session holds the device. */
    fun torch(front: Boolean, on: Boolean): Boolean {
        if (front) return false
        active?.let { if (it.requestTorch(on)) return true }
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log("camera: torch needs the camera permission")
            return false
        }
        val cm = ctx.getSystemService(CameraManager::class.java)
        val want = CameraCharacteristics.LENS_FACING_BACK
        val flash = { id: String -> cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        val id = lastCameraId?.takeIf { flash(it) }
            ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want && flash(it) }
            ?: cm.cameraIdList.firstOrNull { flash(it) }
        if (id == null) { log("camera: the phone has no flash unit"); return false }
        // a just-closed preview may still hold the device for a moment: give it a few tries
        var lastError: Throwable? = null
        for (attempt in 0 until 12) {
            val r = runCatching { cm.setTorchMode(id, on) }
            if (r.isSuccess) return true
            lastError = r.exceptionOrNull()
            if ((lastError as? CameraAccessException)?.reason != CameraAccessException.CAMERA_IN_USE) break
            Thread.sleep(100)
        }
        log("camera: torch failed: ${lastError?.message}")
        return false
    }

    private inner class Session(private val sock: LocalSocket) {
        private val out: OutputStream = sock.outputStream
        private val thread = HandlerThread("guest-camera").apply { start() }
        private val handler = Handler(thread.looper)
        @Volatile private var device: CameraDevice? = null
        @Volatile private var reader: ImageReader? = null
        @Volatile private var streaming = false
        @Volatile private var shot = false
        @Volatile private var shotRotation = 0
        @Volatile private var opening = false
        @Volatile private var capture: CameraCaptureSession? = null
        @Volatile private var preview: CaptureRequest.Builder? = null
        @Volatile private var torchWanted = false
        @Volatile private var sensorRect: Rect? = null
        @Volatile private var hostZoomMax = 1f
        @Volatile private var hostZoomRatio = false
        @Volatile private var zoomL = 0f
        @Volatile private var zoomT = 0f
        @Volatile private var zoomR = 1f
        @Volatile private var zoomB = 1f
        @Volatile private var closeLatch: CountDownLatch? = null
        @Volatile private var released = false
        private var openRetries = 0
        private val nv21 = ByteArray(W * H * 3 / 2)
        private val rgba = ByteArray(W * H * 4)
        private var frames = 0
        private var firstNs = 0L
        private var convNs = 0L
        private var sendNs = 0L

        fun run() {
            log("camera: guest connected")
            try {
                val r = sock.inputStream.bufferedReader()
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.trim().split(' ')
                    when (p[0]) {
                        "open" -> { log("camera: guest asks for ${if (p.getOrNull(1) == "1") "front" else "back"}"); open(p.getOrNull(1) == "1") }
                        "start" -> { log("camera: preview start"); streaming = true }
                        "stop" -> { streaming = false }
                        "shot" -> {
                            shotRotation = p.getOrNull(1)?.toIntOrNull() ?: 0
                            shot = true
                        }
                        "torch" -> {
                            val front = p.getOrNull(1) == "1"
                            val on = p.getOrNull(2) == "1"
                            val ok = torch(front, on)
                            log("camera: torch ${if (front) "front" else "back"} ${if (on) "on" else "off"}: ${if (ok) "ok" else "failed"}")
                        }
                        "zoom" -> {
                            // the guest HAL sends the crop in 0..10000 of its own sensor
                            val l = p.getOrNull(2)?.toIntOrNull()
                            val t = p.getOrNull(3)?.toIntOrNull()
                            val r = p.getOrNull(4)?.toIntOrNull()
                            val b = p.getOrNull(5)?.toIntOrNull()
                            if (l != null && t != null && r != null && b != null) {
                                zoomL = l / 10000f; zoomT = t / 10000f
                                zoomR = r / 10000f; zoomB = b / 10000f
                                applyZoom()
                            }
                        }
                        "close" -> break
                    }
                }
            } finally {
                log("camera: guest disconnected")
                releaseCamera(1_200)
                streaming = false
                torchWanted = false
                opening = false
                thread.quitSafely()
                runCatching { sock.close() }
            }
        }

        fun requestTorch(on: Boolean): Boolean {
            // the preview may not be configured yet: onConfigured applies the wanted state
            torchWanted = on
            return applyTorch()
        }

        /** Close the device and wait for the camera service to release it: a second camera (front) or a
         *  torch command cannot open while the old one is still counted as a user. */
        fun releaseCamera(waitMs: Long = 1_200) {
            released = true
            streaming = false
            shot = false
            torchWanted = false
            val d = device
            device = null
            runCatching { capture?.stopRepeating(); capture?.close() }
            capture = null
            preview = null
            runCatching { reader?.close() }
            reader = null
            sensorRect = null
            if (d != null) {
                val latch = CountDownLatch(1)
                closeLatch = latch
                runCatching { d.close() }
                runCatching { latch.await(waitMs, TimeUnit.MILLISECONDS) }
                closeLatch = null
            }
            if (active === this) active = null
        }

        private fun applyZoom() {
            val b = preview ?: return
            val span = (zoomR - zoomL).coerceIn(0.01f, 1f)
            val ratio = (1f / span).coerceIn(1f, hostZoomMax.coerceAtLeast(1f))
            // Prefer the camera2 zoom ratio: the phone's HAL can use all of its lenses (the crop
            // region alone is capped by the logical camera on Samsung). Fall back to a sensor crop.
            val usedRatio = hostZoomRatio && runCatching {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
                true
            }.getOrDefault(false)
            if (!usedRatio) {
                val s = sensorRect ?: return
                val l = s.left + (zoomL * s.width()).roundToInt().coerceIn(0, s.width() - 1)
                val t = s.top + (zoomT * s.height()).roundToInt().coerceIn(0, s.height() - 1)
                val r = s.left + (zoomR * s.width()).roundToInt().coerceIn(l + 1, s.width())
                val btm = s.top + (zoomB * s.height()).roundToInt().coerceIn(t + 1, s.height())
                runCatching { b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, r, btm)) }
            }
            capture?.let { runCatching { it.setRepeatingRequest(b.build(), null, handler) } }
        }

        private fun applyTorch(): Boolean {
            val b = preview ?: return false
            val s = capture ?: return false
            return runCatching {
                // some camera HALs only drive the LED when AE is on for the preview request
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.FLASH_MODE,
                    if (torchWanted) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                s.setRepeatingRequest(b.build(), null, handler)
                true
            }.getOrElse { log("camera: torch in preview failed: ${it.message}"); false }
        }

        @SuppressLint("MissingPermission")
        private fun open(front: Boolean) {
            if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                log("camera: the guest wants the camera, asking for permission")
                onPermissionNeeded?.invoke()
                // the guest's open already succeeded: wait for the answer, the preview starts when it is granted
                var waited = 0
                while (waited < 120 && ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    Thread.sleep(500); waited++
                }
                if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            }
            val cm = ctx.getSystemService(CameraManager::class.java)
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: cm.cameraIdList.firstOrNull() ?: run { log("camera: the phone has no camera"); return }
            lastCameraId = id
            val ir: ImageReader
            synchronized(cameraLock) {
                // a front/back switch: release whichever session owns the camera and wait for the service
                active?.takeIf { it !== this }?.releaseCamera()
                releaseCamera()
                released = false
                active = this
                sensorRect = runCatching {
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                }.getOrNull()
                runCatching {
                    val c = cm.getCameraCharacteristics(id)
                    hostZoomMax = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                    val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (range != null && range.upper > 1f) {
                        hostZoomRatio = true
                        hostZoomMax = minOf(hostZoomMax, range.upper)
                    }
                }
                ir = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2)
                opening = true
            }
            reader = ir
            ir.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { frame(it) } }, handler)
            runCatching {
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        opening = false
                        openRetries = 0
                        if (released) { runCatching { d.close() }; return }
                        device = d
                        // the guest may have closed the camera already (mediaserver only probes it at start)
                        runCatching {
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(ir.surface) }
                            // a steady 30 frames a second: the default range lets the exposure halve the rate in a dim room
                            cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                ?.filter { it.upper == 30 }?.maxByOrNull { it.lower }
                                ?.let { req.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            preview = req
                            applyZoom()
                            applyTorch()    // the guest may have asked for the torch while the camera was opening
                            @Suppress("DEPRECATION")
                            d.createCaptureSession(listOf(ir.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (released) { runCatching { s.close() }; return }
                                    capture = s
                                    applyZoom()
                                    applyTorch()
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { log("camera: session not configured") }
                            }, handler)
                        }.onFailure { runCatching { d.close() }; opening = false; return }
                        log("camera: ${if (front) "front" else "back"} camera opened for the guest")
                    }
                    override fun onClosed(d: CameraDevice) {
                        if (device === d) device = null
                        closeLatch?.countDown()
                    }
                    override fun onDisconnected(d: CameraDevice) { opening = false; d.close() }
                    override fun onError(d: CameraDevice, e: Int) {
                        opening = false
                        log("camera: error $e")
                        d.close()
                        if (e == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                            e == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) retryOpen(front)
                    }
                }, handler)
            }.onFailure {
                opening = false
                if (active === this@Session) active = null
                runCatching { ir.close() }
                val retry = it is CameraAccessException &&
                    (it.reason == CameraAccessException.CAMERA_IN_USE || it.reason == CameraAccessException.MAX_CAMERAS_IN_USE)
                log("camera: open failed: ${it.message}")
                if (retry) retryOpen(front)
            }
        }

        /** A camera switch can race the old device's close: wait and try once more. */
        private fun retryOpen(front: Boolean) {
            if (released || openRetries >= 6) return
            openRetries++
            log("camera: retrying ${if (front) "front" else "back"} camera ($openRetries)")
            Thread({
                Thread.sleep(400)
                if (!released) open(front)
            }, "camera-retry").start()
        }

        /** NV21 rotated clockwise by the JPEG orientation the guest's camera service requested. */
        private fun rotatedNv21(src: ByteArray, w: Int, h: Int, degrees: Int): Triple<ByteArray, Int, Int> {
            val d = ((degrees % 360) + 360) % 360
            if (d == 0) return Triple(src, w, h)
            val outW = if (d == 180) w else h
            val outH = if (d == 180) h else w
            val out = ByteArray(w * h * 3 / 2)
            val ySize = w * h
            for (y in 0 until h) for (x in 0 until w) {
                val (ox, oy) = when (d) {
                    90 -> (h - 1 - y) to x
                    180 -> (w - 1 - x) to (h - 1 - y)
                    else -> y to (w - 1 - x)
                }
                out[oy * outW + ox] = src[y * w + x]
            }
            val cw = w / 2
            val ch = h / 2
            val outCW = if (d == 180) cw else ch
            for (cy in 0 until ch) for (cx in 0 until cw) {
                val (ox, oy) = when (d) {
                    90 -> (ch - 1 - cy) to cx
                    180 -> (cw - 1 - cx) to (ch - 1 - cy)
                    else -> cy to (cw - 1 - cx)
                }
                val si = ySize + (cy * cw + cx) * 2
                val di = ySize + (oy * outCW + ox) * 2
                out[di] = src[si]
                out[di + 1] = src[si + 1]
            }
            return Triple(out, outW, outH)
        }

        private fun frame(img: Image) {
            if (!streaming && !shot) return
            val t0 = System.nanoTime()
            toNv21(img)
            runCatching {
                if (shot) {
                    shot = false
                    val (data, jw, jh) = rotatedNv21(nv21, W, H, shotRotation)
                    val jpg = ByteArrayOutputStream(200_000)
                    YuvImage(data, ImageFormat.NV21, jw, jh, null).compressToJpeg(Rect(0, 0, jw, jh), 92, jpg)
                    streaming = false   // a picture stops the preview until the guest starts it again (its "start"
                    send(2, jpg.toByteArray(), jpg.size())     // may come right after the picture, so this goes first)
                    return
                }
                toRgba()
                val t1 = System.nanoTime()
                send(1, rgba, rgba.size, nv21)
                convNs += t1 - t0; sendNs += System.nanoTime() - t1
                if (++frames == 30) {
                    // the host's own picture brightness: a dark guest preview with a dark host frame is the scene, not a bug
                    var sum = 0L; var i = 0; while (i < W * H) { sum += nv21[i].toInt() and 0xff; i += 97 }
                    log("camera: 29 frames in ${(t1 - firstNs) / 1_000_000} ms, conversion ${convNs / 30_000} us, sending ${sendNs / 30_000} us a frame, host luma ${sum * 97 / (W * H)}")
                }
                if (frames == 1) firstNs = t1
            }.onFailure { streaming = false }
        }

        private fun send(type: Int, a: ByteArray, n: Int, b: ByteArray? = null) {
            val h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(type).putInt(W).putInt(H).putInt(n + (b?.size ?: 0))
            synchronized(out) { out.write(h.array()); out.write(a, 0, n); if (b != null) out.write(b); out.flush() }
        }

        /** YUV_420_888 planes (any row/pixel stride) → NV21 */
        private fun toNv21(img: Image) {
            val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
            val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
            for (row in 0 until H) {
                yb.position(row * y.rowStride)
                yb.get(nv21, row * W, minOf(W, yb.remaining()))
            }
            var o = W * H
            if (u.pixelStride == 2 && v.pixelStride == 2) {
                // the usual layout: the V plane's buffer is V,U,V,U… already (one byte short in its last row)
                for (row in 0 until H / 2) {
                    vb.position(row * v.rowStride)
                    val n = minOf(W, vb.remaining())
                    vb.get(nv21, o, n)
                    if (n < W) nv21[o + W - 1] = ub.get(row * u.rowStride + W - 2)
                    o += W
                }
                return
            }
            for (row in 0 until H / 2) {
                val up = row * u.rowStride; val vp = row * v.rowStride
                for (col in 0 until W / 2) {
                    nv21[o++] = vb.get(vp + col * v.pixelStride)
                    nv21[o++] = ub.get(up + col * u.pixelStride)
                }
            }
        }

        private fun toRgba() {
            var o = 0
            for (row in 0 until H) {
                val uv = W * H + (row shr 1) * W
                val yr = row * W
                for (col in 0 until W) {
                    val yy = (nv21[yr + col].toInt() and 0xff) - 16
                    val p = uv + (col and 1.inv())
                    val v = (nv21[p].toInt() and 0xff) - 128
                    val u = (nv21[p + 1].toInt() and 0xff) - 128
                    val c = 1192 * (if (yy < 0) 0 else yy)
                    var r = (c + 1634 * v) shr 10
                    var g = (c - 833 * v - 400 * u) shr 10
                    var b = (c + 2066 * u) shr 10
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    rgba[o++] = r.toByte(); rgba[o++] = g.toByte(); rgba[o++] = b.toByte(); rgba[o++] = -1
                }
            }
        }
    }

    companion object {
        const val W = 640
        const val H = 480
    }
}
