// Минимальный service worker: только для установки как PWA, без офлайн-кеша.
// Аудио и API всегда идут в сеть - кешировать их тут нечем и незачем.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {});
