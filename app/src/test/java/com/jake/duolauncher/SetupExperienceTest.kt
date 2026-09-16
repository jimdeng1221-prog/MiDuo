package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupExperienceTest {
    @Test fun freshInstallShowsSetup() {
        assertEquals(SetupEntryDecision.SHOW,
            setupEntryDecision(finished = false, started = false, hadLauncherState = false))
    }

    @Test fun existingInstallStaysSilentWithoutNeedingSetupState() {
        assertEquals(SetupEntryDecision.EXISTING_INSTALL,
            setupEntryDecision(finished = false, started = false, hadLauncherState = true))
    }

    @Test fun startedFreshSetupSurvivesLauncherStateCreationAndRecreation() {
        assertEquals(SetupEntryDecision.SHOW,
            setupEntryDecision(finished = false, started = true, hadLauncherState = true))
    }

    @Test fun finishedSetupStaysDismissedAfterRecreation() {
        assertEquals(SetupEntryDecision.ALREADY_FINISHED,
            setupEntryDecision(finished = true, started = true, hadLauncherState = false))
        assertEquals(SetupEntryDecision.ALREADY_FINISHED,
            setupEntryDecision(finished = true, started = true, hadLauncherState = true))
    }
}
