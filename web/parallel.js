/* ============================================================================
   ⚡ موازی‌ساز «ردِّ صدا» — web/parallel.js
   ----------------------------------------------------------------------------
   کارهای سنگین تحلیل (استخراج F0، موتور دوم YIN، پالایش هارمونیک نت) بین
   چند Worker پخش می‌شوند تا روی گوشی/لپ‌تاپ چند برابر سریع‌تر تمام شوند.

   • اگر Worker در دسترس نباشد (مرورگر قدیمی، file://، یا هر خطا) این فایل
     بی‌صدا غیرفعال می‌شود و موتور خودش مسیر سریال را اجرا می‌کند.
   • داده‌های صوتی فقط به‌صورت «برشِ همان بازه» فرستاده می‌شوند؛ هیچ نسخهٔ
     کاملِ صدا در چند نسخه تکثیر نمی‌شود (مهم برای فایل‌های بلند).
   • نتیجهٔ محاسبه‌ها با مسیر سریال بیت‌به‌بیت یکسان است (تست: parallel_test.js).

   بارگذاری: بعد از engine.js و قبل از offline.js
   ========================================================================== */
(function (global) {
  'use strict';

  var isNode = (typeof process !== 'undefined' && !!(process.versions && process.versions.node) &&
                typeof window === 'undefined');
  var MAX_WORKERS = 4;
  var TASK_FRAMES = 900;          // ~۴٫۵ ثانیه در هر کار
  var TASK_TIMEOUT_MS = 90000;

  function cpuCount() {
    try {
      if (isNode) return require('os').cpus().length || 1;
      if (typeof navigator !== 'undefined' && navigator.hardwareConcurrency) return navigator.hardwareConcurrency;
    } catch (e) { }
    return 2;
  }

  function workerCount() {
    var n = cpuCount();
    if (!(n > 1)) return 0;                 // تک‌هسته‌ای: همان مسیر سریال
    return Math.min(MAX_WORKERS, n);        // در طول تحلیل، رشتهٔ اصلی منتظر است
  }

  /* ── لایهٔ ارتباط با Worker (مرورگر یا Node) ─────────────────────────── */
  function spawn() {
    var w;
    if (isNode) {
      var path = require('path');
      w = new (require('worker_threads').Worker)(path.join(__dirname, 'worker.js'));
    } else {
      var URLmaker = global.Worker;
      var url;
      try {
        url = new URL('worker.js', (global.document && global.document.baseURI) || global.location.href).href;
      } catch (e) {
        url = 'worker.js';
      }
      w = new URLmaker(url);
    }
    var t = {
      raw: w,
      onmessage: null,
      onerror: null,
      post: function (msg) {
        try { w.postMessage(msg); } catch (e) { if (t.onerror) t.onerror(e); }
      },
      kill: function () {
        try { if (w.terminate) w.terminate(); } catch (e) { }
      }
    };
    if (isNode) {
      w.on('message', function (m) { if (t.onmessage) t.onmessage({ data: m }); });
      w.on('error', function (e) { if (t.onerror) t.onerror(e); });
      try { w.unref(); } catch (e) { }                       // پروسه با پایان کار بسته شود
      try { if (w.port && w.port.unref) w.port.unref(); } catch (e) { }
    } else {
      w.onmessage = function (e) { if (t.onmessage) t.onmessage(e); };
      w.onerror = function (e) { if (t.onerror) t.onerror(e); };
    }
    return t;
  }

  /* ── استخر کارگرها ─────────────────────────────────────────────────────── */
  function Pool(size) {
    this.size = size;
    this.workers = [];
    this.pending = {};
    this.seq = 1;
    this.dead = false;
    for (var i = 0; i < size; i++) {
      var w = spawn();
      var self = this;
      w.onmessage = function (ev) {
        var d = ev && ev.data;
        if (!d || d.id == null) return;
        var job = self.pending[d.id];
        if (!job) return;
        if (d.progress != null) {
          if (job.onProgress) { try { job.onProgress(d.progress); } catch (e) { } }
          return;
        }
        delete self.pending[d.id];
        clearTimeout(job.timer);
        if (d.ok) job.resolve(d);
        else job.reject(new Error(d.error || 'خطای Worker'));
      };
      w.onerror = function (e) {
        self.dead = true;
        var keys = Object.keys(self.pending);
        for (var k = 0; k < keys.length; k++) {
          var job = self.pending[keys[k]];
          clearTimeout(job.timer);
          try { job.reject(new Error('Worker از کار افتاد')); } catch (e2) { }
        }
        self.pending = {};
      };
      this.workers.push(w);
    }
  }

  Pool.prototype.send = function (worker, msg, onProgress) {
    var self = this;
    if (this.dead) return Promise.reject(new Error('استخر کارگر بسته است'));
    msg.id = this.seq++;
    return new Promise(function (resolve, reject) {
      self.pending[msg.id] = {
        resolve: resolve, reject: reject, onProgress: onProgress,
        timer: setTimeout(function () {
          delete self.pending[msg.id];
          reject(new Error('پاسخ Worker طول کشید'));
        }, TASK_TIMEOUT_MS)
      };
      self.workers[worker].post(msg);
    });
  };

  Pool.prototype.terminate = function () {
    this.dead = true;
    for (var i = 0; i < this.workers.length; i++) this.workers[i].kill();
    this.workers = [];
  };

  /* ── وضعیت ماژول ───────────────────────────────────────────────────────── */
  var api = {
    available: false,
    cores: 0,
    reason: '',
    _pool: null
  };

  function ensurePool() {
    if (api._pool) return api._pool;
    var n = workerCount();
    if (!n) { api.available = false; api.reason = 'تک‌هسته‌ای'; return null; }
    try {
      api._pool = new Pool(n);
      api.cores = n;
      api.available = true;
      api.reason = 'فعال (' + n + ' کارگر)';
      return api._pool;
    } catch (e) {
      api.available = false;
      api.reason = 'Worker در دسترس نیست';
      return null;
    }
  }

  api.init = function () { return !!ensurePool(); };

  api.reset = function () {
    if (api._pool) { try { api._pool.terminate(); } catch (e) { } }
    api._pool = null;
    api.available = false;
    api.reason = 'غیرفعال شد (بازگشت به حالت سریال)';
  };

  /* ── تقسیم کار و اجرای موازی ───────────────────────────────────────────── */
  function splitTasks(n, total) {
    var tasks = [];
    if (!total) return tasks;
    var per = Math.max(1, Math.ceil(total / n));
    for (var a = 0; a < total; a += per) tasks.push([a, Math.min(total, a + per)]);
    return tasks;
  }

  /** همهٔ کارها را بین کارگرها پخش می‌کند (بار متعادل) و پیشرفت تجمیعی می‌دهد */
  function runAll(pool, tasks, makeMsg, onProgress) {
    var totalW = 0;
    for (var i = 0; i < tasks.length; i++) totalW += (tasks[i][1] - tasks[i][0]);
    if (!totalW) totalW = 1;

    var results = new Array(tasks.length);
    var doneW = 0;
    var idx = 0;

    function report() {
      if (onProgress) onProgress(Math.max(0, Math.min(1, doneW / totalW)));
    }

    function lane(worker) {
      if (idx >= tasks.length) return Promise.resolve();
      var k = idx++;
      var span = tasks[k][1] - tasks[k][0];
      var last = 0;
      return pool.send(worker, makeMsg(tasks[k][0], tasks[k][1]), function (f) {
        var p = Math.max(0, Math.min(1, f));
        doneW += (p - last) * span;
        last = p;
        report();
      }).then(function (res) {
        doneW += (1 - last) * span;
        last = 1;
        results[k] = { task: tasks[k], res: res };
        report();
        return lane(worker);          // کار بعدی همین کارگر
      });
    }

    var lanes = [];
    for (var w = 0; w < pool.size; w++) lanes.push(lane(w));
    return Promise.all(lanes).then(function () { return results; });
  }

  /* ── API سطح بالا ──────────────────────────────────────────────────────── */

  /** استخراج F0 (هم‌ارز pitchTrack) به‌صورت موازی */
  api.pitchTrack = function (y, sr, fmin, fmax, onProgress) {
    var pool = ensurePool();
    if (!pool) return Promise.resolve(null);
    var CORE = global.LocalEngineCore;
    var w = CORE.pitchWindow(y, sr, fmin);
    var nFrames = w.nFrames;
    var tasks = splitTasks(pool.size, nFrames);
    // دانه‌بندی ریزتر برای تعادل بار
    var small = [];
    tasks.forEach(function (t) {
      for (var a = t[0]; a < t[1]; a += TASK_FRAMES) small.push([a, Math.min(t[1], a + TASK_FRAMES)]);
    });

    return runAll(pool, small, function (i0, i1) {
      var a = i0 * w.hopN, b = (i1 - 1) * w.hopN + w.winN;
      return {
        cmd: 'pitch', y: y.subarray(a, Math.min(y.length, b)), yBase: a,
        sr: sr, fmin: fmin, fmax: fmax, winN: w.winN, hopN: w.hopN, i0: i0, i1: i1
      };
    }, onProgress).then(function (parts) {
      var freqs = new Float32Array(nFrames);
      var strength = new Float32Array(nFrames);
      for (var k = 0; k < parts.length; k++) {
        freqs.set(parts[k].res.freqs, parts[k].res.i0);
        strength.set(parts[k].res.strength, parts[k].res.i0);
      }
      var times = new Float32Array(nFrames);
      for (var i = 0; i < nFrames; i++) times[i] = i * CORE.TIME_STEP;
      return { times: times, freqs: freqs, strength: strength, nFrames: nFrames };
    });
  };

  /** موتور دوم YIN به‌صورت موازی (هم‌ارز yinTrack) */
  api.yinTrack = function (y, sr, fmin, fmax, duration, hopSec) {
    var pool = ensurePool();
    if (!pool) return Promise.resolve(null);
    var CORE = global.LocalEngineCore;
    if (duration > 180.0) return Promise.resolve(null);
    var p = CORE.yinParams(y, sr, fmin, fmax, hopSec);
    var tasks = splitTasks(pool.size, p.nFrames);
    var small = [];
    tasks.forEach(function (t) {
      for (var a = t[0]; a < t[1]; a += 400) small.push([a, Math.min(t[1], a + 400)]);
    });

    return runAll(pool, small, function (i0, i1) {
      var a = i0 * p.hop, b = (i1 - 1) * p.hop + p.W;
      return {
        cmd: 'yin', y: y.subarray(a, Math.min(y.length, b)), yBase: a,
        sr: sr, fmin: fmin, fmax: fmax,
        W: p.W, hop: p.hop, tauMin: p.tauMin, tauMax: p.tauMax,
        nFrames: p.nFrames, hopSec: p.hopSec, i0: i0, i1: i1
      };
    }, null).then(function (parts) {
      var f0 = new Float32Array(p.nFrames);
      var prob = new Float32Array(p.nFrames);
      for (var k = 0; k < parts.length; k++) {
        f0.set(parts[k].res.f0, parts[k].res.i0);
        prob.set(parts[k].res.prob, parts[k].res.i0);
      }
      return { f0: f0, prob: prob, hop: p.hopSec };
    });
  };

  /** پالایش هارمونیک نت‌ها به‌صورت موازی (هم‌ارز refineNotesRange) */
  api.refineNotes = function (y, sr, notes) {
    var pool = ensurePool();
    if (!pool) return Promise.resolve(null);
    if (!notes || !notes.length) return Promise.resolve([]);
    var tasks = splitTasks(pool.size, notes.length);
    // یک کار برای هر کارگر، هر کار با برش صوتی خودش
    return Promise.all(tasks.map(function (t, wi) {
      var t0 = Infinity, t1 = -Infinity;
      for (var k = t[0]; k < t[1]; k++) {
        if (notes[k].start < t0) t0 = notes[k].start;
        if (notes[k].end > t1) t1 = notes[k].end;
      }
      var a = Math.max(0, Math.floor((t0 - 0.15) * sr));
      var b = Math.min(y.length, Math.ceil((t1 + 0.15) * sr));
      var sub = notes.slice(t[0], t[1]);
      return pool.send(wi % pool.size, {
        cmd: 'refine', y: y.subarray(a, b), yBaseSec: a / sr,
        sr: sr, notes: sub, i0: 0, i1: sub.length
      }).then(function (r) { r.base = t[0]; return r; });
    })).then(function (parts) {
      var out = new Array(notes.length);
      for (var i = 0; i < parts.length; i++) {
        var res = parts[i].results;
        for (var k = 0; k < res.length; k++) out[parts[i].base + k] = res[k];
      }
      return out;
    });
  };

  global.LocalParallel = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;

  // ساخت زودهنگام استخر: اگر Worker در این محیط ممکن نباشد، همین‌جا معلوم شود
  try { ensurePool(); } catch (e) { api.available = false; }
})(typeof window !== 'undefined' ? window : globalThis);
