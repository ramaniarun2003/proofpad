package com.mathnotes.solver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mathnotes.solver.ui.CaptureScreen
import com.mathnotes.solver.ui.HistoryScreen
import com.mathnotes.solver.ui.ResultsScreen
import com.mathnotes.solver.ui.ReviewScreen
import com.mathnotes.solver.ui.SettingsScreen
import com.mathnotes.solver.ui.SolverViewModel

/** Navigation: capture → review → results, plus history (cached sessions) and settings (backend URL). */
class MainActivity : ComponentActivity() {
    private val vm: SolverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "capture") {
                        composable("capture") {
                            CaptureScreen(vm, onCaptured = { nav.navigate("review") },
                                onHistory = { nav.navigate("history") }, onSettings = { nav.navigate("settings") })
                        }
                        composable("review") { ReviewScreen(vm, onSolved = { nav.navigate("results") }) }
                        composable("results") { ResultsScreen(vm, onNewProblem = { nav.popBackStack("capture", inclusive = false) }) }
                        composable("history") { HistoryScreen(vm, onOpen = { nav.navigate("results") }) }
                        composable("settings") { SettingsScreen(vm, onDone = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
