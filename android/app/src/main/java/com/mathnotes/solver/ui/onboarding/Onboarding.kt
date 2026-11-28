package com.mathnotes.solver.ui.onboarding

/**
 * First launch, once: the notebook cover, the three-page guide, then the handwriting setup wizard.
 * One flag, `hasCompletedOnboarding`, covers all three. It is set when the user reaches Capture
 * from the wizard (finishing it or skipping it) and cleared only by Settings "Show welcome screen
 * again". While it is set the app opens on Capture and none of this is shown.
 */
object Onboarding {
    const val WELCOME = "welcome"
    const val GUIDE = "guide"
    const val SETUP_WRITE = "setupWrite"
    const val CAPTURE = "capture"
    const val GUIDE_PAGES = 3

    /** Where the app opens. */
    fun startDestination(hasCompletedOnboarding: Boolean): String =
        if (hasCompletedOnboarding) CAPTURE else WELCOME

    /**
     * The flag for an install that predates it. Before the guide existed there were two flags
     * (cover seen, setup done); someone who had both has finished onboarding and must not be shown
     * the guide after updating. [stored] is null until the new flag has ever been written.
     */
    fun hasCompleted(stored: Boolean?, legacySeenWelcome: Boolean, legacySetupDone: Boolean): Boolean =
        stored ?: (legacySeenWelcome && legacySetupDone)

    /** What the guide's buttons and the back button do on [page] (0-based). */
    sealed interface GuideMove {
        data class ToPage(val page: Int) : GuideMove
        /** Skip, or the last page's button: on to the wizard, which has its own Skip. */
        data object ToSetup : GuideMove
        /** Back on the first page: return to the cover. */
        data object ToCover : GuideMove
    }

    fun onNext(page: Int): GuideMove = if (page >= GUIDE_PAGES - 1) GuideMove.ToSetup else GuideMove.ToPage(page + 1)
    fun onBack(page: Int): GuideMove = if (page <= 0) GuideMove.ToCover else GuideMove.ToPage(page - 1)
    fun onSkip(): GuideMove = GuideMove.ToSetup

    /** Pages 1 and 2 offer Skip at the top right; the last page's own button leads on. */
    fun showsSkip(page: Int): Boolean = page < GUIDE_PAGES - 1
}
