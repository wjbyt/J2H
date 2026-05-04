package com.wjbyt.j2h.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wjbyt.j2h.R
import com.wjbyt.j2h.storage.TreeUriStore
import com.wjbyt.j2h.work.ConversionForegroundService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRepair: () -> Unit
) {
    val ctx = LocalContext.current
    val store = remember { TreeUriStore(ctx) }
    val uris by store.uris.collectAsState(initial = emptyList())
    val quality by store.quality.collectAsState(initial = 95)
    val videoPct by store.videoBitratePct.collectAsState(initial = 60)
    val state by ConversionForegroundService.state.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringFrom(R.string.app_name)) }) },
        floatingActionButton = {
            // Plain icon-only FAB so it covers less of the log area below.
            if (!state.running) {
                FloatingActionButton(
                    onClick = { onPickFolder { uri -> scope.launch { store.add(uri) } } }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringFrom(R.string.add_folder))
                }
            }
        }
    ) { padding ->
        // Whole page scrolls vertically — previously the log was clipped on
        // shorter screens because the cards above pushed it off the viewport.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {

            // ----- progress block -----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = if (state.running) stringFrom(R.string.status_running)
                        else if (state.done + state.failed + state.skipped > 0) stringFrom(R.string.status_done)
                        else stringFrom(R.string.status_idle),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    if (state.total > 0) {
                        val frac = ((state.done + state.failed + state.skipped).toFloat() /
                                state.total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("成功 ${state.done} · 失败 ${state.failed} · 跳过 ${state.skipped} / 共 ${state.total}",
                             style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.current.isNotEmpty()) {
                        Text(
                            "当前: ${state.current}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ----- start/stop -----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = !state.running && uris.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text(stringFrom(R.string.start)) }
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.running,
                    modifier = Modifier.weight(1f)
                ) { Text(stringFrom(R.string.stop)) }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRepair,
                enabled = !state.running && uris.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("修复已转换 HEIC 的元数据（不重编码）") }

            Spacer(Modifier.height(8.dp))
            Text(stringFrom(R.string.warning_destructive),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)

            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("HEIC 质量：$quality", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = { v -> scope.launch { store.setQuality(v.toInt()) } },
                        valueRange = 50f..100f,
                        steps = 49,
                        enabled = !state.running
                    )
                    Text("校验通过后再删除原 JPG（DateTime/GPS 必须能在新 HEIC 中读到）",
                         style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("视频画质：${videoPct} （CQ 恒定画质，文件大小自适应内容）",
                         style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = videoPct.toFloat(),
                        onValueChange = { v -> scope.launch { store.setVideoBitratePct(v.toInt()) } },
                        valueRange = 30f..100f,
                        steps = 69,
                        enabled = !state.running
                    )
                    Text("70 ≈ 视觉透明（推荐）· 90+ 完全保真 · 50 以下激进压缩",
                         style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ----- folders -----
            Text(stringFrom(R.string.folders_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (uris.isEmpty()) {
                Text(stringFrom(R.string.empty_folders), style = MaterialTheme.typography.bodySmall)
            } else {
                // A plain Column instead of LazyColumn — nesting LazyColumn
                // inside a vertically-scrolling parent throws at runtime.
                // The folder list is short (handful of entries) so a non-lazy
                // implementation is fine here.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp).fillMaxWidth()) {
                        for (uri in uris) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    text = friendlyName(uri),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(onClick = { scope.launch { store.remove(uri) } },
                                           enabled = !state.running) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringFrom(R.string.remove))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ----- log -----
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringFrom(R.string.logs_title),
                     style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val full = state.log.joinToString("\n")
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("J2H log", full))
                    Toast.makeText(ctx, "日志已复制 (${full.length} 字符)", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制全部日志")
                }
            }
            Spacer(Modifier.height(4.dp))
            // Fixed height so the outer page scroll keeps working — weight(1f)
            // is illegal inside a verticalScroll Column. Inner scroll keeps the
            // log itself navigable for long histories.
            Card(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                SelectionContainer {
                    Box(Modifier.padding(8.dp)) {
                        Column(modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())) {
                            for (line in state.log.takeLast(800)) {
                                Text(line, style = MaterialTheme.typography.bodySmall,
                                     fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun stringFrom(id: Int): String =
    androidx.compose.ui.res.stringResource(id)

private fun friendlyName(uri: Uri): String {
    // tree URI looks like content://.../tree/primary%3ADCIM%2FCamera
    val path = Uri.decode(uri.lastPathSegment ?: uri.toString())
    return path.removePrefix("primary:").ifEmpty { uri.toString() }
}
