/*
 * آزمونِ بازسازیِ ظرف (fMP4 → MP4ِ معمولی)
 * ----------------------------------------------------------------------
 * MediaRecorder در کروم/WebView خروجیِ «تکه‌ای» می‌دهد:
 *   • mvhd.duration = 0 و هیچ mehd → گالری «۰ ثانیه» نشان می‌دهد یا طول ندارد
 *   • زمان‌بندیِ نمونه‌ها نامنظم (VFR) → پخشِ لگ‌دار
 * این آزمون با فایلِ واقعیِ تکه‌ای (ساخته‌شده با ffmpeg) بررسی می‌کند که
 * remuxFmp4 یک MP4ِ معمولی با طولِ درست، نرخِ ثابتِ فریم، stss و هم‌ترازیِ
 * دقیقِ آفست‌ها می‌سازد.
 */
'use strict';

const fs = require('fs');
const path = require('path');

global.window = global;
global.document = { createElement: () => ({}) };
require('../engine.js');
require('../trace_style.js');
require('../offline.js');

const OFF = global.LocalOffline;
const FIX = path.join(__dirname, 'fixtures');

let pass = 0, fail = 0;
function ok(cond, name, extra) {
  if (cond) { pass++; console.log('  ✅ ' + name + (extra ? '  ' + extra : '')); }
  else { fail++; console.log('  ❌ ' + name + (extra ? '  ' + extra : '')); }
}
function eq(a, b, name, tol) {
  const good = (tol == null) ? (a === b) : (Math.abs(a - b) <= tol);
  ok(good, name, '→ ' + a + (tol == null ? '' : ' ≈ ' + b));
}
function load(name) {
  const b = fs.readFileSync(path.join(FIX, name));
  return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
}
const CAT = function (list) {
  const len = list.reduce((a, x) => a + x.length, 0);
  const out = new Uint8Array(len);
  let o = 0;
  list.forEach(x => { out.set(x, o); o += x.length; });
  return out;
};
const fcc = (dv, off) => String.fromCharCode(dv.getUint8(off), dv.getUint8(off + 1),
  dv.getUint8(off + 2), dv.getUint8(off + 3));

/* خواندنِ stblهای خروجی برای بررسیِ آفست‌ها (مستقل از کدِ کتابخانه) */
function readStbl(buf) {
  const dv = new DataView(buf);
  const res = {};
  function walk(start, end, cb) {
    let p = start;
    while (p + 8 <= end) {
      const size = dv.getUint32(p);
      if (size < 8 || p + size > end) return;
      cb(fcc(dv, p + 4), p + 8, size - 8);
      p += size;
    }
  }
  walk(0, dv.byteLength, (t, b, len) => {
    if (t !== 'moov') return;
    walk(b, b + len, (t2, b2, l2) => {
      if (t2 !== 'trak') return;
      let handler = '';
      const boxes = {};
      walk(b2, b2 + l2, (t3, b3, l3) => {
        if (t3 !== 'mdia') return;
        walk(b3, b3 + l3, (t4, b4, l4) => {
          if (t4 === 'hdlr') handler = fcc(dv, b4 + 8);
          if (t4 !== 'minf') return;
          walk(b4, b4 + l4, (t5, b5, l5) => {
            if (t5 !== 'stbl') return;
            walk(b5, b5 + l5, (t6, b6, l6) => {
              boxes[t6] = { b: b6, len: l6 };
            });
          });
        });
      });
      if (!handler || !boxes.stco || !boxes.stsz || !boxes.stts) return;
      /* stsz: version/flags(4) + sample_size(4) + sample_count(4) + entries */
      const sizes = [];
      const n = dv.getUint32(boxes.stsz.b + 8);
      for (let i = 0; i < n; i++) sizes.push(dv.getUint32(boxes.stsz.b + 12 + i * 4));
      const sttsN = dv.getUint32(boxes.stts.b + 4);
      const stts = [];
      for (let i = 0; i < sttsN; i++) {
        stts.push([dv.getUint32(boxes.stts.b + 8 + i * 8), dv.getUint32(boxes.stts.b + 12 + i * 8)]);
      }
      res[handler] = {
        count: n,
        sizes,
        stts,
        chunkOffset: dv.getUint32(boxes.stco.b + 8),
        hasStss: !!boxes.stss,
        stssCount: boxes.stss ? dv.getUint32(boxes.stss.b + 4) : 0,
        hasCtts: !!boxes.ctts
      };
    });
  });
  return res;
}

