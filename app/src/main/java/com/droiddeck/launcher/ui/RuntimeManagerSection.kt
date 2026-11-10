package com.droiddeck.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.RuntimeDiscovery
import com.droiddeck.launcher.runtime.RuntimePrefs
import com.droiddeck.launcher.runtime.RuntimeStore
import com.droiddeck.launcher.runtime.container.ContainerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dialog-based management of Linux runtimes, opened from the "Linux container management" button.
 * A runtime is a DroidSpaces container + its rootfs. The list switches runtimes; long-press renames.
 * DroidSpaces containers are scanned asynchronously and compatibility is only checked when the user
 * actually selects one to import.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RuntimeManagerSection(
    show: Boolean,
    onDismiss: () -> Unit,
    busy: Boolean,
    sessionRunning: Boolean,
) {
    if (!show) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var runtimes by remember { mutableStateOf(RuntimeStore.entries(ctx)) }
    var active by remember { mutableStateOf(RuntimeStore.active(ctx)) }
    var dialog by remember { mutableStateOf("manage") }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<RuntimeStore.Entry?>(null) }
    var renameText by remember { mutableStateOf("") }
    fun refresh() {
        runtimes = RuntimeStore.entries(ctx)
        active = RuntimeStore.active(ctx)
    }

    if (dialog == "manage") {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.runtime_mgr_manage)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    var runAtBoot by remember { mutableStateOf(RuntimePrefs.runAtBoot(ctx)) }
                    var keepRunning by remember { mutableStateOf(RuntimePrefs.keepRunning(ctx)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.runtime_mgr_run_at_boot), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = runAtBoot, onCheckedChange = {
                            runAtBoot = it
                            RuntimePrefs.setRunAtBoot(ctx, it)
                        })
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.runtime_mgr_keep_running), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = keepRunning, onCheckedChange = {
                            keepRunning = it
                            RuntimePrefs.setKeepRunning(ctx, it)
                        })
                    }
                    if (working) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text(progress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    runtimes.forEach { entry ->
                        Column(
                            Modifier.fillMaxWidth().combinedClickable(
                                enabled = !sessionRunning && !working,
                                onClick = {
                                    RuntimeStore.setActive(ctx, entry.container)
                                    message = null
                                    refresh()
                                },
                                onLongClick = {
                                    renameTarget = entry
                                    renameText = entry.label
                                },
                            ).padding(horizontal = 4.dp, vertical = 10.dp),
                        ) {
                            Text(
                                (if (active?.container == entry.container) "✓ " else "") + entry.label,
                                fontSize = 14.sp,
                            )
                            Text(
                                entry.container + " · " +
                                    if (LinuxRuntime.isInstalledAt(File(entry.rootfs))) stringResource(R.string.setup_check_done)
                                    else stringResource(R.string.setup_runtime_missing),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { dialog = "import" }, enabled = !working) { Text(stringResource(R.string.runtime_mgr_import)) }
                    TextButton(onClick = { dialog = "new" }, enabled = !working) { Text(stringResource(R.string.runtime_mgr_new)) }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.runtime_mgr_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.runtime_mgr_rename_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RuntimeStore.rename(ctx, target.container, renameText)
                    renameTarget = null
                    refresh()
                }) { Text(stringResource(R.string.runtime_mgr_rename_save)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (dialog == "import") {
        var containers by remember(dialog) { mutableStateOf<List<RuntimeDiscovery.Container>?>(null) }
        var checking by remember(dialog) { mutableStateOf(false) }
        LaunchedEffect(dialog) {
            containers = withContext(Dispatchers.IO) { RuntimeDiscovery.list(ctx) }
        }
        AlertDialog(
            onDismissRequest = { dialog = "manage" },
            title = { Text(stringResource(R.string.runtime_mgr_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val list = containers
                    if (list == null || checking) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        Text(
                            stringResource(if (checking) R.string.runtime_mgr_checking else R.string.runtime_mgr_scanning),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (list.isEmpty()) {
                        Text(stringResource(R.string.runtime_mgr_no_containers), fontSize = 13.sp)
                    } else {
                        list.forEach { container ->
                            TextButton(
                                enabled = !checking,
                                onClick = {
                                    checking = true
                                    scope.launch {
                                        val reason = withContext(Dispatchers.IO) { RuntimeDiscovery.compatibility(ctx, container) }
                                        checking = false
                                        if (reason != null) {
                                            message = ctx.getString(R.string.runtime_mgr_incompatible, reason)
                                        } else {
                                            RuntimeStore.add(ctx, RuntimeStore.Entry(
                                                container = container.name,
                                                label = RuntimeStore.nextLabel(ctx, list.map { it.name }),
                                                rootfs = container.rootfs,
                                                imported = true,
                                            ), activate = true)
                                            message = ctx.getString(R.string.runtime_mgr_imported, container.name)
                                            refresh()
                                            dialog = "manage"
                                        }
                                    }
                                },
                            ) { Text(container.name, fontSize = 13.sp) }
                        }
                    }
                    message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "manage" }) { Text(stringResource(R.string.common_back)) } },
        )
    }

    if (dialog == "new") {
        var taken by remember(dialog) { mutableStateOf<List<String>?>(null) }
        LaunchedEffect(dialog) {
            taken = withContext(Dispatchers.IO) { RuntimeDiscovery.list(ctx).map { it.name } }
        }
        val takenNames = taken ?: emptyList()
        val label = RuntimeStore.nextLabel(ctx, takenNames)
        val containerName = RuntimeStore.uniqueContainer(ctx, takenNames)
        AlertDialog(
            onDismissRequest = { dialog = "manage" },
            title = { Text(stringResource(R.string.runtime_mgr_new)) },
            text = {
                Column {
                    Text(label, fontSize = 14.sp)
                    Text(containerName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.runtime_mgr_new_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (working) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text(progress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    dialog = "manage"
                    working = true
                    progress = ctx.getString(R.string.runtime_mgr_working)
                    scope.launch {
                        val release = withContext(Dispatchers.IO) { LinuxRuntimeInstaller.fetchRelease() }
                        if (release == null) {
                            message = ctx.getString(R.string.runtime_mgr_failed, "release")
                            working = false
                        } else {
                            val root = File(ctx.filesDir, "runtimes/${System.currentTimeMillis()}")
                            val ok = withContext(Dispatchers.IO) {
                                LinuxRuntimeInstaller.installTo(ctx, release, root, object : LinuxRuntimeInstaller.ProgressListener {
                                    override fun onProgress(stage: String?, percent: Int) {
                                        progress = stage ?: ""
                                    }
                                })
                            }
                            working = false
                            if (ok) {
                                RuntimeStore.add(ctx, RuntimeStore.Entry(containerName, label, root.path, imported = false), activate = true)
                                message = ctx.getString(R.string.runtime_mgr_imported, label)
                                refresh()
                            } else {
                                message = ctx.getString(R.string.runtime_mgr_failed, "install")
                            }
                        }
                    }
                }) { Text(stringResource(R.string.runtime_mgr_start)) }
            },
            dismissButton = { TextButton(onClick = { dialog = "manage" }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

}
