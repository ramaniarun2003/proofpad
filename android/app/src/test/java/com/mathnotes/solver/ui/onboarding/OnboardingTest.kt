package com.mathnotes.solver.ui.onboarding

import com.mathnotes.solver.ui.onboarding.Onboarding.GuideMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time first-launch flow: where the app opens, and where the guide's buttons lead. */
class OnboardingTest {

    @Test fun onboarding_done_opens_on_capture() {
        assertEquals("capture", Onboarding.startDestination(hasCompletedOnboarding = true))
    }

    @Test fun onboarding_not_done_opens_on_the_cover() {
        assertEquals("welcome", Onboarding.startDestination(hasCompletedOnboarding = false))
    }

    @Test fun the_flag_once_written_is_the_only_thing_that_counts() {
        assertTrue(Onboarding.hasCompleted(stored = true, legacySeenWelcome = false, legacySetupDone = false))
        // Settings "Show welcome screen again" wrote false: the older flags must not override it.
        assertFalse(Onboarding.hasCompleted(stored = false, legacySeenWelcome = true, legacySetupDone = true))
    }

    @Test fun an_install_from_before_the_flag_is_judged_by_the_two_older_ones() {
        // Finished the cover and the wizard before the update: no guide after it.
        assertTrue(Onboarding.hasCompleted(stored = null, legacySeenWelcome = true, legacySetupDone = true))
        // Quit partway before the update, or a fresh install: the flow runs.
        assertFalse(Onboarding.hasCompleted(stored = null, legacySeenWelcome = true, legacySetupDone = false))
        assertFalse(Onboarding.hasCompleted(stored = null, legacySeenWelcome = false, legacySetupDone = false))
    }

    @Test fun next_walks_the_three_pages_then_goes_to_the_wizard() {
        assertEquals(GuideMove.ToPage(1), Onboarding.onNext(0))
        assertEquals(GuideMove.ToPage(2), Onboarding.onNext(1))
        assertEquals(GuideMove.ToSetup, Onboarding.onNext(2))      // "Set up my handwriting"
    }

    @Test fun back_walks_the_pages_and_from_the_first_returns_to_the_cover() {
        assertEquals(GuideMove.ToPage(1), Onboarding.onBack(2))
        assertEquals(GuideMove.ToPage(0), Onboarding.onBack(1))
        assertEquals(GuideMove.ToCover, Onboarding.onBack(0))
    }

    @Test fun skip_goes_to_the_wizard_and_is_offered_on_pages_one_and_two_only() {
        assertEquals(GuideMove.ToSetup, Onboarding.onSkip())
        assertEquals(listOf(true, true, false), (0 until Onboarding.GUIDE_PAGES).map(Onboarding::showsSkip))
    }

    @Test fun the_marble_is_the_same_every_time_and_about_half_black_half_white() {
        val a = Marble.pixels(300, 300, density = 2f)
        assertTrue(a.contentEquals(Marble.pixels(300, 300, density = 2f)))
        assertTrue(a.all { it == Marble.BLACK || it == Marble.WHITE })
        val white = a.count { it == Marble.WHITE }.toFloat() / a.size
        assertTrue("white share was $white", white in 0.35f..0.60f)
    }

    @Test fun there_are_three_pages() {
        assertEquals(3, Onboarding.GUIDE_PAGES)
    }
}