console.log('══ بازسازیِ ظرف: fMP4 → MP4ِ معمولی ══\n');

/* ── ۱) خوراکِ خام واقعاً «تکه‌ای» و بی‌طول است ── */
const srcAV = load('frag_av.mp4');
const tlSrc = OFF.mp4Timeline(srcAV);
ok(tlSrc.fragmented === true, 'خوراکِ آزمون تکه‌ای است', '(moof=' + tlSrc.moofCount + ')');
eq(tlSrc.movieDuration, 0, 'در خوراک، طولِ mvhd صفر است (همان علتِ «بدونِ طول» در گالری)');
ok(tlSrc.mehdDuration == null, 'در خوراک، mehd وجود ندارد');

/* ── ۲) خواندنِ نمونه‌ها از moof/trun ── */
const info = OFF.parseFmp4(srcAV);
const vSrc = info.tracks.filter(t => t.handler === 'vide')[0];
const aSrc = info.tracks.filter(t => t.handler === 'soun')[0];
ok(!!vSrc && !!aSrc, 'یک تراکِ تصویر و یک تراکِ صدا خوانده شد',
  '(v=' + (vSrc && vSrc.samples.length) + ' a=' + (aSrc && aSrc.samples.length) + ')');
eq(vSrc.samples.length, 60, 'شمارشِ نمونه‌های تصویر درست است', 0);
eq(vSrc.width, 160, 'عرضِ تصویر از tkhd خوانده شد');
eq(vSrc.height, 120, 'ارتفاعِ تصویر از tkhd خوانده شد');
ok(vSrc.samples.every(s => s.size > 0 && s.off > 0), 'همهٔ نمونه‌ها آفست و اندازهٔ معتبر دارند');

/* ── ۳) بازسازی: طولِ درست، بی‌تکه، نرخِ ثابت ── */
const out = OFF.remuxFmp4(srcAV, { fps: 30, durationSec: 2 });
ok(!!out, 'remuxFmp4 خروجی داد');
const tl = OFF.mp4Timeline(out);
eq(tl.fragmented, false, 'خروجی دیگر تکه‌ای نیست (moof ندارد)');
eq(+tl.movieDuration, 2000, 'طولِ mvhd برابرِ ۲ ثانیه نوشته شد', 60);
const vOut = tl.tracks.filter(t => t.handler === 'vide')[0];
const aOut = tl.tracks.filter(t => t.handler === 'soun')[0];
eq(+vOut.samples, 60, 'همهٔ ۶۰ نمونهٔ تصویر منتقل شدند');
eq(+(vOut.mdhdDuration / vOut.mdhdTimescale).toFixed(2), 2.00, 'طولِ تراکِ تصویر = ۲ ثانیه', 0.02);
eq(+(aOut.mdhdDuration / aOut.mdhdTimescale).toFixed(2), 2.00, 'طولِ تراکِ صدا = ۲ ثانیه', 0.05);
eq(+(vOut.tkhdDuration / tl.movieTimescale).toFixed(2), 2.00, 'طولِ tkhd تصویر = ۲ ثانیه', 0.05);

