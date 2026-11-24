package com.music.bitchord.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.auth.clearSpotifyWebSession
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import com.music.bitchord.ui.components.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.roundToInt

@Composable
fun AccountAndScrobblingScreen(
    signedIn: Boolean,
    account: Account?,
    channelName: String?,
    onSignIn: () -> Unit,
    onSwitchChannel: () -> Unit,
    onSignOut: () -> Unit,
    onOpenListenBrainzLogin: () -> Unit,
    onOpenLastfmLogin: () -> Unit,
    onOpenDiscord: () -> Unit,
    onOpenSpotify: () -> Unit,
    contentPadding: PaddingValues,
    /** BitChord Audio: pCloud's settings page, and its sign-in (Sources). */
    onOpenPcloud: () -> Unit = {},
    onOpenPcloudAccount: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmSessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val lastfmPrimaryArtistOnly by AppSettings.lastfmPrimaryArtistOnly.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    val listenBrainzPrimaryArtistOnly by AppSettings.listenBrainzPrimaryArtistOnly.collectAsStateWithLifecycle()
    val discordToken by AppSettings.discordToken.collectAsStateWithLifecycle()
    val discordUsername by AppSettings.discordUsername.collectAsStateWithLifecycle()
    val discordRpcEnabled by AppSettings.discordRpcEnabled.collectAsStateWithLifecycle()
    val spotifyConnected by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val youtubePremiumAudio by AppSettings.youtubePremiumAudio.collectAsStateWithLifecycle()
    var lastfmLoginOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.account_integrations))

        // One card: who is signed in, which profile is listening, and the way
        // out, with the explanation of profiles underneath.
        SettingsGroup(
            footer = if (signedIn) stringResource(R.string.account_profiles_help) else null,
            topSpacing = 0.dp,
        ) {
            AccountCard(
                signedIn = signedIn,
                account = account,
                onSignIn = onSignIn,
                onClick = onSwitchChannel,
                grouped = true,
            )
            if (signedIn) {
                FullWidthDivider()
                SettingsRow(
                    icon = SfIcons.SwitchAccount,
                    title = stringResource(R.string.listen_as),
                    subtitle = channelName ?: stringResource(R.string.default_youtube_profile),
                    onClick = onSwitchChannel,
                )
                // With the account because it is a claim about the account: only a
                // signed-in Premium session is offered the higher Opus rung.
                RowDivider()
                SettingsRow(
                    icon = SfIcons.AutoAwesome,
                    title = stringResource(R.string.youtube_premium_audio),
                    subtitle = stringResource(R.string.youtube_premium_audio_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = youtubePremiumAudio,
                            onCheckedChange = AppSettings::setYoutubePremiumAudio,
                        )
                    },
                    onClick = { AppSettings.setYoutubePremiumAudio(!youtubePremiumAudio) },
                )
                FullWidthDivider()
                DestructiveRow(label = stringResource(R.string.sign_out), onClick = onSignOut)
            }
        }

        SettingsGroup(
            header = stringResource(R.string.spotify),
            footer = stringResource(R.string.spotify_connect_subtitle),
        ) {
            SettingsRow(
                iconPainter = painterResource(Res.drawable.spotify_logo),
                title = stringResource(R.string.spotify),
                subtitle = stringResource(
                    if (spotifyConnected.isNotBlank()) R.string.connected else R.string.tap_to_connect,
                ),
                onClick = onOpenSpotify,
            )
            if (spotifyConnected.isNotBlank()) {
                FullWidthDivider()
                DestructiveRow(
                    label = stringResource(R.string.spotify_disconnect),
                    onClick = {
                        clearSpotifyWebSession()
                        AppSettings.setSpotifySpdcToken("")
                    },
                )
            }
        }

        SettingsGroup(
            header = stringResource(R.string.rich_presence),
            footer = stringResource(R.string.rich_presence_footer),
        ) {
            SettingsRow(
                icon = ImageVector.vectorResource(R.drawable.ic_discord),
                title = "Discord",
                subtitle = when {
                    discordToken.isEmpty() -> stringResource(R.string.tap_to_connect)
                    !discordRpcEnabled -> stringResource(R.string.connected_presence_off)
                    discordUsername.isNotEmpty() -> stringResource(R.string.sharing_as, discordUsername)
                    else -> stringResource(R.string.sharing_listens)
                },
                onClick = onOpenDiscord,
            )
        }

        if (AppSettings.scrobblingAvailable) {
            SettingsGroup(
                header = stringResource(R.string.scrobbling),
                footer = stringResource(R.string.scrobbling_footer),
            ) {
                SettingsRow(
                    icon = SfIcons.Cloud,
                    title = "ListenBrainz",
                    subtitle = if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) {
                        stringResource(R.string.connected)
                    } else {
                        stringResource(R.string.enter_token_to_enable)
                    },
                    trailing = {
                        IosSwitch(
                            checked = listenBrainzEnabled,
                            onCheckedChange = { checked ->
                                if (checked && listenBrainzToken.isBlank()) {
                                    onOpenListenBrainzLogin()
                                } else {
                                    AppSettings.setListenBrainzEnabled(checked)
                                }
                            },
                        )
                    },
                    onClick = onOpenListenBrainzLogin,
                )
                if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) {
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.scrobble_primary_artist_only),
                        subtitle = stringResource(R.string.scrobble_primary_artist_only_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = listenBrainzPrimaryArtistOnly,
                                onCheckedChange = AppSettings::setListenBrainzPrimaryArtistOnly,
                            )
                        },
                        onClick = { AppSettings.setListenBrainzPrimaryArtistOnly(!listenBrainzPrimaryArtistOnly) },
                    )
                }
                RowDivider()
                SettingsRow(
                    icon = SfIcons.History,
                    title = "Last.fm",
                    subtitle = if (lastfmSessionKey.isNotBlank()) {
                        stringResource(R.string.signed_in_as, lastfmUsername)
                    } else {
                        stringResource(R.string.tap_to_sign_in)
                    },
                    trailing = {
                        IosSwitch(
                            checked = lastfmEnabled,
                            onCheckedChange = { checked ->
                                if (checked && lastfmSessionKey.isBlank()) {
                                    lastfmLoginOpen = true
                                } else {
                                    AppSettings.setLastfmEnabled(checked)
                                }
                            },
                        )
                    },
                    onClick = {
                        if (lastfmSessionKey.isNotBlank()) {
                            AppSettings.setLastfmSessionKey("")
                            AppSettings.setLastfmUsername("")
                            AppSettings.setLastfmEnabled(false)
                            AppSettings.setLastfmScrobbleEnabled(false)
                            AppSettings.setLastfmNowPlaying(false)
                        } else {
                            lastfmLoginOpen = !lastfmLoginOpen
                        }
                    },
                )
                // BitChord Audio: Last.fm's sign-in right here, under its row (no pop-up).
                if (lastfmSessionKey.isBlank() && lastfmLoginOpen) {
                    RowDivider()
                    LastfmInlineLogin(onDone = { lastfmLoginOpen = false })
                }
                if (lastfmEnabled && lastfmSessionKey.isNotBlank()) {
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.scrobble_tracks),
                        subtitle = stringResource(R.string.scrobble_tracks_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = lastfmScrobbleEnabled,
                                onCheckedChange = AppSettings::setLastfmScrobbleEnabled,
                            )
                        },
                        onClick = { AppSettings.setLastfmScrobbleEnabled(!lastfmScrobbleEnabled) },
                    )
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.scrobble_primary_artist_only),
                        subtitle = stringResource(R.string.scrobble_primary_artist_only_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = lastfmPrimaryArtistOnly,
                                onCheckedChange = AppSettings::setLastfmPrimaryArtistOnly,
                            )
                        },
                        onClick = { AppSettings.setLastfmPrimaryArtistOnly(!lastfmPrimaryArtistOnly) },
                    )
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.now_playing),
                        subtitle = stringResource(R.string.lastfm_now_playing_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = lastfmNowPlayingEnabled,
                                onCheckedChange = AppSettings::setLastfmNowPlaying,
                            )
                        },
                        onClick = { AppSettings.setLastfmNowPlaying(!lastfmNowPlayingEnabled) },
                    )
                    // BitChord Audio: also what other music apps play (LastWave's idea).
                    RowDivider()
                    val otherContext = androidx.compose.ui.platform.LocalContext.current
                    val otherOn by com.music.bitchord.data.scrobbling.OtherAppsScrobble.enabled.collectAsStateWithLifecycle()
                    val otherSeen by com.music.bitchord.data.scrobbling.OtherAppsScrobble.lastSeen.collectAsStateWithLifecycle()
                    val toggleOther = { on: Boolean ->
                        com.music.bitchord.data.scrobbling.OtherAppsScrobble.setEnabled(on)
                        if (on && !com.music.bitchord.data.scrobbling.OtherAppsScrobble.hasAccess(otherContext)) {
                            com.music.bitchord.data.scrobbling.OtherAppsScrobble.openAccessSettings(otherContext)
                        }
                    }
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.other_scrobble_title),
                        subtitle = otherSeen?.let { stringResource(R.string.other_scrobble_last, it) }
                            ?: stringResource(R.string.other_scrobble_subtitle),
                        trailing = { IosSwitch(checked = otherOn, onCheckedChange = toggleOther) },
                        onClick = { toggleOther(!otherOn) },
                    )
                }
            }

            if (lastfmEnabled && lastfmSessionKey.isNotBlank()) {
                SettingsGroup(header = stringResource(R.string.scrobble_timing)) {
                    SliderRow(
                        icon = SfIcons.Tune,
                        title = stringResource(R.string.min_song_duration),
                        subtitle = stringResource(R.string.min_song_duration_subtitle),
                        value = "${scrobbleMinDuration}s",
                        sliderValue = scrobbleMinDuration.toFloat(),
                        onSliderValue = { AppSettings.setScrobbleMinDuration(it.roundToInt()) },
                        valueRange = 15f..120f,
                        steps = 20,
                    )
                    RowDivider()
                    SliderRow(
                        icon = SfIcons.Tune,
                        title = stringResource(R.string.scrobble_delay),
                        subtitle = stringResource(R.string.scrobble_delay_subtitle),
                        value = "${(scrobbleDelayPercent * 100).roundToInt()}%",
                        sliderValue = scrobbleDelayPercent,
                        onSliderValue = { AppSettings.setScrobbleDelayPercent(it) },
                        valueRange = 0.1f..1.0f,
                        steps = 8,
                    )
                    RowDivider()
                    SliderRow(
                        icon = SfIcons.Tune,
                        title = stringResource(R.string.max_delay),
                        subtitle = stringResource(R.string.max_delay_subtitle),
                        value = "${scrobbleDelaySeconds}s",
                        sliderValue = scrobbleDelaySeconds.toFloat(),
                        onSliderValue = { AppSettings.setScrobbleDelaySeconds(it.roundToInt()) },
                        valueRange = 30f..300f,
                        steps = 26,
                    )
                }
            }
        } else {
            // Left in place rather than dropped: a section that simply vanishes
            // reads as a feature that never existed, and this one is coming back.
            // The rows are dimmed and inert via `enabled = false`.
            SettingsGroup(
                header = stringResource(R.string.scrobbling),
                footer = stringResource(R.string.scrobbling_paused_footer),
            ) {
                SettingsRow(
                    icon = SfIcons.Cloud,
                    title = "ListenBrainz",
                    subtitle = stringResource(R.string.back_in_future_version),
                    enabled = false,
                )
                RowDivider()
                SettingsRow(
                    icon = SfIcons.History,
                    title = "Last.fm",
                    subtitle = stringResource(R.string.back_in_future_version),
                    enabled = false,
                )
            }
        }

        // BitChord Audio: each service with everything it needs in one place.
        SettingsGroup(header = stringResource(R.string.lastfm_keys_header), footer = stringResource(R.string.lastfm_keys_footer)) {
            com.music.bitchord.ui.components.ServiceKeyFields(com.music.bitchord.data.ServiceKeys.Service.LASTFM)
        }

        SettingsGroup(header = "pCloud", footer = stringResource(R.string.pcloud_group_footer)) {
            val webdavUser by AppSettings.webdavUsername.collectAsStateWithLifecycle()
            SettingsRow(
                icon = SfIcons.Cloud,
                title = stringResource(R.string.pcloud_account_row),
                subtitle = webdavUser.takeIf { it.isNotBlank() }?.let { stringResource(R.string.signed_in_as, it) }
                    ?: stringResource(R.string.tap_to_sign_in),
                onClick = onOpenPcloudAccount,
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.Storage,
                title = stringResource(R.string.pcloud_settings_title),
                subtitle = stringResource(R.string.pcloud_settings_subtitle),
                onClick = onOpenPcloud,
            )
        }

        SettingsGroup(header = "VK", footer = stringResource(R.string.vk_group_footer)) {
            var vkIn by remember { mutableStateOf(com.music.bitchord.data.video.VkVideo.signedIn()) }
            val opening by com.music.bitchord.data.video.VkVideo.loginOpen.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(opening) { vkIn = com.music.bitchord.data.video.VkVideo.signedIn() }
            SettingsRow(
                icon = SfIcons.PlayCircle,
                title = stringResource(R.string.vk_account),
                subtitle = stringResource(if (vkIn) R.string.vk_signed_in else R.string.vk_signed_out),
                onClick = { if (!vkIn) com.music.bitchord.data.video.VkVideo.loginOpen.value = true },
            )
            if (vkIn) {
                FullWidthDivider()
                DestructiveRow(label = stringResource(R.string.sign_out), onClick = {
                    com.music.bitchord.data.video.VkVideo.signOut(); vkIn = false
                })
            }
        }

        SettingsGroup(header = stringResource(R.string.song_info_services_header), footer = stringResource(R.string.service_keys_intro)) {
            com.music.bitchord.ui.components.ServiceKeyFields(com.music.bitchord.data.ServiceKeys.Service.DISCOGS)
            RowDivider()
            com.music.bitchord.ui.components.ServiceKeyFields(com.music.bitchord.data.ServiceKeys.Service.FANART)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** BitChord Audio: Last.fm's sign-in inline — name, password, a button, and what went wrong. */
@Composable
private fun LastfmInlineLogin(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = user, onValueChange = { user = it }, singleLine = true,
            label = { androidx.compose.material3.Text(stringResource(R.string.username)) },
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.OutlinedTextField(
            value = pass, onValueChange = { pass = it }, singleLine = true,
            label = { androidx.compose.material3.Text(stringResource(R.string.password)) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { androidx.compose.material3.Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        androidx.compose.material3.Button(
            enabled = !loading && user.isNotBlank() && pass.isNotBlank(),
            onClick = {
                loading = true
                error = null
                scope.launch {
                    try {
                        if (AppSettings.lastfmApiKey.value.isBlank() || AppSettings.lastfmSecret.value.isBlank()) {
                            error = context.getString(R.string.lastfm_keys_needed)
                            return@launch
                        }
                        com.music.bitchord.data.scrobbling.LastFM.initialize(
                            apiKey = AppSettings.lastfmApiKey.value,
                            secret = AppSettings.lastfmSecret.value,
                        )
                        com.music.bitchord.data.scrobbling.LastFM.getMobileSession(user.trim(), pass)
                            .onSuccess { auth ->
                                AppSettings.setLastfmSessionKey(auth.session.key)
                                AppSettings.setLastfmUsername(auth.session.name)
                                AppSettings.setLastfmEnabled(true)
                                com.music.bitchord.data.ServiceKeys.applyLastFm()
                                onDone()
                            }
                            .onFailure { e -> error = e.message ?: context.getString(R.string.login_failed) }
                    } catch (e: Exception) {
                        error = e.message ?: context.getString(R.string.login_failed)
                    } finally {
                        loading = false
                    }
                }
            },
        ) { androidx.compose.material3.Text(stringResource(R.string.sign_in)) }
    }
}

/** A hairline across the whole card, for setting a destructive row apart from the rows above it. */
@Composable
private fun FullWidthDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
}
