package com.mathnotes.solver

import com.mathnotes.solver.ui.onboarding.Onboarding
import com.mathnotes.solver.ui.onboarding.GuideScreen
import com.mathnotes.solver.ui.UiMessage
import com.mathnotes.solver.ui.components.NoticeHost
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mathnotes.solver.data.WelcomePrefs
import com.mathnotes.solver.ui.CaptureScreen
import com.mathnotes.solver.ui.FolderDetailScreen
import com.mathnotes.solver.ui.GalleryCropScreen
import com.mathnotes.solver.ui.GalleryHomeScreen
import com.mathnotes.solver.ui.HistoryScreen
import com.mathnotes.solver.ui.ResultsScreen
import com.mathnotes.solver.ui.ReviewScreen
import com.mathnotes.solver.ui.SaveToGalleryScreen
import com.mathnotes.solver.ui.SettingsScreen
import com.mathnotes.solver.ui.SolverViewModel
import com.mathnotes.solver.ui.WelcomeScreen
import com.mathnotes.solver.ui.setup.SetupCheckScreen
import com.mathnotes.solver.ui.setup.SetupDoneScreen
import com.mathnotes.solver.ui.setup.SetupEvent
import com.mathnotes.solver.ui.setup.SetupViewModel
import com.mathnotes.solver.ui.setup.SetupWriteScreen
import com.mathnotes.solver.ui.theme.NotepadTheme

/** Navigation: welcome → handwriting setup wizard → capture → review → results, plus history and settings. */
class MainActivity : ComponentActivity() {
    private val vm: SolverViewModel by viewModels()
    private val setupVm: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // AndroidX splash: keep the system splash up until Compose draws (no white flash).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // One flag for the whole first-launch flow (cover, guide, wizard): done means Capture, full stop.
        val start = Onboarding.startDestination(WelcomePrefs.hasCompletedOnboardingBlocking(this))

        setContent {
            NotepadTheme {
                Surface {
                  Box {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = start) {
                        composable("welcome") {
                            // The cover stays on the back stack so Back from the guide's first page returns to it.
                            WelcomeScreen(onGetStarted = { nav.navigate("guide") })
                        }
                        // --- The one-time three-page guide ---
                        composable("guide") {
                            GuideScreen(
                                onToCover = { nav.popBackStack("welcome", inclusive = false) },
                                // Skip on any page, or the last page's button: on to the wizard, leaving
                                // the cover and the guide behind.
                                onToSetup = { nav.navigate("setupWrite") { popUpTo("welcome") { inclusive = true } } },
                            )
                        }
                        // --- Handwriting setup wizard ---
                        composable("setupWrite") {
                            SetupWriteScreen(
                                onSkip = {
                                    vm.completeOnboarding()
                                    nav.navigate("capture") { popUpTo("setupWrite") { inclusive = true } }
                                },
                                onCapture = { nav.navigate("setupCapture") },
                            )
                        }
                        composable("setupCapture") {
                            CaptureScreen(
                                vm,
                                onCaptured = {}, onGalleryPicked = {}, onHistory = {}, onGallery = {}, onSettings = {},
                                pageMode = true,
                                setupVm = setupVm,
                                onPageRecognized = { nav.navigate("setupCheck") },
                            )
                        }
                        composable("setupCheck") {
                            SetupCheckScreen(
                                setupVm,
                                onRetake = { nav.popBackStack("setupCapture", inclusive = false) },
                                onSave = { setupVm.saveSamples(); nav.navigate("setupDone") },
                                onRetakeLine = { idx -> setupVm.beginRetake(idx); nav.navigate("setupRetake") },
                            )
                        }
                        composable("setupRetake") {
                            // Single-line retake (formula mode) for a line the page read missed.
                            LaunchedEffect(Unit) {
                                setupVm.events.collect {
                                    when (it) {
                                        is SetupEvent.Recognized -> nav.popBackStack("setupCheck", inclusive = false)
                                        is SetupEvent.Error -> vm.notify(UiMessage(it.message))
                                    }
                                }
                            }
                            CaptureScreen(
                                vm,
                                onCaptured = {}, onGalleryPicked = {}, onHistory = {}, onGallery = {}, onSettings = {},
                                onFormulaCaptured = { f, crop -> setupVm.fillRetakeLine(f, crop) },
                            )
                        }
                        composable("setupDone") {
                            SetupDoneScreen(setupVm, onDone = {
                                vm.completeOnboarding()
                                nav.navigate("capture") { popUpTo("setupWrite") { inclusive = true } }
                            })
                        }
                        composable("capture") {
                            CaptureScreen(vm, onCaptured = { nav.navigate("review") },
                                onGalleryPicked = { nav.navigate("galleryCrop") },
                                onHistory = { nav.navigate("history") },
                                onGallery = { nav.navigate("gallery") },
                                onSettings = { nav.navigate("settings") })
                        }
                        composable("gallery") {
                            GalleryHomeScreen(vm,
                                onOpenFolder = { id -> vm.openFolder(id); nav.navigate("folderDetail") },
                                onOpenNote = { nav.navigate("results") },
                                onSettings = { nav.navigate("settings") },
                                onSnap = { nav.popBackStack("capture", inclusive = false) })
                        }
                        composable("folderDetail") {
                            FolderDetailScreen(vm,
                                onBack = { nav.popBackStack() },
                                onOpenNote = { nav.navigate("results") },
                                onAddNote = { nav.popBackStack("capture", inclusive = false) },
                                onSettings = { nav.navigate("settings") })
                        }
                        composable("galleryCrop") {
                            GalleryCropScreen(
                                vm,
                                onCropped = { nav.navigate("review") { popUpTo("capture") } },
                                onCancel = { nav.popBackStack("capture", inclusive = false) },
                            )
                        }
                        composable("review") {
                            ReviewScreen(vm, onSolved = { nav.navigate("results") { popUpTo("capture") { inclusive = false } } },
                                onRetake = { nav.popBackStack("capture", inclusive = false) })
                        }
                        composable("results") {
                            ResultsScreen(vm,
                                onNewProblem = { vm.reset(); nav.popBackStack("capture", inclusive = false) },
                                onSaveToGallery = { nav.navigate("saveToGallery") },
                                // An unsolved note goes back to Review in place of this screen.
                                onFixReading = { nav.navigate("review") { popUpTo("results") { inclusive = true } } })
                        }
                        composable("saveToGallery") {
                            SaveToGalleryScreen(vm, onDone = { nav.popBackStack("results", inclusive = false) })
                        }
                        composable("history") {
                            HistoryScreen(vm,
                                onOpen = { nav.navigate("results") },
                                onSettings = { nav.navigate("settings") },
                                onSnap = { nav.popBackStack("capture", inclusive = false) })
                        }
                        composable("settings") {
                            SettingsScreen(
                                vm,
                                onDone = { nav.popBackStack() },
                                onRedoSetup = {
                                    setupVm.reset()
                                    nav.navigate("setupWrite") { popUpTo("settings") { inclusive = true } }
                                },
                                // Clears the onboarding flag and restarts at the cover, with nothing behind it.
                                onShowWelcome = {
                                    vm.resetOnboarding { nav.navigate("welcome") { popUpTo(0) { inclusive = true } } }
                                },
                            )
                        }
                    }
                    // Save / delete confirmations and Undo, over whichever screen is showing.
                    NoticeHost(vm.messages, Modifier.align(Alignment.BottomCenter))
                  }
                }
            }
        }
    }
}
