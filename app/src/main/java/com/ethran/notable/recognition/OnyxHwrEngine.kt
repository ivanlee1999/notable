package com.ethran.notable.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.DeviceCompat
import com.onyx.android.sdk.hwr.service.HWRInputArgs
import com.onyx.android.sdk.hwr.service.HWROutputArgs
import com.onyx.android.sdk.hwr.service.HWROutputCallback
import com.onyx.android.sdk.hwr.service.IHWRService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val log = ShipBook.getLogger("OnyxHwrEngine")

/** The recognizer this engine wraps, as recorded in `pagetext` documents. */
const val ENGINE_MYSCRIPT = "myscript"

/**
 * Handwriting recognition through the BOOX firmware's own MyScript engine, reached over AIDL.
 *
 * The service is stateful — `init` configures one recognizer that every subsequent
 * `batchRecognize` uses — so calls are serialized under [lock] and the recognizer is rebuilt
 * whenever the requested language or view size changes.
 *
 * Ported from github.com/jdkruzr/aragonite (MIT), which worked out the wire format.
 */
@Singleton
class OnyxHwrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Mutex()

    @Volatile
    private var service: IHWRService? = null

    @Volatile
    private var bound = false

    @Volatile
    private var connectLatch = CountDownLatch(1)

    /** Non-null once a recognizer is live; holds the configuration it was built with. */
    @Volatile
    private var recognizer: RecognizerConfig? = null

    private data class RecognizerConfig(
        val language: String,
        val viewWidth: Float,
        val viewHeight: Float,
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IHWRService.Stub.asInterface(binder)
            bound = true
            log.i("HWR service connected")
            connectLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            recognizer = null
            log.w("HWR service disconnected")
        }
    }

    /** True on hardware that can recognize at all. Everywhere else this engine is inert. */
    val isAvailable: Boolean get() = DeviceCompat.isOnyxDevice

    /**
     * Recognized text for [strokes], or null when recognition could not run — a non-BOOX device,
     * a service that would not bind, or a recognizer that timed out. Null means "unknown", never
     * "empty page": an empty page recognizes as the empty string.
     *
     * Coordinates are page-space, and MyScript needs them to fall inside the view it was told
     * about, so callers pass strokes already translated into a [viewWidth] x [viewHeight] box.
     */
    suspend fun recognize(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String,
    ): String? {
        if (!isAvailable) return null
        if (strokes.isEmpty()) return ""

        return lock.withLock {
            val svc = connect() ?: return@withLock null
            if (!ensureRecognizer(svc, language, viewWidth, viewHeight)) return@withLock null
            batchRecognize(svc, strokes, viewWidth, viewHeight, language)
        }
    }

    /**
     * Drops the binder. Recognition is bursty — a page or two on exit, then nothing for hours —
     * and there is no reason to hold the firmware's recognizer open across that idle time.
     */
    suspend fun release() = lock.withLock {
        if (!bound) return@withLock
        try {
            service?.closeRecognizer()
        } catch (e: Exception) {
            log.w("closeRecognizer failed: ${e.message}")
        }
        try {
            context.applicationContext.unbindService(connection)
        } catch (e: Exception) {
            log.w("unbindService failed: ${e.message}")
        }
        bound = false
        service = null
        recognizer = null
    }

    // --- Service lifecycle ---

    private suspend fun connect(timeoutMs: Long = 2_000): IHWRService? {
        service?.let { if (bound) return it }

        connectLatch = CountDownLatch(1)
        val intent = Intent().apply {
            component = ComponentName(HWR_SERVICE_PACKAGE, HWR_SERVICE_CLASS)
        }
        val started = try {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            log.w("Failed to bind HWR service: ${e.message}")
            return null
        }
        if (!started) {
            log.w("HWR service refused the bind — firmware without ksync?")
            return null
        }

        val connected = withContext(Dispatchers.IO) {
            connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
        if (!connected) log.w("HWR service did not connect within ${timeoutMs}ms")
        return service
    }

    /** Builds (or rebuilds) the recognizer when the wanted configuration differs from the live one. */
    private suspend fun ensureRecognizer(
        svc: IHWRService,
        language: String,
        viewWidth: Float,
        viewHeight: Float,
    ): Boolean {
        val wanted = RecognizerConfig(language, viewWidth, viewHeight)
        if (recognizer == wanted) return true

        val args = HWRInputArgs().apply {
            lang = language
            contentType = "Text"
            recognizerType = RECOGNIZER_ON_SCREEN
            this.viewWidth = viewWidth
            this.viewHeight = viewHeight
            isTextEnable = true
        }

        val activated = withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                svc.init(args, /* forceReinit = */ true, object : HWROutputCallback.Stub() {
                    override fun read(out: HWROutputArgs?) {
                        cont.resume(out?.recognizerActivated == true)
                    }
                })
            }
        }

        recognizer = if (activated == true) wanted else null
        if (activated == null) log.e("HWR init timed out after ${INIT_TIMEOUT_MS}ms")
        else if (!activated) log.e("HWR init did not activate a recognizer for '$language'")
        return activated == true
    }

    private suspend fun batchRecognize(
        svc: IHWRService,
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String,
    ): String? {
        val proto = withContext(Dispatchers.Default) {
            encodeInput(strokes, viewWidth, viewHeight, language)
        }
        val pfd = memoryFileDescriptor(proto) ?: return null

        return try {
            val text = withTimeoutOrNull(RECOGNIZE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    svc.batchRecognize(pfd, object : HWROutputCallback.Stub() {
                        override fun read(out: HWROutputArgs?) {
                            try {
                                cont.resume(readResult(out))
                            } catch (e: Exception) {
                                cont.resumeWithException(e)
                            }
                        }
                    })
                }
            }
            if (text == null) log.e("HWR recognition timed out after ${RECOGNIZE_TIMEOUT_MS}ms")
            text
        } finally {
            pfd.close()
        }
    }

    /**
     * The service answers either inline — which it only does for errors — or through a second
     * descriptor holding the result JSON.
     */
    private fun readResult(out: HWROutputArgs?): String {
        val inlineError = out?.hwrResult
        if (!inlineError.isNullOrBlank()) {
            log.e("HWR error: ${inlineError.take(300)}")
            return ""
        }
        val resultPfd = out?.pfd ?: run {
            log.w("HWR returned neither a result descriptor nor an error")
            return ""
        }
        // AutoCloseInputStream closes the descriptor with the stream.
        val json = ParcelFileDescriptor.AutoCloseInputStream(resultPfd)
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return parseLabel(json)
    }

    private fun parseLabel(json: String): String = try {
        val obj = JSONObject(json)
        if (obj.has("exception")) {
            val exception = obj.optJSONObject("exception")
            val message = exception?.optJSONObject("cause")?.optString("message")
                ?: exception?.optString("message")
                ?: "unknown"
            log.e("HWR error response: $message")
            ""
        } else {
            obj.optJSONObject("result")?.optString("label", "") ?: obj.optString("label", "")
        }
    } catch (e: Exception) {
        log.w("Unparseable HWR result: ${e.message}")
        ""
    }

    // --- Wire format ---
    //
    // The service reads an HWRInputProto protobuf from a shared-memory descriptor. Encoding it
    // by hand keeps a protobuf dependency (and a .proto whose field numbers we would have to
    // guess anyway) out of the build.

    /**
     * HWRInputProto: 1 lang, 2 contentType, 4 recognizerType, 5 viewWidth, 6 viewHeight,
     * 10 recognizeText, 15 repeated pointer events.
     */
    private fun encodeInput(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        writeTag(out, 1, WIRE_LENGTH_DELIMITED); writeString(out, language)
        writeTag(out, 2, WIRE_LENGTH_DELIMITED); writeString(out, "Text")
        writeTag(out, 4, WIRE_LENGTH_DELIMITED); writeString(out, RECOGNIZER_ON_SCREEN)
        writeTag(out, 5, WIRE_FIXED32); writeFixed32(out, viewWidth)
        writeTag(out, 6, WIRE_FIXED32); writeFixed32(out, viewHeight)
        writeTag(out, 10, WIRE_VARINT); writeVarint(out, 1)

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val strokeEpoch = stroke.createdAt.time
            val last = stroke.points.size - 1
            for ((i, point) in stroke.points.withIndex()) {
                val eventType = when (i) {
                    0 -> EVENT_DOWN
                    last -> EVENT_UP
                    else -> EVENT_MOVE
                }
                // dt is milliseconds from the stroke's first point. Strokes that predate its
                // capture get an even 10ms spacing, which MyScript only uses for stroke ordering.
                val timestamp = strokeEpoch + (point.dt?.toLong() ?: (i * 10L))
                writeTag(out, 15, WIRE_LENGTH_DELIMITED)
                writeBytes(
                    out,
                    encodePointer(
                        x = point.x,
                        y = point.y,
                        timestamp = timestamp,
                        pressure = point.pressure ?: DEFAULT_PRESSURE,
                        eventType = eventType,
                    )
                )
            }
        }
        return out.toByteArray()
    }

    /**
     * HWRPointerProto: 1 x, 2 y, 3 t (sint64), 4 pressure, 5 pointerId (sint32), 6 eventType,
     * 7 pointerType.
     */
    private fun encodePointer(
        x: Float,
        y: Float,
        timestamp: Long,
        pressure: Float,
        eventType: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, 1, WIRE_FIXED32); writeFixed32(out, x)
        writeTag(out, 2, WIRE_FIXED32); writeFixed32(out, y)
        writeTag(out, 3, WIRE_VARINT); writeVarint(out, (timestamp shl 1) xor (timestamp shr 63))
        writeTag(out, 4, WIRE_FIXED32); writeFixed32(out, pressure)
        writeTag(out, 5, WIRE_VARINT); writeVarint(out, 0)          // pointerId 0, zigzagged
        writeTag(out, 6, WIRE_VARINT); writeVarint(out, eventType.toLong())
        writeTag(out, 7, WIRE_VARINT); writeVarint(out, POINTER_PEN.toLong())
        return out.toByteArray()
    }

    /**
     * Shared memory holding [data], as a descriptor the service can read.
     *
     * `MemoryFile.getFileDescriptor` is hidden API; Onyx's own MemoryFileUtils reaches it the
     * same way, and the app already carries hiddenapibypass for the pen SDK.
     */
    private fun memoryFileDescriptor(data: ByteArray): ParcelFileDescriptor? = try {
        val file = MemoryFile("hwr_input", data.size)
        file.writeBytes(data, 0, 0, data.size)
        val getFileDescriptor = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
        getFileDescriptor.isAccessible = true
        val fd = getFileDescriptor.invoke(file) as FileDescriptor
        ParcelFileDescriptor.dup(fd).also { file.close() }
    } catch (e: Exception) {
        log.e("Could not hand the recognizer its input: ${e.message}")
        null
    }

    companion object {
        private const val HWR_SERVICE_PACKAGE = "com.onyx.android.ksync"
        private const val HWR_SERVICE_CLASS = "com.onyx.android.ksync.service.KHwrService"

        /** MyScript's on-screen recognizer: the one tuned for freehand writing. */
        private const val RECOGNIZER_ON_SCREEN = "MS_ON_SCREEN"

        private const val INIT_TIMEOUT_MS = 5_000L
        private const val RECOGNIZE_TIMEOUT_MS = 10_000L

        private const val DEFAULT_PRESSURE = 0.5f

        private const val WIRE_VARINT = 0
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val WIRE_FIXED32 = 5

        private const val EVENT_DOWN = 0
        private const val EVENT_MOVE = 1
        private const val EVENT_UP = 2
        private const val POINTER_PEN = 0

        internal fun writeTag(out: ByteArrayOutputStream, field: Int, wireType: Int) =
            writeVarint(out, ((field shl 3) or wireType).toLong())

        internal fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write((v.toInt() and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v.toInt() and 0x7F)
        }

        internal fun writeFixed32(out: ByteArrayOutputStream, value: Float) {
            val bits = java.lang.Float.floatToIntBits(value)
            out.write(bits and 0xFF)
            out.write((bits shr 8) and 0xFF)
            out.write((bits shr 16) and 0xFF)
            out.write((bits shr 24) and 0xFF)
        }

        internal fun writeString(out: ByteArrayOutputStream, value: String) =
            writeBytes(out, value.toByteArray(Charsets.UTF_8))

        internal fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
            writeVarint(out, bytes.size.toLong())
            out.write(bytes)
        }
    }
}
