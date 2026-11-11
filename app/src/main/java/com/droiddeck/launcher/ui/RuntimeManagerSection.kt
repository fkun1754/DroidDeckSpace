package com.droiddeck.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
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
 * Container management dialog: switching, importing, creating, renaming, repairing, updating and
 * removing runtimes. A runtime name and its DroidSpaces container name are the same.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RuntimeManagerSection(
    show: Boolean,
    onDismiss: () -> Unit,
    busy: Boolean,
    sessionRunning: Boolean,
    installedVersion: String?,
    latestVersion: String?,
    onChanged: () -> Unit = {},
) {
    if (!show) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var runtimes by remember { mutableStateOf(RuntimeStore.entries(ctx)) }
    var active by remember { mutableStateOf(RuntimeStore.active(ctx)) }
    var sub by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<RuntimeStore.Entry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var editBoot by remember { mutableStateOf(false) }
    var clearCacheOnClose by remember { mutableStateOf(false) }
    fun closeManager() {
        if (clearCacheOnClose) {
            clearCacheOnClose = false
            Thread({ com.droiddeck.launcher.runtime.RuntimeCache.clear(ctx) }, "runtime-cache-clear").start()
        }
        onDismiss()
    }
    LaunchedEffect(show) { if (!show) sub = null }
    fun refresh() {
        runtimes = RuntimeStore.entries(ctx)
        active = RuntimeStore.active(ctx)
    }
    fun deleteRuntimeFiles(root: File) {
        if (root.path.startsWith(ctx.filesDir.path)) {
            com.droiddeck.launcher.runtime.container.SuShell.exec("rm -rf " + com.droiddeck.launcher.runtime.container.SuShell.quote(root.absolutePath), 180_000)
        }
    }
    fun installActive(repair: Boolean) {
        if (busy || sessionRunning || working) return
        scope.launch {
            val release = withContext(Dispatchers.IO) { LinuxRuntimeInstaller.fetchRelease() }
            if (release == null) {
                message = ctx.getString(R.string.runtime_mgr_failed, "release")
                return@launch
            }
            if (repair && !installedVersion.isNullOrEmpty() && release.version != installedVersion) {
                message = ctx.getString(R.string.runtime_mgr_use_update)
                return@launch
            }
            working = true
            progress = ctx.getString(if (repair) R.string.runtime_mgr_repairing else R.string.runtime_mgr_working)
            val entry = com.droiddeck.launcher.runtime.RuntimeStore.active(ctx)
            val ok = withContext(Dispatchers.IO) {
                if (entry?.builtin == true || entry == null) {
                    LinuxRuntimeInstaller.install(ctx, release, null)
                } else {
                    LinuxRuntimeInstaller.installTo(ctx, release, File(entry.rootfs), null)
                }
            }
            working = false
            message = if (ok) ctx.getString(if (repair) R.string.runtime_mgr_repaired else R.string.runtime_mgr_updated)
            else ctx.getString(R.string.runtime_mgr_failed, "install")
            refresh()
        }
    }

    if (working) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.runtime_mgr_busy_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text(progress.ifEmpty { stringResource(R.string.runtime_mgr_working) }, fontSize = 13.sp)
                }
            },
            confirmButton = {},
        )
    }

    AlertDialog(
        onDismissRequest = { if (!working) closeManager() },
        title = { Text(stringResource(R.string.runtime_mgr_manage)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var runAtBoot by remember { mutableStateOf(false) }
                LaunchedEffect(show) {
                    runAtBoot = withContext(Dispatchers.IO) {
                        active?.let { com.droiddeck.launcher.runtime.ContainerBootConfig.runAtBoot(ctx, it.container) } ?: false
                    }
                }
                var keepRunning by remember { mutableStateOf(RuntimePrefs.keepRunning(ctx)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.runtime_mgr_run_at_boot), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = runAtBoot, onCheckedChange = { runAtBoot = it; RuntimePrefs.setRunAtBoot(ctx, it) })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.runtime_mgr_keep_running), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = keepRunning, onCheckedChange = { keepRunning = it; RuntimePrefs.setKeepRunning(ctx, it) })
                }
                var keepCache by remember { mutableStateOf(RuntimePrefs.cacheEnabled(ctx)) }
                var cacheDays by remember { mutableStateOf(RuntimePrefs.cacheRetentionDays(ctx)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.runtime_mgr_keep_cache), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = keepCache, onCheckedChange = {
                        keepCache = it
                        RuntimePrefs.setCacheEnabled(ctx, it)
                        // Do not delete immediately: the user may still be inspecting sizes. The
                        // cache is cleared when the Linux container management panel is closed.
                        clearCacheOnClose = !it
                    })
                }
                if (keepCache) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.runtime_mgr_cache_days), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val options = listOf(3, 7, 14, 30, 0)
                            cacheDays = options[(options.indexOf(cacheDays).coerceAtLeast(0) + 1) % options.size]
                            RuntimePrefs.setCacheRetentionDays(ctx, cacheDays)
                        }) {
                            Text(
                                if (cacheDays <= 0) stringResource(R.string.runtime_mgr_days_forever)
                                else ctx.getString(R.string.runtime_mgr_days, cacheDays)
                            )
                        }
                    }
                }
                if (working) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
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
                            onLongClick = { editTarget = entry; renameText = entry.label },
                        ).padding(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Text((if (active?.container == entry.container) "✓ " else "") + entry.label, fontSize = 14.sp)
                        if (LinuxRuntime.isInstalledAt(File(entry.rootfs))) {
                            Text(installedVersion ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(stringResource(R.string.setup_runtime_missing), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.runtime_mgr_longpress_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = installedVersion != null && !working, onClick = { sub = "repair" }) { Text(stringResource(R.string.runtime_mgr_repair)) }
                    TextButton(
                        // Update only applies to an installed runtime; with no runtime the user must
                        // create/import one instead of triggering an install through Update.
                        enabled = installedVersion != null && latestVersion != null &&
                            latestVersion != installedVersion && !working,
                        onClick = { installActive(false) },
                    ) { Text(stringResource(R.string.runtime_mgr_update)) }
                    TextButton(enabled = active != null && !working, onClick = { sub = "remove" }) { Text(stringResource(R.string.runtime_mgr_uninstall_short)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !working, onClick = { sub = "import" }) { Text(stringResource(R.string.runtime_mgr_import)) }
                    TextButton(enabled = !working, onClick = { sub = "new" }) { Text(stringResource(R.string.runtime_mgr_new)) }
                }
                val hiddenBuiltin = com.droiddeck.launcher.runtime.container.ContainerPaths.defaultContainerName() in
                    RuntimeStore.hiddenContainers(ctx) && LinuxRuntime.hasRootfsAt(
                    com.droiddeck.launcher.runtime.LinuxRuntime.defaultRootDir(ctx))
                if (hiddenBuiltin) {
                    TextButton(enabled = !working, onClick = {
                        RuntimeStore.unhide(ctx, com.droiddeck.launcher.runtime.container.ContainerPaths.defaultContainerName())
                        refresh(); onChanged()
                    }) { Text(stringResource(R.string.runtime_mgr_restore_builtin)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { closeManager() }) { Text(stringResource(R.string.common_close)) } },
    )

    if (sub == "repair") {
        AlertDialog(
            onDismissRequest = { sub = null },
            title = { Text(stringResource(R.string.runtime_mgr_repair_confirm_title)) },
            text = { Text(stringResource(R.string.runtime_mgr_repair_confirm_text, active?.label ?: "")) },
            confirmButton = { TextButton(onClick = { sub = null; installActive(true) }) { Text(stringResource(R.string.runtime_mgr_repair)) } },
            dismissButton = { TextButton(onClick = { sub = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    editTarget?.let { target ->
        LaunchedEffect(target.container) {
            editBoot = withContext(Dispatchers.IO) {
                com.droiddeck.launcher.runtime.ContainerBootConfig.runAtBoot(ctx, target.container)
            }
        }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.runtime_mgr_edit_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText, onValueChange = { renameText = it }, singleLine = true,
                        label = { Text(stringResource(R.string.runtime_mgr_rename_hint)) },
                        supportingText = { Text(renameError ?: stringResource(R.string.runtime_mgr_name_hint)) },
                        isError = renameError != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.runtime_mgr_run_at_boot), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = editBoot, onCheckedChange = { checked ->
                            editBoot = checked
                            // Always remember the choice; the config is (re)written after the next start.
                            com.droiddeck.launcher.runtime.RuntimePrefs.setContainerBoot(ctx, target.container, checked)
                            scope.launch {
                                val error = withContext(Dispatchers.IO) {
                                    com.droiddeck.launcher.runtime.ContainerBootConfig.setRunAtBoot(ctx, target.container, checked)
                                }
                                if (error != null) message = error
                            }
                        })
                    }
                    Text(stringResource(R.string.runtime_mgr_edit_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val error = com.droiddeck.launcher.runtime.RuntimeStore.rename(ctx, target.container, renameText)
                    if (error == null) { editTarget = null; renameError = null; message = null; refresh(); onChanged() }
                    else renameError = error
                }) { Text(stringResource(R.string.runtime_mgr_rename_save)) }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text(stringResource(R.string.common_close)) } },
        )
    }

    if (sub == "import") {
        var containers by remember(sub) { mutableStateOf<List<RuntimeDiscovery.Container>?>(null) }
        var checking by remember(sub) { mutableStateOf(false) }
        LaunchedEffect(sub) {
            val imported = RuntimeStore.entries(ctx).map { it.container }.toSet()
            containers = withContext(Dispatchers.IO) { RuntimeDiscovery.list(ctx, true).filter { it.name !in imported } }
        }
        AlertDialog(
            onDismissRequest = { sub = null },
            title = { Text(stringResource(R.string.runtime_mgr_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val list = containers
                    if (list == null || checking) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        Text(stringResource(if (checking) R.string.runtime_mgr_checking else R.string.runtime_mgr_scanning), fontSize = 12.sp)
                    } else if (list.isEmpty()) {
                        Text(stringResource(R.string.runtime_mgr_no_containers), fontSize = 13.sp)
                    } else {
                        list.forEach { container ->
                            TextButton(enabled = !checking, onClick = {
                                checking = true
                                working = true
                                progress = ctx.getString(R.string.runtime_mgr_checking)
                                scope.launch {
                                    val reason = withContext(Dispatchers.IO) { RuntimeDiscovery.compatibility(ctx, container) }
                                    checking = false
                                    working = false
                                    if (reason != null) {
                                        message = ctx.getString(R.string.runtime_mgr_incompatible, reason)
                                    } else {
                                        RuntimeStore.add(ctx, RuntimeStore.Entry(container.name, container.name, container.rootfs, imported = true), activate = true)
                                        message = ctx.getString(R.string.runtime_mgr_imported, container.name)
                                        refresh()
                                        onChanged()
                                        sub = null
                                    }
                                }
                            }) { Text(container.name, fontSize = 13.sp) }
                        }
                    }
                    message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { sub = null }) { Text(stringResource(R.string.common_back)) } },
        )
    }

    if (sub == "new") {
        var taken by remember(sub) { mutableStateOf<List<String>?>(null) }
        var name by remember(sub) { mutableStateOf("") }
        LaunchedEffect(sub) {
            val names = withContext(Dispatchers.IO) { RuntimeDiscovery.list(ctx, true).map { it.name } }
            taken = names
            name = RuntimeStore.nextName(ctx, names)
        }
        val valid = RuntimeStore.validName(name)
        val exists = taken?.any { it == name.trim() } == true || RuntimeStore.entries(ctx).any { it.container == name.trim() }
        AlertDialog(
            onDismissRequest = { sub = null },
            title = { Text(stringResource(R.string.runtime_mgr_new)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text(stringResource(R.string.runtime_mgr_name_hint)) },
                        isError = !valid || exists,
                        supportingText = { Text(if (!valid) stringResource(R.string.runtime_mgr_name_invalid) else if (exists) stringResource(R.string.runtime_mgr_name_taken) else stringResource(R.string.runtime_mgr_name_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Text(stringResource(R.string.runtime_mgr_new_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (working) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text(progress, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !working && valid && !exists, onClick = {
                    val label = name.trim()
                    sub = null
                    working = true
                    progress = ctx.getString(R.string.runtime_mgr_working)
                    scope.launch {
                        val release = withContext(Dispatchers.IO) { LinuxRuntimeInstaller.fetchRelease() }
                        if (release == null) {
                            working = false
                            message = ctx.getString(R.string.runtime_mgr_failed, "release")
                        } else {
                            val root = File(ctx.filesDir, "runtimes/${System.currentTimeMillis()}")
                            val ok = withContext(Dispatchers.IO) {
                                LinuxRuntimeInstaller.installTo(ctx, release, root, object : LinuxRuntimeInstaller.ProgressListener {
                                    override fun onProgress(stage: String?, percent: Int) { progress = stage ?: "" }
                                })
                            }
                            working = false
                            if (ok) {
                                RuntimeStore.add(ctx, RuntimeStore.Entry(label, label, root.path, imported = false), activate = true)
                                message = ctx.getString(R.string.runtime_mgr_imported, label)
                                refresh()
                                onChanged()
                            } else {
                                message = ctx.getString(R.string.runtime_mgr_failed, "install")
                            }
                        }
                    }
                }) { Text(stringResource(R.string.runtime_mgr_start)) }
            },
            dismissButton = { TextButton(onClick = { sub = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (sub == "remove") {
        AlertDialog(
            onDismissRequest = { sub = null },
            title = { Text(stringResource(R.string.runtime_mgr_remove)) },
            text = {
                Column {
                    Text(stringResource(R.string.runtime_mgr_remove_hint), fontSize = 13.sp)
                    active?.let { entry ->
                        TextButton(onClick = {
                            sub = null
                            // Detach from DroidDeckSpace only: keep the DroidSpaces container and
                            // its rootfs intact so it can still be used/managed in DroidSpaces.
                            RuntimeStore.hide(ctx, entry.container)
                            message = ctx.getString(R.string.runtime_mgr_detached)
                            refresh()
                            onChanged()
                        }) { Text(stringResource(R.string.runtime_mgr_remove_app)) }
                        TextButton(onClick = {
                            sub = null
                            working = true
                            progress = ctx.getString(R.string.runtime_mgr_removing)
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { ContainerRuntime.stopContainer(ctx, entry.container) }
                                    RuntimeStore.remove(ctx, entry.container)
                                    ContainerRuntime.removeContainer(ctx, entry.container)
                                    deleteRuntimeFiles(File(entry.rootfs))
                                }
                                working = false
                                refresh()
                                onChanged()
                            }
                        }) { Text(stringResource(R.string.runtime_mgr_remove_both)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sub = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