/* ── ۴) نرخِ ثابتِ فریم (stts با یک بازه) و stss ── */
const st = readStbl(out);
eq(st.vide.stts.length, 1, 'stts تصویر یک بازهٔ یکنواخت است (نرخِ ثابت)');
eq(st.vide.stts[0][0], 60, 'طولِ بازه = ۶۰ نمونه');
eq(st.vide.stts[0][1], 1000, 'گامِ زمانیِ هر فریم = ۱۰۰۰ (۳۰ فریم در ثانیه)');
ok(st.vide.hasStss && st.vide.stssCount > 0, 'جدولِ stss (کی‌فریم‌ها) نوشته شد',
  '(' + st.vide.stssCount + ' کی‌فریم)');
eq(st.soun.stts[0][1], 1024, 'گامِ نمونهٔ صدا از خودِ AAC حفظ شد');

/* ── ۵) آفست‌ها درست‌اند: بایت‌های خروجی با نمونه‌های مبدأ یکی است ── */
const dst = new Uint8Array(out);
function dstSample(off, size, index) {
  let o = off;
  for (let i = 0; i < index; i++) o += size[i];
  return dst.subarray(o, o + size[index]);
}
function sameBytes(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
const srcBytes = new Uint8Array(srcAV);
const srcSample = (s) => srcBytes.subarray(s.off, s.off + s.size);
ok(sameBytes(dstSample(st.vide.chunkOffset, st.vide.sizes, 0), srcSample(vSrc.samples[0])),
  'نمونهٔ اولِ تصویر بایت‌به‌بایت همان مبدأ است (stco/stsz درست)');
ok(sameBytes(dstSample(st.vide.chunkOffset, st.vide.sizes, 59), srcSample(vSrc.samples[59])),
  'نمونهٔ آخرِ تصویر هم درست است');
ok(sameBytes(dstSample(st.soun.chunkOffset, st.soun.sizes, 0), srcSample(aSrc.samples[0])),
  'نمونهٔ اولِ صدا درست است (آفستِ چانکِ صدا بعد از تصویر)');

/* ── ۶) بازبینیِ درونیِ کتابخانه هم قبولش می‌کند ── */
const vfy = OFF.verifyMp4(out, { durationSec: 2, fps: 30, maxKeyGapSec: 2.5 });
ok(vfy.parsed && vfy.ok, 'verifyMp4 خروجی را سالم می‌داند',
  '(' + (vfy.problems || []).join('، ') + ')');

/* ── ۷) تصویرتنها (بدونِ صدا) هم کار می‌کند ── */
const srcV = load('frag_v.mp4');
const outV = OFF.remuxFmp4(srcV, { fps: 30, durationSec: 2 });
ok(!!outV, 'فایلِ تصویرتنها هم بازسازی شد');
const tlV = OFF.mp4Timeline(outV);
eq(tlV.tracks.length, 1, 'فقط یک تراک دارد');
eq(+tlV.movieDuration, 2000, 'طولِ ۲ ثانیه در فایلِ تصویرتنها', 60);

/* ── ۸) طولِ اعلام‌شدهٔ اشتباه، صدا را خراب نمی‌کند ── */
const outBad = OFF.remuxFmp4(srcAV, { fps: 30, durationSec: 5 });
const tlBad = OFF.mp4Timeline(outBad);
eq(+(tlBad.movieDuration / tlBad.movieTimescale).toFixed(2), 2.00,
  'اگر طولِ اعلام‌شده غلط باشد، طولِ واقعیِ صدا مرجع می‌شود', 0.05);

/* ── ۹) ورودیِ نامعتبر ⇒ null (نه استثنا) ── */
ok(OFF.remuxFmp4(new ArrayBuffer(32), { fps: 30 }) === null,
  'ورودیِ بی‌معنا null برمی‌گرداند و مسیرِ احتیاطی می‌ماند');

console.log('\n' + (fail === 0 ? '✅ همهٔ ' + pass + ' بررسی گذشت' : '❌ ' + fail + ' بررسی شکست خورد (' + pass + ' گذشت)'));
process.exit(fail === 0 ? 0 : 1);
