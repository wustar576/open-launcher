package app.lawnchair.ui.preferences.about

/**
 * Represents the UI state for the "About" screen.
 *
 * Open Launcher's About screen is intentionally minimal: it only shows the app name, version,
 * a link to this project's GitHub repository, and legal/acknowledgement information. It does
 * not fetch anything from the network (no contributor list, no update checker).
 *
 * @param versionName The current version name of the application.
 * @param commitHash The commit hash of the current build.
 */
data class AboutUiState(
    val versionName: String = "",
    val commitHash: String = "",
)
