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
            val merged = (cur.log + line).takeLast(200)
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
            appendLog("未发现 JPG 文件")
            return
        }
        _state.value = _state.value.copy(total = files.size)
        appendLog("共发现 ${files.size} 张 JPG，开始转换…")

        val quality = store.qualitySnapshot()
        appendLog("HEIC 质量 = $quality")
        val converter = HeicConverter(applicationContext, quality = quality)
        var done = 0; var failed = 0; var skipped = 0
        for ((idx, f) in files.withIndex()) {
            if (!currentCoroutineContext().isActive) break
            val name = f.file.name ?: "?"
            _state.value = _state.value.copy(current = name)
            updateNotification("正在转换 ($idx/${files.size}): $name", idx, files.size)
            try {
                when (val r = converter.convert(f.file, f.parent)) {
                    is HeicConverter.Result.Ok -> {
                        done++
                        appendLog("✓ $name → ${r.outName} (${r.bytesIn / 1024}KB → ${r.bytesOut / 1024}KB)")
                    }
                    is HeicConverter.Result.Skipped -> {
                        skipped++
                        appendLog("· $name 跳过：${r.reason}")
                    }
                    is HeicConverter.Result.Failed -> {
                        failed++
                        appendLog("✗ $name 失败：${r.reason}（已保留原文件）")
                    }
                }
            } catch (e: Exception) {
                failed++
                appendLog("✗ $name 异常：${e.message}（已保留原文件）")
            }
            _state.value = _state.value.copy(
                done = done, failed = failed, skipped = skipped
            )
        }
        updateNotification("完成：成功 $done · 失败 $failed · 跳过 $skipped", files.size, files.size)
        appendLog("==== 完成：成功 $done，失败 $failed，跳过 $skipped ====")
        _state.value = _state.value.copy(running = false, current = "")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
