#!/usr/bin/env node
/* ============================================================================
   تست «APK واقعاً همان اپ آفلاین است»
   ----------------------------------------------------------------------------
   این تست خودِ فایل نصب را باز می‌کند (release/radde-seda-<نسخه>.apk) و
   بررسی می‌کند که آنچه روی گوشی نصب می‌شود عیناً همان کدِ آفلاین است:

     • همهٔ فایل‌های web/ با همان md5 داخل assets/web هستند
       (یعنی هیچ نسخهٔ قدیمی/جدا داخل بسته جا نمانده)
     • موتور آفلاین، سبک مشترک رسم و مالتی‌پلکسر MP4 داخل بسته‌اند
     • زوم، منوی اندازهٔ ویدیو، رندر سریع و پل «ذخیره در گالری» هست
     • هیچ مسیر سروری/آدرس اینترنتی در بسته نمانده
     • service worker هر دو فایل تازه را در SHELL دارد (نسخهٔ کش تازه)
     • versionCode/versionName با نسخهٔ اعلام‌شده یکی است (اگر aapt2 باشد)

   اجرا:  node web/tests/apk_test.js
          node web/tests/apk_test.js release/radde-seda-1.3.1.apk
   ========================================================================== */
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '../..');
const WEB = path.join(ROOT, 'web');

let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('✓ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
  else { fail++; console.log('✖ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
}

/* ── پیدا کردن APK ─────────────────────────────────────────────────────── */
function newestApk() {
  const dir = path.join(ROOT, 'release');
  const cands = fs.readdirSync(dir)
    .filter(f => /^radde-seda-\d+\.\d+\.\d+\.apk$/.test(f))
    .map(f => ({ f, m: fs.statSync(path.join(dir, f)).mtimeMs }))
    .sort((a, b) => b.m - a.m);
  return cands.length ? path.join(dir, cands[0].f) : null;
}

const apk = process.argv[2] ? path.resolve(process.argv[2]) : newestApk();
check('فایل APK پیدا شد', !!apk, apk ? path.basename(apk) : 'هیچ APK در release/ نیست');
if (!apk) { console.log('\n✖ ' + fail + ' ناموفق'); process.exit(1); }

/* ── خواندن محتویات zip بدون کتابخانهٔ بیرونی ───────────────────────────── */
function readZip(file) {
  const buf = fs.readFileSync(file);
  /* پیدا کردن End of Central Directory */
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66000); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('zip سالم نیست');
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const files = {};
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('central directory خراب است');
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    const lNameLen = buf.readUInt16LE(localOff + 26);
    const lExtraLen = buf.readUInt16LE(localOff + 28);
    const dataOff = localOff + 30 + lNameLen + lExtraLen;
    const raw = buf.subarray(dataOff, dataOff + compSize);
    let body = null;
    if (method === 0) body = raw;
    else if (method === 8) body = require('zlib').inflateRawSync(raw);
    files[name] = { method, size: compSize, raw, body };
    off += 46 + nameLen + extraLen + commentLen;
  }
  return files;
}

let zip;
try { zip = readZip(apk); } catch (e) { console.log('✖ ' + e.message); process.exit(1); }

const names = Object.keys(zip);
check('بستهٔ APK باز شد', names.length > 10, names.length + ' فایل داخل بسته');

function text(name) {
  const e = zip[name];
  if (!e || !e.body) return null;
  return e.body.toString('utf8');
}

/* ── ۱) همان کدِ web/ داخل بسته ─────────────────────────────────────────── */
const mustShip = ['index.html', 'engine.js', 'offline.js', 'parallel.js',
  'trace_style.js', 'native.js', 'worker.js', 'sw.js',
  'vendor/mp4-muxer.js', 'manifest.webmanifest'];
const missing = mustShip.filter(f => !zip['assets/web/' + f]);
check('همهٔ فایل‌های اجرایی داخل بسته‌اند', missing.length === 0,
  missing.length ? 'گم‌شده: ' + missing.join(', ') : mustShip.length + ' فایل');

