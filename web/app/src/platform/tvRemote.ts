/**
 * BitChord Audio — the TV, driven from the phone (webos/).
 *
 * The companion service relays what a paired phone asks (play these songs from
 * here, pause, seek, these lyrics, this video …) as server-sent events; this
 * applies them to the player. The other way, the player's state is posted
 * back every second or so, which is what the phone shows.
 *
 * The TV works on its own all the same: this only listens.
 */
import { queryClient } from '@/lib/queryClient';
import type { LyricLine } from '@/lyrics/model';
import { parseEnhancedLrc, parseLrc } from '@/lyrics/parse';
import type { LyricsResult } from '@/lyrics/repository';
import type { LyricsSourceId } from '@/lyrics/sources';
import { writeTtml } from '@/lyrics/ttml';
import type { Track } from '@/data/music/types';
import { usePlayer } from '@/state/player';
import { onServiceInfo, SERVICE, type ServiceInfo } from './webos';

interface PhoneTrack {
  videoId: string;
  title: string;
  artist: string;
  album?: string | null;
  durationSec?: number | null;
  thumbnail?: string | null;
}

type Command =
  | { type: 'play'; tracks: PhoneTrack[]; index: number; positionSec?: number; title?: string }
  | { type: 'pause' }
  | { type: 'resume' }
  | { type: 'toggle' }
  | { type: 'next' }
  | { type: 'previous' }
  | { type: 'seek'; positionSec: number }
  | { type: 'volume'; value: number }
  | { type: 'jump'; index: number }
  | { type: 'lyrics'; videoId: string; lrc: string; label?: string; offsetMs?: number }
  | { type: 'video'; videoId: string; title?: string; artist?: string }
  | { type: 'toast'; text: string };

function toTrack(t: PhoneTrack): Track {
  return {
    videoId: t.videoId,
    title: t.title,
    artists: t.artist ? t.artist.split(',').map((name) => ({ name: name.trim(), id: null })) : [],
    album: t.album ? { name: t.album, id: null } : null,
    durationSec: t.durationSec ?? null,
    explicit: false,
    thumbnail: t.thumbnail ?? null,
    index: null,
    plays: null,
  };
}

// ---- lyrics sent from the phone: they win over the TV's own lookup ----

const phoneLyrics = new Map<string, LyricsResult>();

function lyricsFromLrc(text: string, label: string, offsetMs: number): LyricsResult | null {
  const wordTimed = /<\d{1,2}:\d{2}/.test(text);
  let lines = wordTimed ? parseEnhancedLrc(text) : parseLrc(text);
  if (!lines.length) return null;
  if (offsetMs) {
    // The phone's shift (positive: later) moved into the times.
    const shift = (ms: number) => Math.max(0, ms + offsetMs);
    const move = (l: LyricLine): LyricLine => ({
      ...l,
      timeMs: shift(l.timeMs),
      sungUntilMs: l.sungUntilMs === null ? null : shift(l.sungUntilMs),
      words: l.words.map((w) => ({
        ...w,
        startMs: shift(w.startMs),
        endMs: shift(w.endMs),
        syllables: w.syllables.map((y) => ({ ...y, startMs: shift(y.startMs), endMs: shift(y.endMs) })),
      })),
      background: l.background ? move(l.background) : null,
    });
    lines = lines.map(move);
  }
  return {
    source: 'lrclib' as LyricsSourceId,
    label,
    lines,
    ttml: writeTtml(lines),
    wordSynced: wordTimed,
    synced: true,
  };
}

function applyPhoneLyrics(): void {
  for (const query of queryClient.getQueryCache().findAll({ queryKey: ['lyrics'] })) {
    const videoId = query.queryKey[1] as string | undefined;
    const mine = videoId ? phoneLyrics.get(videoId) : undefined;
    if (mine && query.state.data !== mine) queryClient.setQueryData(query.queryKey, mine);
  }
}

// ---- small on-screen notes: the pairing code, what a phone did ----

let note: HTMLDivElement | null = null;
let noteTimer: ReturnType<typeof setTimeout> | null = null;

function show(text: string, ms = 4000): void {
  if (!note) {
    note = document.createElement('div');
    note.style.cssText =
      'position:fixed;right:32px;bottom:32px;z-index:99999;padding:14px 22px;border-radius:14px;' +
      'background:rgba(0,0,0,.78);color:#fff;font:500 22px/1.35 system-ui,sans-serif;max-width:46vw;' +
      'transition:opacity .3s;pointer-events:none;white-space:pre-line';
    document.body.appendChild(note);
  }
  note.textContent = text;
  note.style.opacity = '1';
  if (noteTimer) clearTimeout(noteTimer);
  noteTimer = setTimeout(() => {
    if (note) note.style.opacity = '0';
  }, ms);
}

let info: ServiceInfo | null = null;

