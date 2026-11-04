# BitChord Audio — on hold

Things decided or built but paused, to pick up later. (Kept in the app's repo,
`docs/ON_HOLD.md`; the app copies it to your pCloud `.bitchord-audio/README-on-hold.md`.)

Last updated: 4 November 2026.

---

## 1. BitChord Connect — our own app on the speaker (built, not switched on)

**What it is.** Our own Cast receiver: the speaker holds the whole queue itself,
like Spotify. It plays on from song to song without the phone, keeps the links
for the next 6 songs ready, and any phone that opens the app shows and controls
the same music. The Nest Hub / Google Home next and previous buttons drive the
same queue. A song whose link breaks is retried through the phone; with no phone
connected it waits instead of skipping.

**State.** Built in v1.9.99, untested on a real speaker. While no App ID is set,
casting works exactly as before (Google's default player, the phone feeding the
speaker 8 songs ahead).

- Speaker app: `docs/receiver/index.html` in the Musichord repo.
- Phone side: `ConnectLink.kt`, `CastConnect` (App ID setting).

**To switch it on:**

1. GitHub Pages: Musichord repo → Settings → Pages → "Deploy from a branch" →
   `main`, folder `/docs` → Save. The app then lives at
   `https://juanmirodas.github.io/Musichord/receiver/`.
2. Google Cast developer account: <https://cast.google.com/publish> —
   **one-time, non-refundable $5 (may show as €5)**, paid with your Google
   payment method; registration can take up to 48 hours.
   - It is needed even just for you: the App ID and testing devices come with
     the account. No need to **publish** (that's only for other people's speakers).
3. In the console: Add new application → **Custom Receiver**, name BitChord,
   URL above → note the 8-character **App ID**.
4. Devices: add each speaker's **serial number** (Google Home app → device →
   Settings → Device information) — Nest Hub and every speaker you cast to.
   Takes about an hour to become ready.
5. In the app: Settings → **BitChord Connect (own speaker app)** → App ID →
   close the app completely and open it again.

---

## 2. Voice control from the speakers

- **Works once playing** (with or without the paid app): "Hey Google, pause /
  resume / volume" to the speaker; next / previous with BitChord Connect.
- **Works today from the phone**: "Hey Google, play [artist] on BitChord" to the
  phone; if casting, it goes to the speaker.
- **Not possible directly**: "Hey Google, play … on BitChord" to the speaker —
  Google only starts music on its speakers from partner services (Spotify,
  YouTube Music, Deezer…); the paid account doesn't change that.

**Workaround (speaker → automation → phone → BitChord casts):**

- **Home Assistant** (most reliable): Home Assistant linked to Google Home; a
  script appears as a "scene" ("Hey Google, activate BitChord"); it uses the
  Home Assistant companion app to send a command to BitChord on the phone,
  which plays and casts to the speaker. Needs Home Assistant at home (Raspberry
  Pi / NAS) and the phone on the same Wi-Fi.
- **IFTTT + Tasker** (no Home Assistant): phrase → IFTTT → Tasker → BitChord.
  More moving parts; check what IFTTT's Google Assistant trigger allows.
- **To build in BitChord** when picked up: a small command interface any
  automation can call — play liked songs / album / playlist / artist, cast to
  [speaker], next, pause, stop.

---

## 3. Logs for debugging

- Repo: `JuanmiRodas/bitchord-logs` (private), connected for Claude to read.
- In the app: Settings → **Send the log to GitHub** → repo
  `JuanmiRodas/bitchord-logs` + a fine-grained token (that repo only,
  Contents: read and write). Then "Send now" after anything goes wrong.
- The session log is also saved to pCloud `.bitchord-audio/logs/` every 5 minutes.

---

## 4. Later

- Browser version of the app (would join the same speaker queue through
  BitChord Connect, like Spotify's web player).
