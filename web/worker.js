/* ============================================================================
   🔧 کارگر موازی «ردِّ صدا» — web/worker.js
   ----------------------------------------------------------------------------
   همان موتور (engine.js) داخل یک Worker بارگذاری می‌شود و فقط محاسبه‌های
   سنگین را انجام می‌دهد: استخراج F0، موتور دوم YIN، و پالایش هارمونیک نت‌ها.
   هیچ تحلیلی این‌جا «تصمیم» نمی‌گیرد؛ فقط همان توابع خالص engine.js اجرا
   می‌شوند، پس نتیجه دقیقاً برابر مسیر سریال است.

   در مرورگر:  new Worker('worker.js') + importScripts
   در Node:    worker_threads + require  (برای آزمون‌های خودکار)
   ========================================================================== */
(function () {
  'use strict';

  var CORE = null;
  var hasImport = (typeof importScripts === 'function');

  if (hasImport) {
    importScripts('engine.js');
    CORE = self.LocalEngineCore;
  } else if (typeof require === 'function') {
    CORE = require(require('path').join(__dirname, 'engine.js'));
  }
  if (!CORE) throw new Error('engine.js بارگذاری نشد');

  var send = null;
  if (hasImport) {
    send = function (msg, transfer) { self.postMessage(msg, transfer || []); };
  } else {
    var wt = require('worker_threads');
    send = function (msg, transfer) { wt.parentPort.postMessage(msg, transfer || []); };
  }

  function handle(d) {
    var id = d && d.id;
    try {
      var out;
      if (d.cmd === 'ping') {
        out = { id: id, ok: true, pong: true, cores: 1 };
      } else if (d.cmd === 'pitch') {
        var win = null;
        var p = CORE.pitchTrackRange(
          d.y, d.sr, d.fmin, d.fmax,
          { winN: d.winN, hopN: d.hopN }, d.i0, d.i1, win, d.yBase
        );
        out = { id: id, ok: true, i0: p.i0, i1: p.i1, freqs: p.freqs, strength: p.strength };
        send(out, [p.freqs.buffer, p.strength.buffer]);
        return;
      } else if (d.cmd === 'yin') {
        var yp = { W: d.W, hop: d.hop, tauMin: d.tauMin, tauMax: d.tauMax, nFrames: d.nFrames, hopSec: d.hopSec };
        var q = CORE.yinTrackRange(d.y, d.sr, d.fmin, d.fmax, yp, d.i0, d.i1, d.yBase);
        out = { id: id, ok: true, i0: q.i0, i1: q.i1, f0: q.f0, prob: q.prob };
        send(out, [q.f0.buffer, q.prob.buffer]);
        return;
      } else if (d.cmd === 'refine') {
        var res = CORE.refineNotesRange(d.y, d.sr, d.notes, d.i0, d.i1, d.yBaseSec);
        out = { id: id, ok: true, i0: d.i0, i1: d.i1, results: res };
      } else {
        out = { id: id, ok: false, error: 'دستور ناشناخته: ' + d.cmd };
      }
      send(out);
    } catch (e) {
      send({ id: id, ok: false, error: (e && e.message) || String(e) });
    }
  }

  if (hasImport) {
    self.onmessage = function (e) { handle(e.data); };
  } else {
    require('worker_threads').parentPort.on('message', handle);
  }
})();
