/* ============================================================================
   Service Worker — برنامهٔ آفلاین (و پایهٔ نصب به‌عنوان اپ اندروید / PWA)
   ----------------------------------------------------------------------------
   استراتژی:
   • پوستهٔ رابط (HTML، فونت، آیکون‌ها) با «network-first + کش» ذخیره می‌شود
     تا آفلاین هم باز شود، ولی نسخهٔ جدید هم بلافاصله در دسترس باشد.
   • فایل‌های خروجی کاربر (تصویر/ویدیو/گزارش/صدای آپلودی) هیچ‌وقت کش نمی‌شوند؛
     ذخیرهٔ آن‌ها با دکمهٔ «ذخیره» در خود اپ انجام می‌شود.
   • درخواست‌های API/WebSocket هرگز کش نمی‌شوند (تحلیل همیشه تازه است).
   ========================================================================== */
const CACHE = 'radde-seda-shell-v21';
const SHELL = [
  './',
  './index.html',
  './engine.js',
  './parallel.js',
  './worker.js',
  './trace_style.js',
  './vendor/mp4-muxer.js',
  './offline.js',
  './manifest.webmanifest',
  './fonts/Estedad-Variable.woff2',
  './fonts/Vazirmatn-Variable.woff2',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/maskable-512.png',
  './icons/apple-touch-icon.png'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(SHELL).catch(() => undefined))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function isShellRequest(url) {
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws/')) return false;
  if (/\.(mp4|png|json|wav|mp3|webm|txt)$/i.test(url.pathname) &&
      !url.pathname.includes('/icons/')) return false;
  return true;
}

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws/')) return;
  if (!isShellRequest(url)) return;

  event.respondWith(
    fetch(req)
      .then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy)).catch(() => undefined);
        return res;
      })
      .catch(() => caches.match(req).then(hit => hit || caches.match('./index.html')))
  );
});
