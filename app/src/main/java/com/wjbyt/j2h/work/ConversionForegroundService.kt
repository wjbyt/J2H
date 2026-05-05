package com.wjbyt.j2h.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wjbyt.j2h.MainActivity
import com.wjbyt.j2h.R
import com.wjbyt.j2h.heif.HeicConverter
import com.wjbyt.j2h.storage.TreeUriStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConversionForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.wjbyt.j2h.START"
        const val ACTION_STOP = "com.wjbyt.j2h.STOP"
        const val ACTION_REPAIR = "com.wjbyt.j2h.REPAIR"
        private const val CHANNEL_ID = "conversion"
        private const val NOTIF_ID = 1001

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        data class State(
            val running: Boolean = false,
            val total: Int = 0,
            val done: Int = 0,
            val failed: Int = 0,
            val skipped: Int = 0,
            val current: String = "",
            val log: List<String> = emptyList()
        )

        fun appendLog(line: String) {
            val cur = _state.value
            val merged = (cur.log + line).takeLast(800)
            _state.value = cur.copy(log = merged)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWork()
                return START_NOT_STICKY
            }
            ACTION_REPAIR -> startRepair()
            else -> startWork()
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, progress: Int = 0, max: Int = 0): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ConversionForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop), stopIntent)
        if (max > 0) b.setProgress(max, progress, false)
        return b.build()
    }

    private fun goForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, progress, max))
    }

    private fun startRepair() {
        if (job?.isActive == true) return
        goForeground("准备修复元数据…")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2H::repair").apply {
            setReferenceCounted(false); acquire(30 * 60 * 1000L)
        }
        job = scope.launch {
            try { runRepair() }
            finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runRepair() {
        _state.value = State(running = true)
        val store = TreeUriStore(applicationContext)
        val uris = store.snapshot()
        if (uris.isEmpty()) { appendLog("没有目录可修复"); return }
        appendLog("扫描 ${uris.size} 个目录的 .heic 文件…")
        val files = mutableListOf<JpgScanner.Found>()
        for (u in uris) files += JpgScanner.scan(applicationContext, u)
        val heicFiles = files.filter { it.file.name?.lowercase()?.endsWith(".heic") == true }
        if (heicFiles.isEmpty()) {
            appendLog("未发现 HEIC 文件")
            _state.value = _state.value.copy(running = false, current = "")
            return
        }
        _state.value = _state.value.copy(total = heicFiles.size)
        appendLog("共发现 ${heicFiles.size} 张 HEIC，开始修复元数据…")

        var ok = 0; var skipped = 0; var failed = 0; var totalPatched = 0
        var mtimeFixed = 0
        for ((idx, f) in heicFiles.withIndex()) {
            if (!currentCoroutineContext().isActive) break
            val name = f.file.name ?: "?"
            _state.value = _state.value.copy(current = name)
            updateNotification("修复 ($idx/${heicFiles.size}): $name", idx, heicFiles.size)
            try {
                val r = com.wjbyt.j2h.heif.HeicMetadataRepair.repair(applicationContext, f.file)
                val byteFix: String = when (r) {
                    is com.wjbyt.j2h.heif.HeicMetadataRepair.Result.Ok -> {
                        ok++; totalPatched += r.patchedItems
                        "已修补 ${r.patchedItems} 个 infe flag"
                    }
                    is com.wjbyt.j2h.heif.HeicMetadataRepair.Result.Skipped -> {
                        skipped++
                        "container 已合规，无需字节修补"
                    }
                    is com.wjbyt.j2h.heif.HeicMetadataRepair.Result.Failed -> {
                        failed++
                        "字节修补失败：${r.reason}"
                    }
                }
                // Whether or not byte-flag patching changed anything, push the
                // HEIC's own EXIF DateTime into file mtime + MediaStore so that
                // vivo gallery shows the correct shoot time.
                val mtimeFix: String = try {
                    val snap = com.wjbyt.j2h.heif.MediaStoreSync.readSourceJpg(
                        applicationContext, f.file
                    )
                    if (snap.dateTakenMillis != null) {
                        val note = com.wjbyt.j2h.heif.MediaStoreSync.apply(
                            applicationContext, f.file.uri, snap
                        )
                        mtimeFixed++
                        "时间戳已同步至拍摄时间$note"
                    } else "无 EXIF DateTime 可同步"
                } catch (e: Throwable) {
                    "时间戳同步异常：${e.message?.take(40)}"
                }
                val tag = if (r is com.wjbyt.j2h.heif.HeicMetadataRepair.Result.Failed) "✗" else "✓"
                appendLog("$tag $name · $byteFix · $mtimeFix")
            } catch (e: Throwable) {
                failed++
                appendLog("✗ $name 异常：${e.javaClass.simpleName} ${e.message}")
            }
            _state.value = _state.value.copy(done = ok, failed = failed, skipped = skipped)
        }
        appendLog("==== 修复完成：处理 ${heicFiles.size} 张，" +
                  "字节修补 $ok（共改 $totalPatched 个 flag），无需修补 $skipped，" +
                  "失败 $failed，时间戳同步 $mtimeFixed ====")
        _state.value = _state.value.copy(running = false, current = "")
    }

    private fun startWork() {
        if (job?.isActive == true) return
        goForeground("准备开始…")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2H::convert").apply {
            setReferenceCounted(false); acquire(60 * 60 * 1000L)
        }

        job = scope.launch {
            try {
                runConversion()
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopWork() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runConversion() {
        _state.value = State(running = true)
        val store = TreeUriStore(applicationContext)
        val uris: List<Uri> = store.snapshot()
        if (uris.isEmpty()) {
            appendLog("没有目录可转换")
            return
        }

        appendLog("扫描 ${uris.size} 个目录…")
        val files = mutableListOf<JpgScanner.Found>()
        for (u in uris) {
            files += JpgScanner.scan(applicationContext, u)
        }
        if (files.isEmpty()) {
            appendLog("未发现可转换的图片")
            return
        }
        val jpgCount = files.count {
            val n = it.file.name?.lowercase() ?: ""
            n.endsWith(".jpg") || n.endsWith(".jpeg")
        }
        val dngCount = files.count { (it.file.name?.lowercase() ?: "").endsWith(".dng") }
        val videoCount = files.count {
            val n = it.file.name?.lowercase() ?: ""
            n.endsWith(".mp4") || n.endsWith(".mov")
        }
        val heicCount = files.count { (it.file.name?.lowercase() ?: "").endsWith(".heic") }
        // Skip already-HEIC files in the conversion flow — they belong to the
        // repair flow, not here. Without this filter HeicConverter would try
        // to re-encode them as JPEG-source HEICs and fail.
        val toConvert = files.filter {
            (it.file.name?.lowercase() ?: "").let { n -> !n.endsWith(".heic") }
        }
        _state.value = _state.value.copy(total = toConvert.size)
        val heicNote = if (heicCount > 0) ", HEIC: $heicCount 已跳过—改用『修复』" else ""
        appendLog("共发现 ${files.size} 个文件（JPG: $jpgCount, DNG: $dngCount, 视频: $videoCount$heicNote），开始转换…")
        if (toConvert.isEmpty()) {
            appendLog("无可转换文件（HEIC 文件请使用『修复』按钮）")
            _state.value = _state.value.copy(running = false, current = "")
            return
        }

        val quality = store.qualitySnapshot()
        val videoPct = store.videoBitratePctSnapshot()
        appendLog("HEIC 质量 = $quality · 视频画质 = $videoPct (CQ)")
        appendLog("编码：JPG → 8bit (HeifWriter) · DNG → 10bit (MediaCodec Main10) · 视频 → HEVC CQ")
        if (!com.wjbyt.j2h.heif.TenBitEncoder.isAvailable()) {
            appendLog("⚠ 10-bit native lib 不可用，DNG 会失败")
        }
        val converter = HeicConverter(applicationContext, quality = quality)
        var done = 0; var failed = 0; var skipped = 0
        for ((idx, f) in toConvert.withIndex()) {
            if (!currentCoroutineContext().isActive) break
            val name = f.file.name ?: "?"
            val lower = name.lowercase()
            val isVideo = (lower.endsWith(".mp4") || lower.endsWith(".mov")) &&
                          !lower.endsWith(".compressed.mp4") &&
                          !lower.endsWith(".av1.mp4") &&
                          !lower.endsWith(".j2h.mp4")
            val tag = when {
                isVideo -> "[VID→HEVC ]"
                lower.endsWith(".dng") -> "[DNG→10bit]"
                else -> "[JPG→8bit ]"
            }
            _state.value = _state.value.copy(current = "$tag $name")
            updateNotification("正在转换 ($idx/${toConvert.size}): $name", idx, toConvert.size)
            try {
                if (isVideo) {
                    when (val r = com.wjbyt.j2h.video.VideoTranscoder.transcode(
                        applicationContext, f.file, f.parent,
                        qualityPct = videoPct
                    )) {
                        is com.wjbyt.j2h.video.VideoTranscoder.Result.Ok -> {
                            done++
                            // When the output replaces the source in-place
                            // (same filename), don't print "foo → foo".
                            val title = if (name == r.outName) name
                                        else "$name → ${r.outName}"
                            appendLog("✓ $tag $title ${r.bytesIn / 1024 / 1024}MB→${r.bytesOut / 1024 / 1024}MB · ${r.note}")
                        }
                        is com.wjbyt.j2h.video.VideoTranscoder.Result.Skipped -> {
                            skipped++
                            appendLog("· $tag $name 跳过：${r.reason}")
                        }
                        is com.wjbyt.j2h.video.VideoTranscoder.Result.Failed -> {
                            failed++
                            appendLog("✗ $tag $name 失败：${r.reason}（已保留原文件）")
                        }
                    }
                } else when (val r = converter.convert(f.file, f.parent)) {
                    is HeicConverter.Result.Ok -> {
                        done++
                        appendLog("✓ $tag $name → ${r.outName} ${r.bytesIn / 1024}KB→${r.bytesOut / 1024}KB · ${r.encoder}")
                    }
                    is HeicConverter.Result.Skipped -> {
                        skipped++
                        appendLog("· $tag $name 跳过：${r.reason}")
                    }
                    is HeicConverter.Result.Failed -> {
                        failed++
                        appendLog("✗ $tag $name 失败：${r.reason}（已保留原文件）")
                    }
                }
            } catch (e: Throwable) {
                failed++
                val cls = e.javaClass.simpleName
                val msg = e.message ?: "(no message)"
                appendLog("✗ $name 异常 [$cls]：$msg（已保留原文件）")
                // First few stack frames help pinpoint where the throw happened.
                e.stackTrace.take(6).forEach { appendLog("    at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})") }
                e.cause?.let { c ->
                    appendLog("  caused by [${c.javaClass.simpleName}]: ${c.message ?: "(no message)"}")
                    c.stackTrace.take(4).forEach { appendLog("    at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})") }
                }
            }
            _state.value = _state.value.copy(
                done = done, failed = failed, skipped = skipped
            )
        }
        updateNotification("完成：成功 $done · 失败 $failed · 跳过 $skipped", toConvert.size, toConvert.size)
        appendLog("==== 完成：成功 $done，失败 $failed，跳过 $skipped ====")
        _state.value = _state.value.copy(running = false, current = "")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
