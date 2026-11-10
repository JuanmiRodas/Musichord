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

## Details editor, album cover, song selection (v1.9.93)
- MetadataEdits (data/edits): title/artist/album/albumArtist/year/track/genre by "id:<videoId>" and exact original title|artist key (versions stay separate); local + pCloud .bitchord-audio/metadata.json. ArtworkOverrides.edited + Song.shown(); ShownSongs.version bumps redraws.
- Shown in rows (Common.kt SongRow), NowPlaying title/artist + OLED, MiniPlayer, glass bar; toMediaItem puts edited title/artist/album in MediaMetadata (lock screen, notification, cast) with originals in extras (bitchord.origTitle/Artist/Album) that toSong reads back — Song identity/matching stays original.
- TagWriter: text tags into pCloud files and downloads (MP3 ID3 v2.3 UTF-16 / v2.4 UTF-8; FLAC Vorbis comments) via CoverTags.mp3Text/flacText; m4a/opus not written.
- MetadataEditorDialog (MetadataEditor.open): song menu "Edit details"; album ⋯ "Edit album details" (shared fields only); selection.
- CoverPicker.open(list): album ⋯ "Change cover of all songs", selection.
- ReleaseSelection: album/playlist ⋯ "Select songs" → tap ticks; bar: select all, cover, details, play next, add to queue, cancel.

## Suggestions, like star, other albums (v1.9.95)
- DetailsLookup: iTunes (song/album + album track list), Deezer (search + album: date, genre, tracks), MusicBrainz (release-group/recording, tags), Wikidata (P31 album/song, P175, P577, P136, P361). Filtered by artist; consensus fills empty fields in MetadataEditorDialog (marked "filled in"), chips per field for other answers; album edit can set each song's track number from the found track list.
- AlbumAppearances: same song = exact title + first artist (versions separate), from LikedMusicCache.likedSongs (new flow) + pCloud library; likedCopy(), known(), search() (+ YouTube Music song search).
- LikeStar on album/playlist rows (DetailScreen trailing, after duration): filled when liked here or elsewhere, "+" inside when on several albums; tap → LikeLookup.toggle on the liked copy (or this one).
- Song menu: "Also on" rows for the other albums (open album).

