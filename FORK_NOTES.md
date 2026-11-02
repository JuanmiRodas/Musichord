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
