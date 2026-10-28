#!/usr/bin/env node
/* ============================================================================
   تست رندرر «ردِّ صدا» و مسیر آفلاین در Node (بدون مرورگر)
   ----------------------------------------------------------------------------
   یک بوم تقلبی (stub) می‌سازد که همهٔ فراخوانی‌های 2D context را ثبت می‌کند،
   سپس TraceRenderer را روی دادهٔ یک تحلیل واقعی اجرا می‌کند و بررسی می‌کند:
     • هیچ استثنایی رخ ندهد
     • پس‌زمینه/خطوط نت/منحنی/نقطه/برچسب‌ها کشیده شوند
     • PNG در پایان تولید شود (toBlob صدا زده شود)

   اجرا: node web/tests/render_test.js
   ========================================================================== */
'use strict';
const fs = require('fs');
const path = require('path');

const calls = [];
function makeGradient() {
  return { addColorStop: () => { calls.push('gradStop'); } };
}
function makeCtx() {
  const ctx = {
    canvas: null,
    _stack: [],
    beginPath: () => calls.push('beginPath'),
    moveTo: () => calls.push('moveTo'),
    lineTo: () => calls.push('lineTo'),
    arc: () => calls.push('arc'),
    arcTo: () => calls.push('arcTo'),
    closePath: () => calls.push('closePath'),
    stroke: () => calls.push('stroke'),
    fill: () => calls.push('fill'),
    fillRect: () => calls.push('fillRect'),
    clearRect: () => calls.push('clearRect'),
    drawImage: () => calls.push('drawImage'),
    fillText: (t) => { calls.push('text:' + t); },
    setLineDash: () => calls.push('dash'),
    createLinearGradient: makeGradient,
    createRadialGradient: makeGradient,
    save: () => { }, restore: () => { }
  };
  return ctx;
}

const fakeDoc = {
  createElement: (tag) => {
    if (tag !== 'canvas') return {};
    const canvas = {
      width: 1280, height: 720,
      getContext: () => { const c = makeCtx(); c.canvas = canvas; return c; },
      toBlob: (cb) => { calls.push('toBlob'); cb(new Blob([new Uint8Array(64)], { type: 'image/png' })); }
    };
    return canvas;
  }
};

global.document = fakeDoc;
global.window = global;
global.location = { search: '', href: 'https://appassets.local/index.html', origin: 'https://appassets.local' };
global.URLSearchParams = URLSearchParams;
global.URL = URL;

require('../engine.js');
require('../offline.js');

const { LocalOffline } = global;
if (!LocalOffline || !LocalOffline.enabled) {
  console.log('✖ لایهٔ آفلاین فعال نشد');
  process.exit(1);
}

// دادهٔ آزمایشی: تحلیل واقعی روی اجرای مصنوعی
const { synthVoice } = require('./synth.js');
const data = synthVoice(null);
const res = global.LocalEngineCore.analyzeChannels(data.channels, data.sr, {});
const report = global.LocalEngineCore.buildReport(res, { file: 'test.wav', original_name: 'test.wav' });

const canvas = fakeDoc.createElement('canvas');
const R = new LocalOffline.TraceRenderer(canvas, res.times, res.freqs, res.notes);

(async () => {
  R.draw(0);
  R.draw(res.duration / 2);
  const blob = await R.toBlob(null);

  const has = (s) => calls.indexOf(s) >= 0;
  const textCount = calls.filter(c => String(c).startsWith('text:')).length;
  const checks = [
    ['پس‌زمینه', has('fillRect')],
    ['خطوط نت', has('stroke')],
    ['منحنی', has('lineTo')],
    ['نقطهٔ درخشان', has('arc') && has('fill')],
    ['برچسب‌ها', textCount >= 3],
    ['PNG', blob && blob.type === 'image/png'],
  ];
  let bad = 0;
  for (const [name, ok] of checks) {
    console.log((ok ? '✓ ' : '✖ ') + name);
    if (!ok) bad++;
  }
  console.log('برچسب‌های کشیده‌شده:', calls.filter(c => String(c).startsWith('text:')).slice(-4).join(' | '));
  console.log('طول گزارش:', JSON.stringify(report.range.highest && report.range.highest.note_name),
    '↕', JSON.stringify(report.range.lowest && report.range.lowest.note_name),
    '· نت‌ها:', report.notes.length);
  if (bad) { console.log('✖', bad, 'مورد ناموفق'); process.exit(1); }
  console.log('✅ رندرر آفلاین سالم است');
})();