function pairingText(): string {
  if (!info) return 'BitChord Audio: starting the phone link…';
  const where = info.addresses[0] ? `${info.addresses[0]}` : 'this TV';
  return `Connect your phone: BitChord Audio → Speakers → ${info.name}\nCode ${info.pin}  ·  ${where}`;
}

// ---- commands ----

function seekWhenLoaded(videoId: string, positionSec: number): void {
  const started = Date.now();
  const tick = () => {
    const s = usePlayer.getState();
    if (s.nowPlaying?.videoId === videoId && (s.status === 'playing' || s.status === 'paused')) {
      s.seek(positionSec);
      return;
    }
    if (Date.now() - started < 20_000) setTimeout(tick, 300);
  };
  tick();
}

function run(command: Command): void {
  const player = usePlayer.getState();
  switch (command.type) {
    case 'play': {
      if (!command.tracks.length) return;
      const index = Math.min(Math.max(command.index, 0), command.tracks.length - 1);
      player.playTracks(command.tracks.map(toTrack), index, { title: command.title || 'From your phone', type: 'queue' });
      if (command.positionSec && command.positionSec > 1) seekWhenLoaded(command.tracks[index]!.videoId, command.positionSec);
      return;
    }
    case 'pause':
      if (player.status === 'playing') player.togglePlay();
      return;
    case 'resume':
      if (player.status !== 'playing') player.togglePlay();
      return;
    case 'toggle':
      return player.togglePlay();
    case 'next':
      return player.next();
    case 'previous':
      return player.previous();
    case 'seek':
      return player.seek(command.positionSec);
    case 'jump':
      return player.jumpTo(command.index);
    case 'volume':
      return player.setVolume(Math.min(Math.max(command.value, 0), 1));
    case 'lyrics': {
      const result = lyricsFromLrc(command.lrc, command.label || 'From your phone', command.offsetMs ?? 0);
      if (!result) return;
      phoneLyrics.set(command.videoId, result);
      applyPhoneLyrics();
      return;
    }
    case 'video':
      void player.playVideo(command.videoId, { title: command.title, artist: command.artist });
      return;
    case 'toast':
      show(command.text);
      return;
  }
}

// ---- state, for the phone ----

let lastPosted = '';

function postState(): void {
  const s = usePlayer.getState();
  const audio = document.querySelector('audio');
  const state = {
    playing: s.status === 'playing',
    status: s.status,
    nowPlaying: s.nowPlaying
      ? {
          videoId: s.nowPlaying.videoId,
          title: s.nowPlaying.title,
          artist: s.nowPlaying.artist,
          artworkUrl: s.nowPlaying.artworkUrl,
        }
      : null,
    positionSec: audio ? audio.currentTime : 0,
    durationSec: s.nowPlaying?.durationSec ?? 0,
    volume: s.volume,
    index: s.index,
    queue: s.queue.slice(Math.max(0, s.index - 5), s.index + 50).map((e) => ({
      videoId: e.track.videoId,
      title: e.track.title,
      artist: e.track.artists.map((a) => a.name).join(', '),
    })),
  };
  const text = JSON.stringify(state);
  // Position moves every second; the rest only when it changes.
  if (text === lastPosted && !state.playing) return;
  lastPosted = text;
  fetch(`${SERVICE}/tv/state`, { method: 'POST', headers: { 'content-type': 'text/plain' }, body: text }).catch(() => {});
}

export function startTvRemote(): void {
  onServiceInfo((next) => {
    const first = !info;
    info = next;
    if (first) show(pairingText(), 15_000);
  });
  // The pairing code again on the blue button (or the "1" key).
  window.addEventListener('keydown', (e) => {
    if (e.keyCode === 406 || e.key === 'ColorF3Blue') show(pairingText(), 15_000);
  });
  queryClient.getQueryCache().subscribe((event) => {
    if (event.type === 'added' || event.type === 'updated') {
      const key = event.query.queryKey;
      if (key[0] === 'lyrics' && phoneLyrics.has(key[1] as string) && event.query.state.data !== phoneLyrics.get(key[1] as string)) {
        queueMicrotask(applyPhoneLyrics);
      }
    }
  });

  const connect = () => {
    const events = new EventSource(`${SERVICE}/tv/commands`);
    events.addEventListener('command', (e) => {
      try {
        run(JSON.parse((e as MessageEvent).data) as Command);
        setTimeout(postState, 300);
      } catch {
        /* a command this version doesn't know */
      }
    });
    events.onerror = () => {
      events.close();
      setTimeout(connect, 3000);
    };
  };
  connect();
  // Its info too, for the pairing code, in case the Luna answer came before we listened.
  const askInfo = () =>
    fetch(`${SERVICE}/tv/info`)
      .then((r) => r.json())
      .then((i: ServiceInfo) => {
        const first = !info;
        info = i;
        if (first) show(pairingText(), 15_000);
      })
      .catch(() => setTimeout(askInfo, 2000));
  setTimeout(askInfo, 1500);
  setInterval(postState, 1000);
}
