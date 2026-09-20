package app.lawnchair.ui.preferences.navigation

import kotlinx.serialization.Serializable

private const val URI = "openlauncher://settings"

/**
 * Represents a route in the Lawnchair preferences navigation graph.
 *
 * This sealed interface is the base for all navigation destinations within the preferences.
 * Each implementing object or data class defines a specific screen or action.
 *
 * The `@Serializable` annotation indicates that this interface and its implementations
 * can be serialized, which is useful for state saving and deep linking.
 */
@Serializable
sealed interface PreferenceRoute

/**
 * determines whether this is one of the root routes shown in the preference dashboard
 */
@Serializable
sealed interface PreferenceRootRoute : PreferenceRoute

@Serializable
sealed interface PreferenceDeepLink {
    val deepLink: String
}

// Misc routes

@Serializable
data object Root : PreferenceRootRoute

@Serializable
data object Dummy : PreferenceRootRoute

// Top-level destinations
@Serializable
data object HomeScreen : PreferenceRootRoute, PreferenceDeepLink {
    override val deepLink = "$URI/home-screen"
}

@Serializable
data object AppDrawer : PreferenceRootRoute, PreferenceDeepLink {
    override val deepLink = "$URI/app-drawer"
}

// Note: the standalone Gestures settings page/route was removed as part of settings-UI
// pruning (spec §7). Only the "pick an app" sub-route (GesturesPickApp, below) remains,
// since it is used by the double-tap gesture control embedded in Home Screen preferences.

@Serializable
data object About : PreferenceRootRoute, PreferenceDeepLink {
    override val deepLink = "$URI/about"
}

@Serializable
data object Predictions : PreferenceRoute, PreferenceDeepLink {
    override val deepLink = "$URI/predictions"
}

@Serializable
data object DismissedPredictionApps : PreferenceRoute

// Home Screen section routes
@Serializable
data object HomeScreenGrid : PreferenceRoute, PreferenceDeepLink {
    override val deepLink = "$URI/home-screen-grid"
}

@Serializable
data object HomeScreenPopupEditor : PreferenceRoute, PreferenceDeepLink {
    override val deepLink = "$URI/home-screen-popup-editor"
}

// App Drawer section routes
@Serializable
data object AppDrawerHiddenApps : PreferenceRoute, PreferenceDeepLink {
    override val deepLink = "$URI/app-drawer-hidden-apps"
}

@Serializable
data object AppDrawerFolder : PreferenceRoute, PreferenceDeepLink {
    override val deepLink = "$URI/app-drawer-folder"
}

@Serializable
data class AppDrawerAppListToFolder(val id: Int) : PreferenceRoute

// Gestures section routes
@Serializable
data object GesturesPickApp : PreferenceRoute

// About section routes
@Serializable
data object AboutLicenses : PreferenceRoute, PreferenceDeepLink {
    override val deepLink = "$URI/about-licenses"
}

// Data/Action oriented routes (might be used across sections or are specific actions)
// These are intentionally not prefixed as per your instruction,
// as they might be used across different sections or are standalone actions.
@Serializable
data class SelectIcon(
    // assuming componentKey is a ComponentKey.toString()
    val componentKey: String,
) : PreferenceRoute

// default to empty
@Serializable
data class IconPicker(val packageName: String = "") : PreferenceRoute

@Serializable
data class ColorSelection(val prefKey: String) : PreferenceRoute

@Serializable
data object CreateBackup : PreferenceRoute, PreferenceDeepLink {
    override val deepLink = "$URI/create-backup"
}

@Serializable
data class RestoreBackup(val base64Uri: String) : PreferenceRoute

@Serializable
data class RestoreNovaBackup(val base64Uri: String) : PreferenceRoute
