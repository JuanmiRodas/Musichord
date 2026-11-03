# BitChord Audio — fork notes (handoff)

Personal fork of BitChord: **JuanmiRodas/Musichord**, app id `com.music.bitchord.audio`,
installed on a OnePlus 15. Owner: juanmirodas@gmail.com. Replies in English (owner mixes Spanish).

## How it's built and shipped
- Every push to `main` runs `.github/workflows/audio.yml`: builds, signs (debug key, SHA-256 1e08a903…5953),
  runs the fork tests (`app/src/test/java/com/music/bitchord/AudioForkTest.kt`, failures are warnings), makes
  bsdiff patches from the last 3 releases and publishes release **v1.9.<run number>** (`*.apk`, `*.sha256`, `*.bspatch`).
- `concurrency: cancel-in-progress` — a new push cancels the running build (that's expected, not a failure).
- The in-app updater (`AppUpdateChecker.kt`) downloads the patch, checks the sha256, falls back to the full APK.
- Occasional CI flake: "Failed to install … ndk" → just re-run (`gh api -X POST …/actions/runs/<id>/rerun-failed-jobs`).
- Commits: author `Claude <noreply@anthropic.com>`. Read build errors from the check-run annotations API.
- Stack: Kotlin/Compose, Media3. Modules `app` (Android), `shared` (data, lyrics providers), `sharedUi` (player UI,
  talks to the app through `PlayerHost` → `AndroidPlayerHost`).

## Owner's preferences
- Ask before big or ambiguous work; otherwise just do it — don't report every step.
- **Never hard-code songs or links** — fixes must be generic and automatic.
- Different versions (live, tribute, cover, remix, acoustic, karaoke, orchestral) are separate songs — never
  merged or sharing a like. A remaster counts as the same recording.
- Japanese: romaji first everywhere, Japanese in brackets in file names.

## Features (where they live)
**Audio-only / playback**
- Never plays video when audio-only is on; swaps to the audio release without pausing (`playback/AudioFirst.kt`).
- Own files first (`playback/OwnFirst.kt`, `data/sources/OwnFileMatcher.kt`): plays your pCloud file instead of
  YouTube when lossless, ≥ the min kbps setting (default 256), or better than YouTube — unless a better source
  (JioSaavn) exists. Mobile data option (default: own files on Wi-Fi only).
- Matching: name → length → artist → album; Japanese/romaji via Kuromoji (`JapaneseReading.kt`, soundKeys);
  learned artist aliases (`ArtistLinks.kt`); album/tribute/live releases rejected (`OwnFileMatcher.otherRecording`).
- Source map (`data/sources/SourceMap.kt`): Liked Music → best file / JioSaavn copy, computed once, saved on the
  phone and in pCloud `.bitchord-audio/source_map.json`. `MATCHER_VERSION` / `SAAVN_VERSION` bumps trigger a
  re-match. JioSaavn misses are retried on the album's track list (`albumPass`).
- Japanese names (`data/sources/JapaneseNames.kt`): a romaji title/artist's Japanese spelling, confirmed by
  sound (Kuromoji read-back) or by a paired catalogue entry — from your own copies (tags, file names, `.lrc`
  `[ti:]/[ar:]`, lyrics folders), the lyrics' own words (`JapaneseReading.spanSounding`), Apple (JP storefront,
  same track id in en/ja), MusicBrainz, Wikipedia (en→ja langlinks, ja search), Vocaloid Lyrics wiki (Fandom),
  VGMdb, NetEase, Google IME. Kept in `japanese_names.json` (phone + pCloud). Used by the source picker search
  ("Also searched as"), the JioSaavn source map (`SAAVN_VERSION` 3) and playback lookups (`SourceResolver`).
- Now Playing source line + picker (`ui/components/NowPlayingSourceDialog.kt`): choose pCloud file / YouTube
  Music / any source search; remembered (`PreferredSource.kt`); checkbox "rest of the album from this source"
  (`AlbumSources.kt`, covers pCloud folders, JioSaavn albums, YouTube Music albums).
- pCloud browser with search box (`ui/components/PcloudBrowser.kt`).
- System media controls/lock screen covers for pCloud songs (`playback/ArtworkBitmapLoader.kt`).

