package com.stb6.spdf.ui.settings

import kotlinx.coroutines.Deferred
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.stb6.spdf.R
import com.stb6.spdf.data.ReaderSettings
import androidx.compose.ui.input.nestedscroll.nestedScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentSettingsScreen(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Deferred<ReaderSettings?>,
    onResetToGlobal: () -> Unit,
    onOpenGlobalSettings: () -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),

        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.document_settings_title)) },
                scrollBehavior = scroll,

                actions = {
                    TextButton(onClick = onOpenGlobalSettings) {
                        Text(stringResource(R.string.document_settings_open_global))
                    }
                },
            )
        },
    ) { contentPadding ->
        SettingsContent(
            settings = settings,
            onChange = onChange,
            resetLabel = stringResource(R.string.settings_reset_to_global),
            onReset = onResetToGlobal,

            showAbout = false,
            sessionScope = true,
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        )
    }
}
