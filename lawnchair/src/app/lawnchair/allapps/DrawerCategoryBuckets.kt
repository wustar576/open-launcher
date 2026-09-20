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

/**
 * Collapses the 31 shipped flowerpot "pots" plus the two synthetic pots
 * ([POT_GOOGLE] / [POT_SYSTEM]) into a small, stable set of drawer categories.
 *
 * This object is deliberately free of any Android dependency so that the mapping,
 * the "fold tiny buckets away" rule and the bucket ordering can be unit tested on
 * a plain JVM.
 */
object DrawerCategoryBuckets {

    // --- bucket keys -------------------------------------------------------

    const val COMMUNICATION = "communication"
    const val SOCIAL = "social"
    const val MEDIA = "media"
    const val PHOTOGRAPHY = "photography"
    const val GAMES = "games"
    const val PRODUCTIVITY = "productivity"
    const val TOOLS = "tools"
    const val SHOPPING = "shopping"
    const val FINANCE = "finance"
    const val TRAVEL = "travel"
    const val LIFESTYLE = "lifestyle"
    const val GOOGLE = "google"
    const val SYSTEM = "system"

    /** Catch-all bucket. Never rendered as a folder: its members only appear in the A-Z list. */
    const val OTHER = "other"

    // --- synthetic pot names ----------------------------------------------

    /** Pseudo pot for `com.google.*` packages. */
    const val POT_GOOGLE = "GOOGLE"

    /** Pseudo pot for packages flagged as system apps. */
    const val POT_SYSTEM = "SYSTEM"

    /**
     * The order in which apps are *claimed*. An app that matches several pots ends up in the
     * first bucket of this list that claims it, so the order is part of the contract.
     *
     * Google / System come first because that mirrors the behaviour users already had in the
     * old "Caddy" drawer layout: a Google or preinstalled system app is grouped by its origin
     * rather than by its Play Store category.
     */
    val claimOrder: List<String> = listOf(
        GOOGLE,
        SYSTEM,
        COMMUNICATION,
        SOCIAL,
        MEDIA,
        PHOTOGRAPHY,
        GAMES,
        PRODUCTIVITY,
        TOOLS,
        SHOPPING,
        FINANCE,
        TRAVEL,
        LIFESTYLE,
    )

    /**
     * The order in which the folders are rendered above the A-Z list.
     * Google / System are pushed to the end because they are the least interesting groups.
     */
    val displayOrder: List<String> = listOf(
        COMMUNICATION,
        SOCIAL,
        MEDIA,
        PHOTOGRAPHY,
        GAMES,
        PRODUCTIVITY,
        TOOLS,
        SHOPPING,
        FINANCE,
        TRAVEL,
        LIFESTYLE,
        GOOGLE,
        SYSTEM,
    )

    /**
     * Pot (flowerpot asset file name, upper case) to bucket key.
     * Pots that are absent from this map fall into [OTHER].
     */
    val potToBucket: Map<String, String> = mapOf(
        POT_GOOGLE to GOOGLE,
        POT_SYSTEM to SYSTEM,

        "COMMUNICATION" to COMMUNICATION,

        "SOCIAL" to SOCIAL,
        "DATING" to SOCIAL,

        "ENTERTAINMENT" to MEDIA,
        "MUSIC" to MEDIA,
        "VIDEO" to MEDIA,
        "COMICS" to MEDIA,

        "PHOTOGRAPHY" to PHOTOGRAPHY,
        "ART_AND_DESIGN" to PHOTOGRAPHY,

        "GAME" to GAMES,

        "PRODUCTIVITY" to PRODUCTIVITY,
        "BUSINESS" to PRODUCTIVITY,
        "BOOKS_AND_REFERENCE" to PRODUCTIVITY,
        "EDUCATION" to PRODUCTIVITY,
        "NEWS" to PRODUCTIVITY,
        "EVENTS" to PRODUCTIVITY,

        "TOOLS" to TOOLS,
        "PERSONALIZATION" to TOOLS,
        "LIBRARIES_AND_DEMO" to TOOLS,
        "WEATHER" to TOOLS,

        "SHOPPING" to SHOPPING,

        "FINANCE" to FINANCE,

        "MAPS_AND_NAVIGATION" to TRAVEL,
        "AUTO_AND_VEHICLES" to TRAVEL,

        "LIFESTYLE" to LIFESTYLE,
        "HEALTH_AND_FITNESS" to LIFESTYLE,
        "MEDICAL" to LIFESTYLE,
        "SPORTS" to LIFESTYLE,
        "BEAUTY" to LIFESTYLE,
        "HOUSE_AND_HOME" to LIFESTYLE,
        "PARENTING" to LIFESTYLE,
        "FOOD_AND_DRINK" to LIFESTYLE,
    )

    /** Smallest number of members a bucket needs before it is rendered as a folder. */
    const val MIN_BUCKET_SIZE = 2

    /** Maps a pot (code) name onto its bucket key, or [OTHER] when the pot is unknown. */
    fun bucketFor(potName: String): String = potToBucket[potName.uppercase()] ?: OTHER

    /** All pots that feed a given bucket, in a stable order. */
    fun potsFor(bucketKey: String): List<String> = potToBucket.entries
        .filter { it.value == bucketKey }
        .map { it.key }
        .sorted()

    /**
     * Turns raw per-bucket membership into the list that is actually rendered:
     *
     *  * buckets are emitted in [displayOrder];
     *  * buckets with fewer than [MIN_BUCKET_SIZE] members are folded into [OTHER];
     *  * [OTHER] is never emitted.
     *
     * The members of each bucket keep the order they were given in.
     */
    fun <T> foldForDisplay(
        members: Map<String, List<T>>,
        minBucketSize: Int = MIN_BUCKET_SIZE,
    ): List<Pair<String, List<T>>> = displayOrder.mapNotNull { key ->
        if (key == OTHER) return@mapNotNull null
        val bucket = members[key].orEmpty()
        if (bucket.size < minBucketSize) null else key to bucket
    }
}