**Covers**
- Cover button beside the source line (and in the source picker) → `ui/components/CoverPickerDialog.kt`:
  covers from `data/covers/CoverSources.kt` (pCloud file/folder, YouTube Music, JioSaavn, Apple JP/US, Deezer,
  Cover Art Archive, NetEase, VGMdb); album songs ticked to share it; "save into pCloud files" (default on)
  rewrites MP3 ID3 / FLAC PICTURE (`CoverTags.kt`, tested in `CoverTagsTest`) via temp upload + MOVE
  (`CoverWriter.kt`). Choice by title+artist in `CoverOverrides.kt` (`covers.json`, phone + pCloud), applied
  through `ArtworkOverrides` (shared `Song.artworkAt`) and `rememberRemoteArtworkUrl`.

**Background (Wi-Fi only, `data/BackgroundWork.kt`, every 30 min while on Wi-Fi)**
- Covers waiting for files (`PendingCoverWrites`; picked on data → deferred), `SourceMap.fill`, lyrics of every
  liked song (`LyricsKeeper`: kept via EditedLyrics + readable `Artist - Title.lrc` in pCloud `Lyrics/`),
  local-only likes liked on YouTube Music (`LikeLinker`).
- Lyrics found once (auto or picked) are kept and never looked up again; "These lyrics are wrong" clears them.
- Source picker: "also the artist's other songs" (`AlbumSources.applyToArtist`).
- Liked Music cleaner (Settings): duplicates (length tolerance, versions toggle, keep best), similar, all
  (by artist/album, filters), bulk un-like (`LikedCleaner.kt`, `LikedCleanerDialog.kt`, `unlikeExactly`).

**Likes**
- Liked Music saved on the phone (`LikedMusicCache.kt`); hearts use the whole saved list.
- Copies (pCloud/JioSaavn/another YouTube upload) of a liked song show liked and un-like the original
  (`LocalLikes.kt` links/aliases, `SourceMap.likedSongFor`). Anything playing from Liked Music shows liked;
  un-liking removes it from the list and the queue. Songs YouTube lacks are liked locally.

**Lyrics**
- Sources: Apple-based, Musixmatch, KuGou, LRCLIB, … plus NetEase, QQ Music (timed) and UtaTen, Uta-Net,
  J-Lyric (untimed, last resort) — `shared/.../lyrics/JapaneseLyrics.kt`. Provider romaji (NetEase/UtaTen) is
  used before the automatic romanizer (`ProvidedRomaji`, local `JapaneseRomanizer`).
- Own lyrics: `.lrc` beside the file and LyricsX folder (`SidecarLyrics.kt`); lookups also try the romaji ↔
  Japanese spelling of title/artist (`LyricNames.kt`, Google IME for kanji).
- Romaji by default (big romaji, small original), translation together with it.
- ±0.5 s shift per song, synced and written as `[offset:]` into the `.lrc` beside your file (`LyricOffsets.kt`).
- "Search lyrics…": all sources, you pick (`LyricAliasEditor.kt`); picked/timed lyrics stored by
  `EditedLyrics.kt` (phone + pCloud `.bitchord-audio/lyrics/`); "Use automatic" undoes.
- Tap-to-time editor: Start line, Break (…), Back, ±5 s (`sharedUi/.../LyricsTimingEditor.kt`).
- "These lyrics are wrong" flags a source per song (`BadLyrics.kt`).
- OLED mode (moon button): black, status bar kept (time + notification icons), nav bar hidden, big clock + date
  with cover/title rotating corners every 30 s, screen kept on by the dialog window's own FLAG_KEEP_SCREEN_ON
  (`sharedUi/.../OledLyrics.kt`).

**Downloads**
- Always best bitrate across sources; JioSaavn when your file is worse; upgrades on Wi-Fi (`DownloadUpgrader.kt`).
- "Download only from" sources + skipped-songs table/CSV (`download/DownloadSkips.kt`).
- Layout `Music/BitChord/Artist[/Album if >3 songs]/N. Title - Artist - Album.ext`, romaji (日本語), existing
  downloads moved once (`download/DownloadLayout.kt`). Cover embedded in each file.

