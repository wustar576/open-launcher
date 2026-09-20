/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.lawnchair.font.googlefonts

import android.content.Context
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Variant-name helpers for fonts served by the system's downloadable-fonts provider.
 *
 * Open Launcher has no font picker (spec §1 puts font selection out of scope), so the bundled
 * ~1.7 MB catalogue of Google Fonts families was dropped along with the picker screen: nothing
 * enumerated it any more, and it was the last thing in the APK naming a proprietary Google
 * typeface. [getFonts] therefore only reports the families the user added by hand through
 * `PreferenceManager2.additionalFonts`.
 *
 * The [Companion] helpers stay because a *stored* font preference from an older install can
 * still name a downloadable family, and [app.lawnchair.font.FontCache] has to be able to
 * resolve it. No network request is involved: the provider is a system content provider.
 */
@LauncherAppSingleton
class GoogleFontsListing @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private suspend fun getAdditionalFonts(): List<String> {
        val prefs = PreferenceManager2.getInstance(context)
        val userFontsString = prefs.additionalFonts.get().first()
        return if (userFontsString.isEmpty()) emptyList() else userFontsString.split(",")
    }

    suspend fun getFonts(): List<GoogleFontInfo> = getAdditionalFonts()
        .map { GoogleFontInfo(it, DEFAULT_VARIANTS) }
        .sorted()

    override fun close() = Unit

    class GoogleFontInfo(val family: String, val variants: List<String>) : Comparable<GoogleFontInfo> {

        override fun compareTo(other: GoogleFontInfo): Int = family.compareTo(other.family)
    }

    companion object {

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getGoogleFontsListing)

        private val DEFAULT_VARIANTS =
            listOf("regular", "italic", "500", "500italic", "700", "700italic")

        fun getWeight(variant: String): String {
            if (variant == "italic") return "400"
            return variant.replace("italic", "").replace("regular", "400")
        }

        fun isItalic(variant: String): Boolean = variant.contains("italic")

        fun buildQuery(family: String, variant: String): String {
            val weight = getWeight(variant)
            val italic = isItalic(variant)
            return "name=$family&weight=$weight&italic=${if (italic) 1 else 0}&besteffort=1"
        }
    }
}
