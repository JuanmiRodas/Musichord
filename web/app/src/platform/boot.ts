/**
 * BitChord Audio: what has to run before the app on a given platform. Only
 * the LG webOS build (VITE_PLATFORM=webos) does anything here; the web build
 * drops it all at build time.
 */
import { installWebOs } from './webos';

if (import.meta.env.VITE_PLATFORM === 'webos') installWebOs();