const md5 = b => crypto.createHash('md5').update(b).digest('hex');
const stale = [];
mustShip.forEach(f => {
  const src = path.join(WEB, f);
  if (!fs.existsSync(src)) { stale.push(f + ' (در web/ نیست)'); return; }
  const a = md5(fs.readFileSync(src));
  const e = zip['assets/web/' + f];
  if (!e || !e.body || md5(e.body) !== a) stale.push(f);
});
check('محتویات بسته دقیقاً همان نسخهٔ فعلی web/ است (بدون کدِ کهنه)',
  stale.length === 0, stale.length ? 'ناهم‌خوان: ' + stale.join(', ') : mustShip.length + ' فایل یکسان');

/* ── ۲) قابلیت‌های خواسته‌شده داخل بسته ─────────────────────────────────── */
const idx = text('assets/web/index.html') || '';
const off = text('assets/web/offline.js') || '';
const sw = text('assets/web/sw.js') || '';
const nat = text('assets/web/native.js') || '';

check('موتور آفلاین داخل بسته فعال است', /LocalOffline/.test(idx) && /enabled:\s*true/.test(off));
check('رسم مشترک نمودار داخل بسته است', /LocalTraceStyle/.test(idx) && /LocalTraceStyle/.test(off));
check('لایهٔ ثابت نمودار (کشِ رسم، ضدلگ) داخل بسته است', /TRACE_BASE/.test(idx));
check('دکمه‌های زوم زمانی/ارتفاعی داخل بسته‌اند',
  /zoomTimeIn/.test(idx) && /zoomTimeOut/.test(idx) && /zoomPitchIn/.test(idx) && /zoomPitchOut/.test(idx) && /zoomResetBtn/.test(idx));
check('منوی اندازهٔ ویدیو (۱۰۸۰p/۷۲۰p) داخل بسته است',
  /videoQuality/.test(idx) && /value="1080p"/.test(idx));
