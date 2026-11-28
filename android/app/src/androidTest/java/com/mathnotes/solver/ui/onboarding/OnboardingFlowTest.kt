package com.mathnotes.solver.ui.onboarding

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mathnotes.solver.MainActivity
import com.mathnotes.solver.data.WelcomePrefs
import com.mathnotes.solver.ui.setup.SetupViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first-launch flow end to end: cover → guide → wizard → Capture, and the one flag behind it.
 *
 * Instrumented: run it on an emulator or a spare phone with
 * `./gradlew :app:connectedDebugAndroidTest -PallowDeviceWipe` (it uninstalls the app afterwards).
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule(order = 0) val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun launchWithOnboarding(complete: Boolean): ActivityScenario<MainActivity> {
        runBlocking { WelcomePrefs.setOnboardingComplete(context, complete) }
        return ActivityScenario.launch(MainActivity::class.java)
    }

    private fun onboarded() = WelcomePrefs.hasCompletedOnboardingBlocking(context)

    @Test fun skip_on_page_two_lands_on_setup_and_reaching_capture_sets_the_flag() {
        launchWithOnboarding(complete = false).use {
            compose.onNodeWithText("Get started").performClick()
            compose.onNodeWithText("step 1 of 3").assertIsDisplayed()
            compose.onNodeWithText("Take a photo of one problem").assertIsDisplayed()

            compose.onNodeWithText("Next").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("step 2 of 3") > 0 }
            compose.onNodeWithText("See which steps are right").assertIsDisplayed()

            // Skip at the top right of page 2 goes on to the wizard, not to Capture.
            compose.onNodeWithTag("guide_skip_1").performClick()
            compose.onNodeWithText("Learn my handwriting").assertIsDisplayed()
            assertFalse("the guide alone must not complete onboarding", onboarded())

            // The wizard's own Skip reaches Capture, and only then is the flag set.
            compose.onNodeWithText("Skip for now").performClick()
            compose.waitUntil(5_000) { onboarded() }
            compose.onNodeWithText("Put one problem inside the box, page flat").assertIsDisplayed()
        }
    }

    @Test fun finishing_the_wizard_sets_the_flag_and_a_relaunch_starts_on_capture() {
        launchWithOnboarding(complete = false).use { scenario ->
            compose.onNodeWithText("Get started").performClick()
            compose.onNodeWithText("Next").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("step 2 of 3") > 0 }
            compose.onNodeWithText("Next").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("step 3 of 3") > 0 }
            compose.onNodeWithText("Set up my handwriting").performClick()

            compose.onNodeWithText("Learn my handwriting").assertIsDisplayed()
            compose.onNodeWithText("Done writing, capture").performClick()
            // In place of photographing a page: the read comes back with all five lines. Repeated
            // until the capture screen is listening for it.
            compose.waitUntil(10_000) {
                scenario.onActivity { ViewModelProvider(it)[SetupViewModel::class.java].deliverPageReadForTest() }
                compose.waitForIdle()
                compose.onAllNodesWithTextCount("Save my handwriting") > 0
            }
            compose.onNodeWithText("Save my handwriting").performClick()
            assertFalse("the flag is set on reaching Capture, not before", onboarded())
            compose.onNodeWithText("Snap my first problem").performClick()
            compose.waitUntil(5_000) { onboarded() }
            compose.onNodeWithText("Put one problem inside the box, page flat").assertIsDisplayed()
        }

        // Relaunch: straight to Capture, no cover and no guide.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Put one problem inside the box, page flat").assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithTextCount("Get started"))
            assertEquals(0, compose.onAllNodesWithTextCount("step 1 of 3"))
        }
    }

    @Test fun back_on_the_first_guide_page_returns_to_the_cover() {
        launchWithOnboarding(complete = false).use { scenario ->
            compose.onNodeWithText("Get started").performClick()
            compose.onNodeWithText("step 1 of 3").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Get started").assertIsDisplayed()
        }
    }

    @Test fun once_onboarded_the_app_opens_on_capture_and_never_shows_the_guide() {
        launchWithOnboarding(complete = true).use {
            compose.onNodeWithText("Put one problem inside the box, page flat").assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithTextCount("Get started"))
            assertEquals(0, compose.onAllNodesWithTextCount("step 1 of 3"))
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
