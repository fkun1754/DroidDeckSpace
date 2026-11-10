package com.droiddeck.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.RuntimeDiscovery
import com.droiddeck.launcher.runtime.RuntimeStore
import com.droiddeck.launcher.runtime.container.ContainerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runtime management inside the DroidSpaces section: shows the active runtime, lets the user
 * import an existing DroidSpaces container, create (and download) a new runtime, switch between
 * imported runtimes, and choose whether removal also deletes the DroidSpaces container.
 */
@Composable
internal fun RuntimeManagerSection(busy: Boolean, sessionRunning: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var runtimes by remember { mutableStateOf(RuntimeStore.entries(ctx)) }
    var active by remember { mutableStateOf(RuntimeStore.active(ctx)) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        runtimes = RuntimeStore.entries(ctx)
        active = RuntimeStore.active(ctx)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    active?.label ?: stringResource(R.string.runtime_mgr_not_imported),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                )
                val detail = active?.let { "${it.container} · ${if (LinuxRuntime.isInstalledAt(File(it.rootfs))) stringResource(R.string.setup_check_done) else stringResource(R.string.setup_runtime_missing)}" }
                    ?: stringResource(R.string.runtime_mgr_hint)
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { dialog = "manage" }, enabled = !busy && !sessionRunning) {
                Text(stringResource(R.string.runtime_mgr_manage))
            }
        }
        if (working) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Text(progress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
    }

    if (dialog == "manage") {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.setup_container_group)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    runtimes.forEach { entry ->
                        TextButton(onClick = {
                            RuntimeStore.setActive(ctx, entry.container)
                            refresh()
                            message = null
                        }) {
                            Text(
                                (if (active?.container == entry.container) "✓ " else "") +
                                    entry.label + "  ·  " + entry.container,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    TextButton(onClick = { dialog = "import" }) { Text(stringResource(R.string.runtime_mgr_import)) }
                    TextButton(onClick = { dialog = "new" }, enabled = !working) { Text(stringResource(R.string.runtime_mgr_new)) }
                    TextButton(onClick = { dialog = "remove" }, enabled = active != null) { Text(stringResource(R.string.runtime_mgr_remove)) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_close)) } },
        )
    }

    if (dialog == "import") {
        val containers = remember(dialog) { withBlocking { RuntimeDiscovery.list(ctx) } }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.runtime_mgr_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (containers.isEmpty()) Text(stringResource(R.string.runtime_mgr_no_containers), fontSize = 13.sp)
                    containers.forEach { container ->
                        TextButton(onClick = {
                            val reason = RuntimeDiscovery.compatibility(ctx, container)
                            if (reason != null) {
                                message = ctx.getString(R.string.runtime_mgr_incompatible, reason)
                            } else {
                                RuntimeStore.add(ctx, RuntimeStore.Entry(
                                    container = container.name,
                                    label = container.name,
                                    rootfs = container.rootfs,
                                    imported = true,
                                ), activate = true)
                                message = ctx.getString(R.string.runtime_mgr_imported, container.name)
                                dialog = null
                                refresh()
                            }
                        }) {
                            Text("${container.name}  ·  ${container.rootfs}", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_close)) } },
        )
    }

    if (dialog == "new") {
        val taken = remember(dialog) { withBlocking { RuntimeDiscovery.list(ctx) }.map { it.name } }
        val newLabel = RuntimeStore.nextLabel(ctx, taken)
        val newContainer = RuntimeStore.uniqueContainer(ctx, taken)
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.runtime_mgr_new)) },
            text = {
                Column {
                    Text("$newLabel  ·  $newContainer", fontSize = 13.sp)
                    Text(stringResource(R.string.runtime_mgr_new_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    val label = newLabel
                    val container = newContainer
                    val root = File(ctx.filesDir, "runtimes/${System.currentTimeMillis()}")
                    dialog = null
                    working = true
                    progress = ctx.getString(R.string.runtime_mgr_working)
                    scope.launch {
                        val release = withContext(Dispatchers.IO) { LinuxRuntimeInstaller.fetchRelease() }
                        if (release == null) {
                            message = ctx.getString(R.string.runtime_mgr_failed, "无法获取运行时版本")
                            working = false
                            return@launch
                        }
                        val ok = withContext(Dispatchers.IO) {
                            LinuxRuntimeInstaller.installTo(ctx, release, root, object : LinuxRuntimeInstaller.ProgressListener {
                                override fun onProgress(stage: String?, percent: Int) {
                                    progress = stage ?: ""
                                }
                                override fun onProgress(step: LinuxRuntimeInstaller.Step?, stage: String?, percent: Int) {
                                    progress = stage ?: ""
                                }
                            })
                        }
                        working = false
                        if (ok) {
                            RuntimeStore.add(ctx, RuntimeStore.Entry(container, label, root.path, imported = false), activate = true)
                            message = ctx.getString(R.string.runtime_mgr_imported, label)
                            refresh()
                        } else {
                            message = ctx.getString(R.string.runtime_mgr_failed, "安装失败")
                        }
                    }
                }) { Text(stringResource(R.string.runtime_mgr_start)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (dialog == "remove") {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.runtime_mgr_remove)) },
            text = {
                Column {
                    Text(stringResource(R.string.runtime_mgr_remove_hint), fontSize = 13.sp)
                    active?.let { entry ->
                        TextButton(onClick = {
                            RuntimeStore.remove(ctx, entry.container)
                            dialog = null
                            message = null
                            refresh()
                        }) { Text(stringResource(R.string.runtime_mgr_remove_app)) }
                        TextButton(onClick = {
                            val root = File(entry.rootfs)
                            RuntimeStore.remove(ctx, entry.container)
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ContainerRuntime.removeContainer(ctx, entry.container)
                                    if (root.path.startsWith(ctx.filesDir.path)) root.deleteRecursively()
                                }
                                message = null
                                refresh()
                            }
                            dialog = null
                        }) { Text(stringResource(R.string.runtime_mgr_remove_both)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun <T> withBlocking(block: () -> T): T = kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }
