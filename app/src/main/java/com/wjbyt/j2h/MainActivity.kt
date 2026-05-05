package com.wjbyt.j2h

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.wjbyt.j2h.ui.HomeScreen
import com.wjbyt.j2h.work.ConversionForegroundService

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* whichever the user grants is fine */ }

    private val pickTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onTreePicked?.invoke(uri)
    }

    private var onTreePicked: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        maybeRequestAllFilesAccess()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(
                        onPickFolder = { cb ->
                            onTreePicked = cb
                            pickTreeLauncher.launch(null)
                        },
                        onStart = { startService(Intent(this, ConversionForegroundService::class.java).setAction(ConversionForegroundService.ACTION_START)) },
                        onStop = { startService(Intent(this, ConversionForegroundService::class.java).setAction(ConversionForegroundService.ACTION_STOP)) },
                        onRepair = { startService(Intent(this, ConversionForegroundService::class.java).setAction(ConversionForegroundService.ACTION_REPAIR)) }
                    )
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        // Android 10+ — without this, the OS strips GPS values from EXIF when we
        // read JPGs via ContentResolver, leaving only the IFD shell behind.
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_MEDIA_LOCATION
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    /**
     * On Android 11+, scoped storage prevents us from setting the file's
     * `mtime` (vivo gallery shows mtime as 时间) and from updating
     * MediaStore columns on files we don't own (we write via SAF, so the
     * outputs are technically owned by DocumentsUI). MANAGE_EXTERNAL_STORAGE
     * is the only permission that lifts both restrictions.
     *
     * We don't gate functionality on it — the converter still works without
     * — but the user-visible "shooting time" / GPS in vivo gallery won't be
     * correct unless this is granted.
     */
    private fun maybeRequestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (Environment.isExternalStorageManager()) return
        // Take the user to the per-app "All files access" page. They have to
        // toggle it manually; there's no runtime grant for this permission.
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Some OEM ROMs hide the per-app screen — fall back to the global
            // "All files access" list.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) { /* give up silently */ }
        }
    }
}