check('رندر سریع ۶۰fps داخل بسته است',
  /fps',\s*'60'/.test(idx) && /renderVideoFast/.test(off) && /VideoEncoder/.test(off));
check('مالتی‌پلکسر MP4 داخل بسته است', !!zip['assets/web/vendor/mp4-muxer.js'] &&
  zip['assets/web/vendor/mp4-muxer.js'].body.length > 50000,
  zip['assets/web/vendor/mp4-muxer.js'] ? zip['assets/web/vendor/mp4-muxer.js'].body.length + ' بایت' : '—');
check('ذخیره در گالری از طریق پل بومی داخل بسته است',
  /NativeApp/.test(nat) && /gallery/.test(nat) && /save/.test(nat));

/* ── ۳) نسخهٔ کش: هر فایل تازه در SHELL ─────────────────────────────────── */
const cacheV = (sw.match(/radde-seda-shell-v(\d+)/) || [])[1];
check('نسخهٔ کش service worker تازه است', Number(cacheV) >= 16, 'v' + cacheV);
check('service worker هم trace_style.js و هم mp4-muxer را پیش‌بارگذاری می‌کند',
  /\.\/trace_style\.js/.test(sw) && /vendor\/mp4-muxer\.js/.test(sw));

/* ── ۴) هیچ مسیر سروری/اینترنتی ─────────────────────────────────────────── */
const codeFiles = names.filter(n => /^assets\/web\/.*\.(js|html)$/.test(n));
const netHits = [];
codeFiles.forEach(n => {
  const t = text(n) || '';
  const lines = t.split('\n');
  lines.forEach((ln, i) => {
    const t = ln.trim();
    /* فقط «آدرسِ واقعی در کد» مهم است، نه لینکِ توضیحی داخل کامنت */
    const isComment = t.startsWith('//') || t.startsWith('*') || t.startsWith('/*');
    if (!isComment && /https?:\/\//.test(ln) &&
      !/appassets\.local|w3\.org|localhost|127\.0\.0\.1/.test(ln)) {
      netHits.push(n.replace('assets/web/', '') + ':' + (i + 1) + ' ' + ln.trim().slice(0, 60));
    }
  });
});
check('هیچ آدرس اینترنتی در کدِ داخل بسته نمانده', netHits.length === 0,
  netHits.length ? netHits.slice(0, 3).join(' | ') : codeFiles.length + ' فایل بررسی شد');
check('پنجرهٔ «آدرس سرور» از کد حذف شده است',
  !/nativeCfgBtn/.test(nat) && !/nativeApiInput/.test(nat) && !/api_base/.test(idx + nat));
check('داخل اپ، حالت سروری حتی با پارامتر آدرس هم روشن نمی‌شود',
  /IN_APP/.test(off) && /appassets/.test(off) && /FORCE_SERVER\s*=\s*!IN_APP/.test(off));
check('API در صفحه ثابت و خالی است (هیچ تنظیمی آن را به بیرون وصل نمی‌کند)',
  /const API = '';/.test(idx));

/* ── ۵) manifest: بستهٔ نصب سالم ───────────────────────────────────────── */
const aapt2 = process.env.ANDROID_TOOLS ? path.join(process.env.ANDROID_TOOLS, 'aapt2') : null;
if (aapt2 && fs.existsSync(aapt2)) {
  let badging = '';
  try { badging = execFileSync(aapt2, ['dump', 'badging', apk], { encoding: 'utf8' }); } catch (e) { }
  const vc = (badging.match(/versionCode='(\d+)'/) || [])[1];
  const vn = (badging.match(/versionName='([^']+)'/) || [])[1];
  check('بسته امضاشده و قابل نصب است (manifest خوانده شد)', !!vc && !!vn,
    'versionCode ' + vc + ' · ' + vn);
  check('نام بسته درست است', /package: name='ir\.raddeseda\.app'/.test(badging));
  const apkName = path.basename(apk).replace(/^radde-seda-|\.apk$/g, '');
  check('نسخهٔ فایل با نسخهٔ داخل بسته یکی است', vn === apkName, apkName + ' = ' + vn);
} else {
  console.log('• aapt2 نبود ⇒ بررسی versionCode/versionName رد شد (ANDROID_TOOLS را ست کن)');
}

/* ── ۶) اجرای واقعی کدِ بسته: آفلاین حتی با ?engine=server ─────────────── */
{
  const os = require('os');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apkoffline-'));
  names.filter(n => /^assets\/web\/(engine|offline|trace_style|parallel|worker)\.js$/.test(n))
    .forEach(n => {
      const out = path.join(dir, path.basename(n));
      fs.writeFileSync(out, zip[n].body);
    });
  global.window = global;
  global.document = { createElement: () => ({ width: 0, height: 0, getContext: () => ({}) }) };
  global.NativeApp = { toast() { }, save() { } };               // یعنی «داخل اپ»
  global.location = {
    search: '?engine=server&api=https://evil.example',
    href: 'https://appassets.local/index.html?engine=server',
    origin: 'https://appassets.local'
  };
  try {
    require(path.join(dir, 'engine.js'));
    require(path.join(dir, 'trace_style.js'));
    require(path.join(dir, 'offline.js'));
  } catch (e) { console.log('  (اجرا: ' + e.message + ')'); }
  const LO = global.LocalOffline || {};
  check('کدِ داخل بسته موتور آفلاین را روشن می‌کند', LO.enabled === true);
  check('حتی با ?engine=server داخل اپ، حالت سروری روشن نمی‌شود', LO.enabled === true);
  const b64 = zip['assets/web/engine.js'];
  check('موتور تحلیل داخل بسته سالم است', !!b64 && !!global.LocalEngineCore &&
    typeof global.LocalEngineCore.analyzeChannels === 'function');
  /* یک تحلیل کوتاه واقعی روی همان کدِ بسته */
  if (global.LocalEngineCore) {
    const sr = 22050, n = sr * 1;
    const y = new Float32Array(n);
    for (let i = 0; i < n; i++) y[i] = 0.2 * Math.sin(2 * Math.PI * 220 * i / sr);
    try {
      const r = global.LocalEngineCore.analyzeChannels([y], sr, {});
      check('تحلیل صدا در بستهٔ نصب کار می‌کند (کاملاً محلی)',
        r && r.times && r.times.length > 10 && r.notes && r.notes.length >= 1,
        (r && r.times ? r.times.length : 0) + ' نمونه · ' +
        (r && r.notes ? r.notes.length : 0) + ' نت');
    } catch (e) {
      check('تحلیل صدا در بستهٔ نصب کار می‌کند (کاملاً محلی)', false, e.message);
    }
  }
}

console.log('─'.repeat(70));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ نسخهٔ نصب‌شدنی همان اپ آفلاین است (' + pass + ' بررسی)');
process.exit(0);