**Other**
- pCloud (EU) library with tags, romaji tables, folder browsing, merged library, settings sync to pCloud
  (`SettingsSync.kt`), playlist export/import, folders (#1017), patch updates, haptics switch, player stays open
  after switching apps.

## Data the owner shared (in the old chat, not in the repo)
- `Liked_Music.json` export (~4,888 tracks) and a `files.tsv` listing of the pCloud music folder — ask for them
  again if a simulation against real data is needed.

## Always-on music screen (v1.9.83)
- `aod/`: AodTrigger (screen off while playing → AodActivity via full-screen-intent notification), AodActivity (over lock screen: song, lyric line, clock/date/notification icons, widgets, prev/play/next/stop, corner rotation + drift, closes on unlock/tap/1 min paused), AodSettings, AodWidgets (+picker), AodWidgetsDialog. Settings → "Music on the always-on screen".
- CurrentLyrics: MainViewModel publishes loaded lyrics for it.
- NotificationIcons: silhouettes painted white; opaque/full-colour small icons fall back to the app icon in colour (was a grey square).

## Cast rejoin after update/crash (v1.9.84)
- CastController.rejoinIfLost() on every app foreground (+5 s after start); matches last speaker by id, route id or name; skips a speaker now on another app (route description not "Default Media Receiver"); scans 3 min; logs to TrackLog.
- CastPlayback: resumed sessions wait for the receiver's status (ADOPT_WAIT_MS) before deciding; receiver track read from queue or mediaInfo; phone follows by id, then title+artist (alias), else inserts the track next to the current one (playlist kept); queued-ahead items rebuilt once (old proxy links die with the process); a rejoined speaker holding nothing of ours is left (forgetAndLeave).

## One-tap cast + notification star (v1.9.85)
- CastController.castToRecent(): recent speaker (KEY_RECENT_ID/NAME, kept after disconnect); State.recentName → CastUi.recentName; RecentCastGlyph (SpeakerGroup) right of the output glyph; notification button ACTION_CAST_RECENT (ic_notification_cast_recent) after the star, before shuffle.
- LikeLookup: notification/widget star reads overrides + LikedMusicCache + LocalLikes (links/liked) like the player; toggle goes through MainViewModel.toggleLike when the app is alive.

## Cast double-play fix (v1.9.86)
- reconcileOnce resolves streams first, then edits only if nothing moved (generation, receiver item, phone item); load() cancels a reconcile in flight. Was: a slow resolve inserted the just-loaded track again (played twice) or the skipped one after the new one (skip came back).
- rejoinIfLost/tryJoin bail on any session (also suspended/connecting).

## Cast slow/skipped after several skips (v1.9.87)
- recoverFrom: no retry/skip while casting (phone-side load failure skipped the song on the speaker).
- onLocalTransition load waits SKIP_SETTLE_MS (450 ms) so rapid skips fetch only the final track.
- IDLE FINISHED < 5 s into a > 30 s track → stream broke: retried once via the phone proxy (CastRouting.proxyOnly) instead of moving on.
- Log: per-track stream resolve time (direct / served by the phone), speaker load time, every speaker move.

## Logs to GitHub + cast hardening (v1.9.88)
- LogUpload: Settings → "Send the log to GitHub" (repo owner/name + fine-grained token, Contents RW, that repo only). Every 2 min when new: logs/<maker-model>/<day>/<start time>.log (same file updated); crash/exit reports once to logs/<device>/reports/. CrashLog also uploads the session log to pCloud .bitchord-audio/logs/ every 5 min.
- Cast: AHEAD 8, inserted one at a time with staleness + exact-tail checks; customData carries the whole song (LastPlayed.songToJson) so an empty/new phone takes up the speaker's whole queue; 20 s heartbeat (requestStatus, phone follows the speaker's track, top-up reconcile).
- Model: speaker = truth for track/position/play state; phone = truth for what comes next.

## Cast survives closing the app (v1.9.90)
- onTaskRemoved: while casting, never stops/disconnects (stopOnTaskRemoved ignored); reopening rejoins (framework resume or rejoinIfLost).
- Speaker leaving a track by itself < 5 s into a > 30 s song (stream failed; Cast queues auto-advance on error) → that track replayed via phone proxy once.
- reconcile: dropped the exact-tail check (status queueItems can be partial → endless re-plans and re-resolves); replans capped at 3 in a row.
- Log: every speaker idle with its reason.
- Logs repo not reachable yet: user must create it and give the Claude GitHub app access, then add_repo.

## Cast skip robustness (v1.9.91)
- CastPlayback.expect(target): after a jump/load the phone shows the new track at its start (BUFFERING, no running clock) until the speaker reports it PLAYING/PAUSED; 15 s stuck → resent via the phone proxy once, then given up (toast). remote.load has a 20 s timeout.
- Resolved streams cached per track (20 min; separate key once proxyOnly).
- resolveForCast: saavncdn.com direct to the speaker (like googlevideo/pCloud).
- CastController.helpResume(): a session resuming after reopen gets an active scan (resume needs discovery); not back in 20 s → endCurrentSession(false) (endingToRejoin keeps the device) + rejoin. Was stuck "connecting" with the speaker idle.
- Upstream kushagrasinghx/BitChord (v1.8.2) cast = same design, older (AHEAD 3, no proxy); forks can't be listed from here (GitHub API scoped, forks page blocked).
