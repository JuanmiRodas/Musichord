/**
 * BitChord Audio — the LG webOS TV build (webos/).
 *
 * On the TV there is no browser extension, so the app's companion service
 * (webos/service, a Node process on the TV) takes its place:
 *
 * - every request the page makes to another site goes through the service
 *   (`/p?u=…`), which adds the YouTube headers the extension's rules would
 *   and answers with open CORS headers;
 * - the bridge calls (`ping`, `yt.fetch`, …) go to its `/bridge`
 *   (config.localServiceUrl, see shared/bridge/client.ts).
 *
 * Installed before anything else runs (main.tsx imports it first), so the
 * player, YouTube.js, Shaka and the lyric providers never see the difference.
 * Also: the remote's Back key, and starting the service over the Luna bus.
 */

export const SERVICE = 'http://127.0.0.1:47615';

const LOCAL = /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//i;

function proxied(url: string): string {
  if (!/^https?:\/\//i.test(url) || LOCAL.test(url)) return url;
  return `${SERVICE}/p?u=${encodeURIComponent(url)}`;
}

function absolute(input: string): string {
  try {
    return new URL(input, location.href).href;
  } catch {
    return input;
  }
}

function patchFetch(): void {
  const original = window.fetch.bind(window);
  window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    if (typeof input === 'string' || input instanceof URL) {
      return original(proxied(absolute(String(input))), init);
    }
    const request = input as Request;
    const target = proxied(request.url);
    if (target === request.url) return original(request, init);
    return original(new Request(target, request), init);
  }) as typeof window.fetch;
}

function patchXhr(): void {
  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (this: XMLHttpRequest, method: string, url: string | URL, ...rest: unknown[]) {
    const target = proxied(absolute(String(url)));
    return (open as (...a: unknown[]) => void).call(this, method, target, ...rest);
  } as typeof XMLHttpRequest.prototype.open;
}

interface PalmBridge {
  onservicecallback: (message: string) => void;
  call(uri: string, params: string): void;
}

/** Held for the life of the page: the subscription is what keeps the service running. */
let luna: PalmBridge | null = null;

export interface ServiceInfo {
  pin: string;
  name: string;
  port: number;
  addresses: string[];
}

let infoListeners: Array<(info: ServiceInfo) => void> = [];
export function onServiceInfo(listener: (info: ServiceInfo) => void): void {
  infoListeners.push(listener);
}

function startService(): void {
  const Bridge = (window as unknown as { PalmServiceBridge?: new () => PalmBridge }).PalmServiceBridge;
  if (!Bridge) return;
  luna = new Bridge();
  luna.onservicecallback = (message) => {
    try {
      const answer = JSON.parse(message) as Partial<ServiceInfo> & { returnValue?: boolean };
      if (answer.returnValue && answer.pin) infoListeners.forEach((l) => l(answer as ServiceInfo));
    } catch {
      /* ignore */
    }
  };
  luna.call('luna://com.bitchord.tv.service/start', JSON.stringify({ subscribe: true }));
}

/** The remote's Back: back through the app's pages, and out of the app from the first one. */
function backKey(): void {
  window.addEventListener(
    'keydown',
    (event) => {
      if (event.keyCode !== 461 && event.key !== 'GoBack' && event.key !== 'BrowserBack') return;
      event.preventDefault();
      const atRoot = location.hash === '' || location.hash === '#/' || location.hash === '#';
      if (!atRoot && history.length > 1) history.back();
      else {
        const palm = (window as unknown as { PalmSystem?: { platformBack?: () => void } }).PalmSystem;
        if (palm?.platformBack) palm.platformBack();
        else window.close();
      }
    },
    true,
  );
}

const FOCUSABLE = 'a[href],button:not([disabled]),input,select,textarea,[tabindex]:not([tabindex="-1"]),[role="button"],[role="slider"]';

/**
 * The plain remote's arrows (the Magic Remote's pointer needs nothing): move focus to the nearest
 * visible control in that direction, so every page works without a pointer.
 */
function arrowKeys(): void {
  const dirs: Record<number, [number, number]> = { 37: [-1, 0], 38: [0, -1], 39: [1, 0], 40: [0, 1] };
  window.addEventListener('keydown', (event) => {
    const dir = dirs[event.keyCode];
    if (!dir) return;
    const active = document.activeElement as HTMLElement | null;
    if (active && /^(INPUT|TEXTAREA|SELECT)$/.test(active.tagName) && dir[0] !== 0) return;
    if (active?.getAttribute('role') === 'slider') return;
    const from = active && active !== document.body ? active.getBoundingClientRect() : new DOMRect(0, 0, 0, 0);
    const fx = from.left + from.width / 2;
    const fy = from.top + from.height / 2;
    let best: HTMLElement | null = null;
    let bestScore = Infinity;
    for (const el of Array.from(document.querySelectorAll<HTMLElement>(FOCUSABLE))) {
      if (el === active) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0 || r.bottom < 0 || r.top > innerHeight + 400) continue;
      const dx = r.left + r.width / 2 - fx;
      const dy = r.top + r.height / 2 - fy;
      const along = dx * dir[0] + dy * dir[1];
      if (along <= 4) continue;
      const across = Math.abs(dx * dir[1] + dy * dir[0]);
      const score = along + across * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (!best) return;
    event.preventDefault();
    best.focus({ preventScroll: true });
    best.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  });
  const style = document.createElement('style');
  style.textContent = 'html.webos :focus-visible,html.webos :focus{outline:3px solid #fa2d48!important;outline-offset:2px}';
  document.head.appendChild(style);
}

export function installWebOs(): void {
  arrowKeys();
  patchFetch();
  patchXhr();
  backKey();
  startService();
  document.documentElement.classList.add('webos');
}
