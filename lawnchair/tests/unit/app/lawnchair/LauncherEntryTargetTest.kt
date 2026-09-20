/*
 *     This file is part of Open Launcher, a fork of Lawnchair Launcher.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.lawnchair

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the "Open Launcher" app icon entry decision.
 *
 * This directory is the `test` source set (see `sourceSets` in the root `build.gradle`); run it
 * with `./gradlew testLawnWithQuickstepGithubDebugUnitTest`.
 */
class LauncherEntryTargetTest {

    @Test
    fun `default home always opens settings`() {
        assertEquals(
            LauncherEntryTarget.SETTINGS,
            decideLauncherEntryTarget(isDefaultHome = true, launchedFromSelf = false, homeIsResumed = false),
        )
        assertEquals(
            LauncherEntryTarget.SETTINGS,
            decideLauncherEntryTarget(isDefaultHome = true, launchedFromSelf = true, homeIsResumed = true),
        )
    }

    @Test
    fun `tap from inside open launcher opens settings even when not default`() {
        assertEquals(
            LauncherEntryTarget.SETTINGS,
            decideLauncherEntryTarget(isDefaultHome = false, launchedFromSelf = true, homeIsResumed = false),
        )
    }

    @Test
    fun `home already on screen opens settings even when not default and not launched from self`() {
        assertEquals(
            LauncherEntryTarget.SETTINGS,
            decideLauncherEntryTarget(isDefaultHome = false, launchedFromSelf = false, homeIsResumed = true),
        )
    }

    @Test
    fun `tap from another launcher while not default opens the home screen`() {
        assertEquals(
            LauncherEntryTarget.HOME,
            decideLauncherEntryTarget(isDefaultHome = false, launchedFromSelf = false, homeIsResumed = false),
        )
    }

    @Test
    fun `every combination with at least one true signal opens settings`() {
        val trueFalseCombos = listOf(true, false)
        for (isDefaultHome in trueFalseCombos) {
            for (launchedFromSelf in trueFalseCombos) {
                for (homeIsResumed in trueFalseCombos) {
                    val expected = if (isDefaultHome || launchedFromSelf || homeIsResumed) {
                        LauncherEntryTarget.SETTINGS
                    } else {
                        LauncherEntryTarget.HOME
                    }
                    assertEquals(
                        "isDefaultHome=$isDefaultHome launchedFromSelf=$launchedFromSelf homeIsResumed=$homeIsResumed",
                        expected,
                        decideLauncherEntryTarget(isDefaultHome, launchedFromSelf, homeIsResumed),
                    )
                }
            }
        }
    }
}
