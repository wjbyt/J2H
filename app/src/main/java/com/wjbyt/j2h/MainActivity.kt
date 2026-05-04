package com.wjbyt.j2h

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(
                        onPickFolder = { cb ->
                            onTreePicked = cb
                            pickTreeLauncher.launch(null)
                        },
                        onStart = { startService(Intent(this, ConversionForegroundService::class.java).setAction(ConversionForegroundService.ACTION_START)) },
                        onStop = { startService(Intent(this, ConversionForegroundService::class.java).setAction(ConversionForegroundService.ACTION_STOP)) }
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
}
