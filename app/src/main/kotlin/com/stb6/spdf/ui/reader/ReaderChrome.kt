package com.stb6.spdf.ui.reader

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.Crossfade
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.unit.dp
import com.stb6.spdf.R
import androidx.compose.ui.res.painterResource
import com.stb6.spdf.ui.DelayedLoading
import com.stb6.spdf.ui.ErrorDialog
import com.stb6.spdf.ui.UiText
import com.stb6.spdf.ui.resolve

@Composable
fun LoadingState() {
    DelayedLoading(
        modifier = Modifier.fillMaxSize().background(CanvasColor),
        color = Color.White,
    )
}

@Composable
fun ErrorState(reason: ErrorReason, detail: UiText?, onDismiss: () -> Unit) {
    ErrorDialog(
        title = stringResource(
            when (reason) {
                ErrorReason.CANNOT_OPEN -> R.string.reader_error_cannot_open
                ErrorReason.UNAVAILABLE -> R.string.reader_error_unavailable
            },
        ),
        message = when (reason) {
            ErrorReason.CANNOT_OPEN -> null
            ErrorReason.UNAVAILABLE -> stringResource(R.string.reader_error_unavailable_message)
        },
        detail = detail?.resolve(),
        detailLabel = stringResource(R.string.reader_error_detail_title),
        confirmLabel = stringResource(R.string.reader_error_close),
        onDismiss = onDismiss,
    )
}

@Composable
fun PasswordDialog(
    retry: Boolean,
    verifying: Boolean,
    // Keep passwords in memory only, never in saved state.
    password: String,
    onPasswordChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Cancel Compose autofill; the Activity autofill flag does not cover its semantics.
    val autofill = LocalAutofillManager.current
    DisposableEffect(Unit) {
        onDispose { autofill?.cancel() }
    }

    fun submit() {
        if (password.isEmpty()) return
        autofill?.cancel()
        onSubmit(password)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.reader_password_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.focusRequester(focusRequester),
                label = { Text(stringResource(R.string.reader_password_label)) },
                singleLine = true,
                visualTransformation = if (revealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            painter = painterResource(
                                if (revealed) R.drawable.ic_visibility_off
                                else R.drawable.ic_visibility,
                            ),
                            contentDescription = stringResource(
                                if (revealed) R.string.reader_password_hide
                                else R.string.reader_password_show,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                // Password keyboard semantics prevent personalized learning, unlike masking alone.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                isError = retry && !verifying,
                supportingText = {
                    Box(Modifier.height(HINT_LINE_HEIGHT)) {
                        Crossfade(
                            targetState = when {
                                verifying -> PasswordHint.Verifying
                                retry -> PasswordHint.Wrong
                                else -> PasswordHint.None
                            },
                            label = "password-hint",
                        ) { hint ->
                            when (hint) {
                                PasswordHint.None -> Unit

                                PasswordHint.Verifying -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(HINT_SPINNER_SIZE),
                                        strokeWidth = 1.5.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.reader_password_verifying))
                                }

                                PasswordHint.Wrong -> Text(
                                    text = stringResource(R.string.reader_password_wrong),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.reader_password_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                autofill?.cancel()
                onCancel()
            }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private enum class PasswordHint { None, Verifying, Wrong }

private val HINT_LINE_HEIGHT = 20.dp

private val HINT_SPINNER_SIZE = 14.dp
