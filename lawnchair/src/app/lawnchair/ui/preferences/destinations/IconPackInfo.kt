/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.destinations

import android.graphics.drawable.Drawable

/**
 * An installed icon pack (or the "system icons" pseudo entry).
 *
 * Open Launcher has no icon pack *settings screen* any more, but the per-app icon override flow
 * ([SelectIconPreference] -> [IconPickerPreference]) still lets the user pick a single icon out of
 * an installed pack, so the list itself is still produced by `PreferenceViewModel`.
 */
data class IconPackInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
)
