import { readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import legacy from '@vitejs/plugin-legacy';
import { defineConfig, loadEnv, type Plugin } from 'vite';
import { webosCss } from './webos-css.ts';

// The development extension's id, pinned by its manifest `key`
// (web/extension/dev-key/). Read at build time so the page can message it.
function devExtensionId(): string {
  try {
    return readFileSync(new URL('../extension/dev-key/extension-id.txt', import.meta.url), 'utf8').trim();
  } catch {
    return '';
  }
}

// The party server's address stays out of the repo: .env.local here, a
// Cloudflare Pages variable in production. Without it Listen Together can't
// make a party, so a production build stops instead of shipping that.
function requirePartyServer(mode: string): void {
  const env = loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)), 'VITE_');
  if (!env.VITE_PARTY_SERVER_URL?.trim()) {
    throw new Error('VITE_PARTY_SERVER_URL is not set (app/.env.local, or the Cloudflare Pages environment).');
  }
}

export default defineConfig(({ command, mode }) => {
  // BitChord Audio: `vite build --mode webos` is the LG TV build (webos/): loaded
  // from files on the TV, by a Chromium as old as 79 (webOS 6), with a local
  // service in place of the extension — see src/platform/webos.ts.
  const webos = mode === 'webos';
  if (command === 'build' && !webos) requirePartyServer(mode);
  return {
    ...(webos ? { base: './' } : {}),
    plugins: [
      react(),
      tailwindcss(),
      ...(webos
        ? [
            // No ES modules (file:// pages can't load them on the TV) and the
            // polyfills an old Chromium needs, through SystemJS chunks.
            legacy({ targets: ['chrome >= 79'], renderModernChunks: false }) as Plugin[],
            webosCss(),
          ]
        : []),
    ],
    define: {
      DEV_EXTENSION_ID: JSON.stringify(devExtensionId()),
    },
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    // AutoMix's analysis worker (playback/automix): essentia.js's 2021-era
    // Emscripten glue breaks under dep pre-bundling, and the worker is a module.
    optimizeDeps: { exclude: ['essentia.js'] },
    worker: { format: webos ? 'iife' : 'es' },
    server: {
      // The extension's externally_connectable and DNR rules name this origin
      // exactly, so the port must not drift to 5174 when 5173 is busy.
      port: 5173,
      strictPort: true,
    },
  };
});
