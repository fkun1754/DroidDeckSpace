package com.droiddeck.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings entry point to (re)set the desktop user's password. */
@Composable
internal fun DesktopPasswordDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.desktop_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.desktop_password_settings_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = first, onValueChange = { first = it }, singleLine = true,
                    label = { Text(stringResource(R.string.desktop_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = second, onValueChange = { second = it }, singleLine = true,
                    label = { Text(stringResource(R.string.desktop_password_confirm)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when {
                    first.length < 6 -> error = ctx.getString(R.string.desktop_password_short)
                    first != second -> error = ctx.getString(R.string.desktop_password_mismatch)
                    else -> {
                        busy = true
                        error = null
                        scope.launch {
                            val problem = withContext(Dispatchers.IO) {
                                com.droiddeck.launcher.runtime.DesktopPassword.set(ctx, first)
                            }
                            busy = false
                            if (problem == null) {
                                Toast.makeText(ctx, R.string.desktop_password_set, Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                error = problem
                            }
                        }
                    }
                }
            }) { Text(stringResource(if (busy) R.string.common_working else R.string.desktop_password_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
