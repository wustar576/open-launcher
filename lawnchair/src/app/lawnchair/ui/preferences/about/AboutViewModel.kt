package app.lawnchair.ui.preferences.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Backs the About screen. Open Launcher does not fetch anything from the network here (no
 * contributor list, no update checker) -- everything is derived from local build metadata.
 */
class AboutViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val prefs: PreferenceManager = PreferenceManager.getInstance(application)

    val uiState: StateFlow<AboutUiState>
        field = MutableStateFlow(AboutUiState())

    init {
        uiState.update {
            it.copy(
                versionName = if (prefs.hideVersionInfo.get()) {
                    prefs.pseudonymVersion.get() + " (pseudonym)"
                } else {
                    BuildConfig.VERSION_NAME
                },
                commitHash = BuildConfig.COMMIT_HASH,
            )
        }
    }
}
