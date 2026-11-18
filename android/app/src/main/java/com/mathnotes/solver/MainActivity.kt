package com.mathnotes.solver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mathnotes.solver.data.WelcomePrefs
import com.mathnotes.solver.ui.CaptureScreen
import com.mathnotes.solver.ui.GalleryCropScreen
import com.mathnotes.solver.ui.HistoryScreen
import com.mathnotes.solver.ui.ResultsScreen
import com.mathnotes.solver.ui.ReviewScreen
import com.mathnotes.solver.ui.SettingsScreen
import com.mathnotes.solver.ui.SolverViewModel
import com.mathnotes.solver.ui.WelcomeScreen
import com.mathnotes.solver.ui.theme.NotepadTheme

/** Navigation: (welcome, first launch only) → capture → review → results, plus history and settings. */
class MainActivity : ComponentActivity() {
    private val vm: SolverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // AndroidX splash: keep the system splash up until Compose draws (no white flash).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // First launch shows the notebook cover; afterwards drop straight into Capture.
        val start = if (WelcomePrefs.hasSeenWelcomeBlocking(this)) "capture" else "welcome"

        setContent {
            NotepadTheme {
                Surface {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = start) {
                        composable("welcome") {
                            WelcomeScreen(onGetStarted = {
                                vm.markWelcomeSeen()
                                nav.navigate("capture") { popUpTo("welcome") { inclusive = true } }
                            })
                        }
                        composable("capture") {
                            CaptureScreen(vm, onCaptured = { nav.navigate("review") },
                                onGalleryPicked = { nav.navigate("galleryCrop") },
                                onHistory = { nav.navigate("history") }, onSettings = { nav.navigate("settings") })
                        }
                        composable("galleryCrop") {
                            GalleryCropScreen(
                                vm,
                                onCropped = { nav.navigate("review") { popUpTo("capture") } },
                                onCancel = { nav.popBackStack("capture", inclusive = false) },
                            )
                        }
                        composable("review") {
                            ReviewScreen(vm, onSolved = { nav.navigate("results") },
                                onRetake = { nav.popBackStack("capture", inclusive = false) })
                        }
                        composable("results") { ResultsScreen(vm, onNewProblem = { nav.popBackStack("capture", inclusive = false) }) }
                        composable("history") {
                            HistoryScreen(vm,
                                onOpen = { nav.navigate("results") },
                                onSettings = { nav.navigate("settings") },
                                onSnap = { nav.popBackStack("capture", inclusive = false) })
                        }
                        composable("settings") { SettingsScreen(vm, onDone = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
