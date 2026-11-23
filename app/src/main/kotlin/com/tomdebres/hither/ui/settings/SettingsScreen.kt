package com.tomdebres.hither.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.BuildConfig
import com.tomdebres.hither.R
import com.tomdebres.hither.data.AccountMethod
import com.tomdebres.hither.ui.components.FocusOnArrival
import com.tomdebres.hither.ui.theme.HitherListItem
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.SettingsScaffold
import com.tomdebres.hither.ui.theme.SettingsSection
import com.tomdebres.hither.ui.theme.SettingsValueRow
import com.tomdebres.hither.ui.theme.Tokens

// Settings and About as the Apple TV app's Settings draws its own (H2's X5; tvOS's `SettingsView`, `AboutView`): the mark
// on the left, a list of rows on the right (`SettingsScaffold`). Privacy is one `ReadingView` (A5, `PrivacyScreen.kt`).

/**
 * Settings (tvOS's `SettingsView`): Hither's mark on the left; on the right who is signed in and on which server (its
 * name, its address under it without the scheme: tvOS's F37); About and the TV's Admin Account (M5), each opening its
 * page; then Sign Out, red words by its role, which asks first (`ConfirmSignOut`, Cancel focused). While the admin is
 * active, "Switch Back to {family}" comes first and takes focus, one Select from the avatar, and the account shown is
 * the approver's, as Seerr shows whoever is signed in (93, 109). Diagnostics is About's (tvOS's S41): for Tom, not the
 * family. Focus arrives on About, or on the row come back from.
 */
@Composable
fun SettingsScreen(
    displayName: String,
    email: String?,
    serverTitle: String,
    serverAddress: String,
    /** The account's way in, for what Sign Out says signing in again takes. */
    method: AccountMethod = AccountMethod.LOCAL,
    onAbout: () -> Unit,
    onSignOut: () -> Unit,
    onAdmin: (() -> Unit)? = null,
    backFromAdmin: Boolean = false,
    /** While the TV's admin is active, the family's name for the Switch Back row (DECISIONS 93); null for none. */
    switchBackTo: String? = null,
    /** The approver's name while the admin is active, for the note under Switch Back. */
    activeAs: String? = null,
    onSwitchBack: () -> Unit = {},
) {
    val about = remember { FocusRequester() }
    val admin = remember { FocusRequester() }
    val switchBack = remember { FocusRequester() }
    val signOut = remember { FocusRequester() }
    var asking by remember { mutableStateOf(false) }
    FocusOnArrival(
        when {
            backFromAdmin && onAdmin != null -> admin
            // the admin active: the way back first, where focus arrives (one Select from the avatar)
            switchBackTo != null -> switchBack
            else -> about
        },
    )
    Box(Modifier.fillMaxSize()) {
        Page(PageKind.Pushed(stringResource(R.string.settings), titleTag = "settings heading")) {
            SettingsScaffold {
                if (switchBackTo != null) {
                    item(key = "switch back") {
                        SettingsSection(footer = activeAs?.let { stringResource(R.string.admin_active_as, it) }, footerTag = "switch back note") {
                            // Focus moves to About before the row goes with the admin: a focused row removed would leave
                            // focus on nothing.
                            HitherListItem(
                                headline = stringResource(R.string.admin_switch_back_to, switchBackTo),
                                onClick = {
                                    runCatching { about.requestFocus() }
                                    onSwitchBack()
                                },
                                modifier = Modifier.focusRequester(switchBack),
                                tag = "switch back",
                            )
                        }
                    }
                }
                item(key = "account") {
                    SettingsSection(header = stringResource(R.string.account), headerTag = "account header") {
                        SettingsValueRow(
                            label = stringResource(R.string.signed_in_as),
                            value = displayName,
                            detail = email?.takeIf { it.isNotBlank() && it != displayName },
                            tag = "signed in as",
                            valueTag = "account name",
                        )
                        // The server by its name, its address under it without the scheme: "http://" is the
                        // repository's word, not the family's (tvOS's F37).
                        SettingsValueRow(
                            label = stringResource(R.string.server),
                            value = serverTitle,
                            detail = serverPlace(serverAddress),
                            tag = "server",
                        )
                    }
                }
                item(key = "pages") {
                    SettingsSection {
                        HitherListItem(stringResource(R.string.about), onAbout, Modifier.focusRequester(about), tag = "about", chevron = true)
                        if (onAdmin != null) {
                            HitherListItem(stringResource(R.string.admin_account), onAdmin, Modifier.focusRequester(admin), tag = "admin account", chevron = true)
                        }
                    }
                }
                item(key = "sign out") {
                    SettingsSection {
                        // destructive by role: red words, never a fill (rule 2)
                        HitherListItem(stringResource(R.string.sign_out), { asking = true }, Modifier.focusRequester(signOut), tag = "sign out", destructive = true)
                    }
                }
            }
        }
        ConfirmSignOut(asking, method, opener = signOut, onConfirm = onSignOut, onCancel = { asking = false })
    }
}

