package mihon.sync.auth

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Receives the OAuth redirect from the browser and finishes linking the Google account.
 *
 * The intent filter's scheme is a manifest placeholder, because Google requires the redirect scheme
 * to be the reversed OAuth client ID, which is build-specific. See `app/build.gradle.kts`.
 */
@HasMemberInjections
class GoogleDriveLoginActivity : BaseActivity() {

    @Inject private lateinit var googleDriveAuth: GoogleDriveAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph.inject(this)

        setComposeContent { LoadingScreen() }

        val data = intent.data
        if (data == null) {
            returnToSettings()
            return
        }

        lifecycleScope.launch {
            try {
                googleDriveAuth.handleRedirect(data)
                toast(stringResource(MR.strings.sync_login_success))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Google Drive sign-in failed" }
                toast(e.message ?: stringResource(MR.strings.sync_login_failed))
            }
            returnToSettings()
        }
    }

    private fun returnToSettings() {
        finish()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }
}
