# BitChord Audio for LG webOS TVs

The whole web player (`web/app`) built for the TV, plus a small JS service
that does what the browser extension does on a computer. It works on its own
with the TV remote, and a phone running BitChord Audio can drive it.

Nothing like this existed before: neither upstream BitChord nor any of its
forks has a webOS build. Upstream's `tv/` is an Android TV app (Google TV,
Fire TV, Shield), which webOS can't run. So this starts from upstream's web
player and replaces the extension with a service on the TV.

## What's in the package

| Part | Where | What it does |
|---|---|---|
| App (`com.bitchord.tv`) | `web/app`, built with `--mode webos` | The player: search, library, queue, lyrics, videos, automix. It targets Chromium 79 (webOS 6, 2021) and newer. The build adds legacy JS and lowers Tailwind v4's CSS (cascade layers, `@property`, `oklch`, `color-mix`) in `web/app/webos-css.ts`. |
| Service (`com.bitchord.tv.service`) | `webos/service/service.js` | Answers the bridge the extension normally answers (YouTube, addons, lyrics). Proxies media and API requests with the headers YouTube expects, since a TV app can't set Origin/Referer itself. Also hosts the phone link. |

The app sends every request to another site through `http://127.0.0.1:47615/p?u=…`
(`web/app/src/platform/webos.ts`). That sidesteps CORS and the forbidden headers.

### Remote control

- **Magic Remote:** works as a pointer.
- **Arrow keys:** move between controls.
- **OK:** presses the selected control.
- **Back:** goes back a page. On the first page, it leaves the app.
- **Blue button:** shows the phone pairing code again.

## Phone link

1. Open BitChord Audio on the TV. The TV shows a 6-digit code at the bottom
   right for 15 s. Press the **blue** button to show it again.
2. On the phone, open the player, then the output drawer, then **Cast**. The
   TV appears under **LG TV · BitChord app** (the phone finds it by UDP
   broadcast on port 47616). Tap it and enter the code.

   If your router blocks broadcasts, use **Add a TV by its address** and type
   the IP that the code note shows.
3. The phone's queue moves to the TV. It works the same way as Cast or the
   watch:
   - The phone keeps the queue. The TV plays it and keeps going on its own,
     even with the phone locked or gone.
   - Skip, seek, pause and volume on the phone control the TV.
   - Whatever the TV plays, the phone follows.
   - The lyrics shown on the phone go to the TV, word-timed when the phone has
     word timing, along with your offset. Nudging the offset on the phone moves
     the TV's lyrics too.
   - **Show the video on the TV** plays the song's video in place of the cover.
   - **Back to this phone** brings the music back to the phone at the same
     place.

Only YouTube tracks go to the TV. Your pCloud files and other sources stay on
the phone.

The phone talks to the TV on port 47615. Each paired phone gets a token
(`/remote/pair`), and every `/remote/*` call needs it. The pairing code changes
each time the service starts.

## Installing

LG only lets signed store apps in without one of these:

- **Developer Mode** (any webOS TV):
  1. Install the *Developer Mode* app from the LG Content Store and sign in
     with an LG developer account.
  2. Turn on Dev Mode and Key Server.
  3. Install the package from a computer:

     ```sh
     npm i -g @webos-tools/cli
     ares-setup-device            # add the TV: its IP, port 9922, user prisoner
     ares-novacom --device tv --getkey
     ares-install --device tv bitchord-tv.ipk
     ```

  Dev Mode expires after 50 hours unless you renew it in the app. When it
  expires, the TV removes the app.
- **Homebrew Channel** (rooted TVs, webosbrew.org): install the `.ipk` from
  its app, or with `ares-install`. Nothing expires.

## Getting the package

- **From CI:** every push that touches `web/` or `webos/` builds the package.
  `.github/workflows/webos.yml` publishes it to the `webos-latest`
  pre-release as `bitchord-tv.ipk`.
- **Locally:** run `webos/build.sh 1.0.0`, which writes
  `webos/out/com.bitchord.tv_1.0.0_all.ipk`.

## Not tested on a real TV yet

| Area | What's been checked | Still unchecked |
|---|---|---|
| App | Boots, lays out and routes its requests through the service in desktop Chrome. | Real Chromium 79. |
| Service | Runs under Node (bridge, proxy, pairing). | The TV's Node (webOS 6 has Node 12) and LAN listening on a real set. |
| Phone side | Builds in CI. | Hasn't met a TV yet. |

The first thing to look at if something fails is the TV's logs:

```sh
ares-inspect --device tv --app com.bitchord.tv
ares-log --device tv
```