## BitChord Connect — own Cast receiver (v1.9.98+)
- docs/receiver/index.html: CAF v3 receiver holding the whole queue (window from the phone, topped up via "more"/"ended"), playing item by item; links asked of phones ("need" → "stream", viaPhone retry on error), prefetch chain AHEAD 6, waits for a phone instead of skipping when none is connected; broadcasts "state" (with ids/items on hello/connect) every 5 s and on changes; speaker's own next/prev (QUEUE_UPDATE/NEXT/PREV interceptors); disableIdleTimeout. Namespace urn:x-cast:com.bitchord.connect.
- Hosting: GitHub Pages from main /docs (user must enable in repo Settings → Pages); URL https://juanmirodas.github.io/Musichord/receiver/ (raw.githack works meanwhile).
- App: CastConnect (prefs bitchord_cast/connectAppId; CastOptionsProvider uses it, restart needed); CastPlayback delegates to ConnectLink when the session is ours (DMR path untouched): pushes the phone queue when the user moves playback from the phone, else takes up the speaker; phone follows "state".
- Settings → "BitChord Connect (own speaker app)": App ID dialog. User must register a Custom Receiver at cast.google.com/publish ($5 once) and publish it.
- Logs repo JuanmiRodas/bitchord-logs attached (empty until the app's token is set).

## Strict matching + unlink anything (v1.9.101)
- OwnFileMatcher: MAX_DRIFT_SEC 15 → 3 (official-video exception kept); unknown length → only sameArtist; ArtistLinks.linked only with close length. SourceMap MATCHER_VERSION 7, SAAVN_VERSION 4 (old matches redone); JioSaavn album match drift ≤ 3; NotSame respected by saavnSongFor/recordSaavn.
- SongLinks.linksOf(song): stand-in's original (LocalLikes.youTubeOf), pCloud file it plays as, JioSaavn copy, picked copy → song menu "Not the same song" rows; unlinkFrom clears NotSame/SourceMap/saavn/LocalLikes/PreferredSource.
- bitchord-logs repo still empty (app token not set) as of 4 Nov 14:00.

## Logs live + unlink of picked copies (v1.9.103)
- Logs arrive in JuanmiRodas/bitchord-logs (cloned at /home/claude/bitchord-logs): logs/OnePlus-CPH2747/<day>/<start>.log, reports/.
- Case from the log: デイドリームジェネレーション (Matsuko Mawatari) kept playing Ricardo Cruz's JioSaavn cover — a copy picked by hand in the source chooser (PreferredSource.Chosen, spread by AlbumSources.applyToArtist). Unlink now clears Chosen/File for that id; OwnFirst/OwnFileMatcher ignore a Chosen copy blocked by NotSame; AlbumSources.best needs known lengths ≤ 3 s apart.
- To look at: two OutOfMemoryError crashes on 3 Nov (heap 256 MB full) — main thread and binder.

## Cast queue read in full; failed jump retried (v1.9.105)
- From the log: "speaker has the next 8 queued (7 added)" every 20 s — MediaStatus.queueItems only holds a few items, so reconcile saw the queue as short, removed what it saw and appended the rest again: the speaker queue grew with copies (EVERCHiLD came round 3 times). reconcileOnce now uses RemoteMediaClient.mediaQueue item ids (MediaQueue.Callback registered) + a ledger itemId→mediaId (learned on insert/load), removes strays.
- New World → Orion: jumped to New World (served by the phone; "cast stream: Connection reset"), the speaker failed it and moved on by itself. If the target song is left before playing, it's reloaded once (proxyOnly) instead of skipped.
- pCloud API login fails ("1022 Please provide 'code'": 2FA), so pCloud files always go through the phone proxy; proxy's first request probes the whole file for its length (slow).


## MusicBeat port (v1.9.108)
From SamuelAdmand/MusicBeat, chosen by the owner:
- **Dolby edition**: flavor `dolby`, applicationId `com.tencent.qqmusic` (OnePlus/OPPO whitelist Dolby Atmos/Dirac for it). Separate app (own data; use Export/Import), APK `BitChordDolby-v*.apk`, updates itself via `UPDATE_APK_PREFIX`. No watch bundling. Can't coexist with real QQ Music.
- **TagLib** (`io.github.kyant0:taglib`): `TagWriter` writes details, disc no., lyrics, covers into any format (pCloud: download → edit → upload temp → MOVE; downloads in place). `CoverWriter` falls back to it for non-MP3/FLAC.
- **Lyrics in files**: `LyricsKeeper` writes kept lyrics into the LYRICS tag (download at once, pCloud on Wi-Fi; backfills liked songs; prefs `embedded:d:/p:`), setting "Write lyrics into my files". `EmbeddedLyrics` reads via TagLib as last fallback.
- **Apple lyrics**: PaxSenix token now expiry-aware, tries music.apple.com + beta.music.apple.com pages, retries once on refusal. MusicBeat's hard-coded token expired 2026-10-19 — not copied.
- **Fast scroller**: `ui/components/FastScroller.kt` + `fastscroll/*` on DetailScreen (non-artist, ≥40 songs, not searching/selecting); bubble = letter when sorted by title, else song number.

## Queue loop fix (v1.9.109)
Symptom: the same 4–5 songs repeat (local and cast). Cause: in-place queue swaps (OwnFirst/AudioFirst/version swap/upgrades/stand-ins) rebuilt items from `Song`, which has no canonical index/cycle, so the bounded QueueWindow cursor stopped advancing; top-ups and restore-after-process-death re-queued from the stale cursor. Fix: **always use `Player.replaceInQueue` / `withQueueIdentityOf`** (QueueReplace.kt) — never raw `replaceMediaItem`. Safety nets: top-up skipped when the playing CONTEXT item has no canonical index; restore falls back to LastPlayed when the saved current entry lost it. Log: `track selected ... list=i/N cursor=c` (should match).

## Upstream merge: BitChord v1.9.1 (v1.9.112)
Base was upstream b690dc14 (v1.8.2, 30 Oct). Merged tag v1.9.1 (7f45c768) with a real git merge (tag `up-v1.9.1` locally; upstream clone at /home/claude/kushagrasinghx/bitchord). Next time: `git fetch <upstream> <tag>` and `git merge` — history now shares the base.
Kept ours where they collided: versioning, Advanced settings group (upstream folded it into Misc and removed the enum, icon and string — re-added), audio-only beside upstream's "Prefer music-only", JapaneseNames queries in SourceResolver (with upstream's lengthLater), shownSong title + source line under the transport (inside upstream's new `transport` lambda), LyricsOffsetControl now `chromeWhite(1f)` (LocalPlayerTint removed upstream). BitChordAudioDialogs() split out of BitChordApp (JVM 64 KB method limit).
Upstream AddonAuth: addons may require proof of the official signed app (hardware attestation). Opt-in per addon; a fork cannot pass it. Do not try to spoof it.