/**
 * A server's address as the family reads it (tvOS's `SettingsView.address`): the host, its port and any path, without
 * the scheme or a trailing slash ("http://192.0.2.10:5055" is "192.0.2.10:5055"). Anything it cannot read, as it is.
 */
fun serverPlace(baseUrl: String): String {
    val uri = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return baseUrl
    val host = uri.host ?: return baseUrl
    if (uri.scheme == null) return baseUrl
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return host + port + path
}

/**
 * About (tvOS's `AboutView`, `docs/attribution.md`): Hither's mark on the left; on the right the version, Privacy and
 * Diagnostics as rows, that Hither is unofficial as their footer, then the acknowledgements: TMDB's logo and notice word
 * for word, and the credits. Focus arrives on Privacy, the first row that opens something, or on Diagnostics come back
 * from. Diagnostics is here, not on Settings: it is for Tom, not the family (tvOS's S41).
 */
@Composable
fun AboutScreen(onPrivacy: () -> Unit, onDiagnostics: () -> Unit = {}, backFromDiagnostics: Boolean = false) {
    val privacy = remember { FocusRequester() }
    val diagnostics = remember { FocusRequester() }
    FocusOnArrival(if (backFromDiagnostics) diagnostics else privacy)
    Page(PageKind.Pushed(stringResource(R.string.about), titleTag = "about heading")) {
        SettingsScaffold(markTag = "about wordmark") {
            item(key = "about") {
                SettingsSection(footer = stringResource(R.string.unofficial), footerTag = "unofficial") {
                    SettingsValueRow(stringResource(R.string.version_label), BuildConfig.VERSION_NAME, tag = "version")
                    HitherListItem(stringResource(R.string.privacy), onPrivacy, Modifier.focusRequester(privacy), tag = "privacy", chevron = true)
                    // For Tom, not the family (DECISIONS 107).
                    HitherListItem(stringResource(R.string.diagnostics), onDiagnostics, Modifier.focusRequester(diagnostics), tag = "diagnostics", chevron = true)
                }
            }
            item(key = "acknowledgements") {
                SettingsSection(
                    header = stringResource(R.string.acknowledgements),
                    headerTag = "acknowledgements",
                    footer = stringResource(R.string.credits),
                    footerTag = "credits",
                ) {
                    // TMDB's logo beside its notice, the app's own mark the page's most prominent (TMDB's terms).
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Image(
                            painterResource(R.drawable.tmdb_logo),
                            contentDescription = stringResource(R.string.tmdb),
                            modifier = Modifier.height(11.dp).testTag("tmdb logo"),
                        )
                        BasicText(
                            stringResource(R.string.tmdb_notice),
                            style = HitherType.Caption.copy(color = Tokens.Text),
                            modifier = Modifier.testTag("tmdb notice"),
                        )
                    }
                }
            }
        }
    }
}
