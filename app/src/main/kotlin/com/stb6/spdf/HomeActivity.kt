package com.stb6.spdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stb6.spdf.data.DataStoreSettingsRepository
import com.stb6.spdf.ui.DelayedLoading
import com.stb6.spdf.ui.home.HomeScreen
import com.stb6.spdf.ui.settings.SettingsScreen
import com.stb6.spdf.ui.theme.pageEnter
import com.stb6.spdf.ui.theme.pageExit
import com.stb6.spdf.ui.theme.pagePopEnter
import com.stb6.spdf.ui.theme.pagePopExit
import com.stb6.spdf.ui.theme.AppTheme
import androidx.activity.compose.BackHandler

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                val repository = remember { DataStoreSettingsRepository(applicationContext) }
                val persisted by repository.settings.collectAsState(initial = null)
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(::openPdf) }

                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME,
                    enterTransition = { pageEnter() },
                    exitTransition = { pageExit() },
                    popEnterTransition = { pagePopEnter() },
                    popExitTransition = { pagePopExit() },
                ) {
                    composable(ROUTE_HOME) {
                        HomeScreen(
                            onOpenFile = { picker.launch(arrayOf(MIME_PDF)) },
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                        )
                    }

                    composable(ROUTE_SETTINGS) {

                        BackHandler { navController.popBackStack() }

                        val settings = persisted
                        if (settings == null) {
                            Surface(Modifier.fillMaxSize()) { DelayedLoading(Modifier.fillMaxSize()) }
                        } else SettingsScreen(settings = settings, onChange = repository::update)
                    }
                }
            }
        }
    }

    private fun openPdf(uri: Uri) {
        startActivity(
            Intent(this, ReaderActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private companion object {
        const val MIME_PDF = "application/pdf"
        const val ROUTE_HOME = "home"
        const val ROUTE_SETTINGS = "settings"
    }
}
