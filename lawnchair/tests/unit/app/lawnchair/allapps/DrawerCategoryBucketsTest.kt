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

package app.lawnchair.allapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the drawer category mapping.
 *
 * NOTE: this source set is not wired into the Gradle build yet — `build.gradle` (owned by another
 * work stream) needs:
 *
 * ```
 * android { sourceSets { test { java.srcDirs = ['lawnchair/tests/unit'] } } }
 * dependencies { testImplementation libs.junit }
 * ```
 *
 * after which `./gradlew testLawnWithQuickstepGithubDebugUnitTest` runs it.
 */
class DrawerCategoryBucketsTest {

    /** The 31 flowerpot files shipped in `lawnchair/assets/flowerpot/`. */
    private val shippedPots = listOf(
        "ART_AND_DESIGN", "AUTO_AND_VEHICLES", "BEAUTY", "BOOKS_AND_REFERENCE", "BUSINESS",
        "COMICS", "COMMUNICATION", "DATING", "EDUCATION", "ENTERTAINMENT", "EVENTS", "FINANCE",
        "FOOD_AND_DRINK", "GAME", "HEALTH_AND_FITNESS", "HOUSE_AND_HOME", "LIBRARIES_AND_DEMO",
        "LIFESTYLE", "MAPS_AND_NAVIGATION", "MEDICAL", "MUSIC", "NEWS", "PARENTING",
        "PERSONALIZATION", "PHOTOGRAPHY", "PRODUCTIVITY", "SHOPPING", "SOCIAL", "SPORTS",
        "TOOLS", "VIDEO", "WEATHER",
    )

    @Test
    fun `every shipped pot maps to a real bucket`() {
        shippedPots.forEach { pot ->
            val bucket = DrawerCategoryBuckets.bucketFor(pot)
            assertTrue(
                "$pot fell through to OTHER",
                bucket != DrawerCategoryBuckets.OTHER,
            )
            assertTrue(
                "$pot maps to unknown bucket $bucket",
                bucket in DrawerCategoryBuckets.displayOrder,
            )
        }
    }

    @Test
    fun `unknown pot falls back to other`() {
        assertEquals(DrawerCategoryBuckets.OTHER, DrawerCategoryBuckets.bucketFor("NO_SUCH_POT"))
    }

    @Test
    fun `bucket lookup is case insensitive`() {
        assertEquals(
            DrawerCategoryBuckets.bucketFor("COMMUNICATION"),
            DrawerCategoryBuckets.bucketFor("communication"),
        )
    }

    @Test
    fun `claim order and display order hold the same buckets`() {
        assertEquals(
            DrawerCategoryBuckets.claimOrder.sorted(),
            DrawerCategoryBuckets.displayOrder.sorted(),
        )
        assertEquals(
            DrawerCategoryBuckets.displayOrder.size,
            DrawerCategoryBuckets.displayOrder.toSet().size,
        )
    }

    @Test
    fun `potsFor is the inverse of bucketFor`() {
        DrawerCategoryBuckets.displayOrder.forEach { bucket ->
            DrawerCategoryBuckets.potsFor(bucket).forEach { pot ->
                assertEquals(bucket, DrawerCategoryBuckets.bucketFor(pot))
            }
        }
        // Every mapped pot is reachable from exactly one bucket.
        val reachable = DrawerCategoryBuckets.displayOrder.flatMap { DrawerCategoryBuckets.potsFor(it) }
        assertEquals(DrawerCategoryBuckets.potToBucket.keys.sorted(), reachable.sorted())
    }

    @Test
    fun `buckets with fewer than two members are folded away`() {
        val members = mapOf(
            DrawerCategoryBuckets.COMMUNICATION to listOf("a", "b"),
            DrawerCategoryBuckets.SOCIAL to listOf("c"),
            DrawerCategoryBuckets.GAMES to emptyList(),
            DrawerCategoryBuckets.TOOLS to listOf("d", "e", "f"),
        )

        val folded = DrawerCategoryBuckets.foldForDisplay(members)

        assertEquals(
            listOf(DrawerCategoryBuckets.COMMUNICATION, DrawerCategoryBuckets.TOOLS),
            folded.map { it.first },
        )
        assertEquals(listOf("d", "e", "f"), folded.last().second)
    }

    @Test
    fun `other is never rendered as a folder`() {
        val members = mapOf(DrawerCategoryBuckets.OTHER to listOf("a", "b", "c"))
        assertTrue(DrawerCategoryBuckets.foldForDisplay(members).isEmpty())
    }

    @Test
    fun `folders come out in display order regardless of input order`() {
        val members = linkedMapOf(
            DrawerCategoryBuckets.SYSTEM to listOf("s1", "s2"),
            DrawerCategoryBuckets.TOOLS to listOf("t1", "t2"),
            DrawerCategoryBuckets.COMMUNICATION to listOf("c1", "c2"),
        )

        assertEquals(
            listOf(
                DrawerCategoryBuckets.COMMUNICATION,
                DrawerCategoryBuckets.TOOLS,
                DrawerCategoryBuckets.SYSTEM,
            ),
            DrawerCategoryBuckets.foldForDisplay(members).map { it.first },
        )
    }

    @Test
    fun `cache key changes with generation, app count and component set`() {
        val base = DrawerCategoryCache.CacheKey(generation = 1, appCount = 10, componentHash = 42)

        assertEquals(base, DrawerCategoryCache.CacheKey(1, 10, 42))
        assertTrue(base != DrawerCategoryCache.CacheKey(2, 10, 42))
        assertTrue(base != DrawerCategoryCache.CacheKey(1, 11, 42))
        assertTrue(base != DrawerCategoryCache.CacheKey(1, 10, 43))
    }
}
