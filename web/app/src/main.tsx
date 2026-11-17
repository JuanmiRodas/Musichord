// BitChord Audio: first, so the TV build's request routing is in place before anything fetches.
import '@/platform/boot';
import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, createHashRouter, RouterProvider } from 'react-router';
import { queryClient } from '@/lib/queryClient';
import { routes } from '@/routes';
import '@/styles/global.css';

// The TV loads the app from a file: paths live in the hash there.
const router = import.meta.env.VITE_PLATFORM === 'webos' ? createHashRouter(routes) : createBrowserRouter(routes);

if (import.meta.env.VITE_PLATFORM === 'webos') {
  void import('@/platform/tvRemote').then((m) => m.startTvRemote());
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
