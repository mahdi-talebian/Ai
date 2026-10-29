/* رندر فریم‌های واقعی (PNG) از TraceRenderer بدون مرورگر — برای بازبینی چشمی */
'use strict';
const fs = require('fs');
const zlib = require('zlib');

function parseColor(c) {
  if (c && typeof c === 'object' && c.stops && c.stops.length) {
    return parseColor(c.stops[Math.floor(c.stops.length / 2)][1]);
  }
  if (typeof c !== 'string') return [16, 26, 48, 1];
  const s = c.trim();
  let m = s.match(/^#([0-9a-f]{6})$/i);
  if (m) {
    const v = parseInt(m[1], 16);
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255, 1];
  }
  m = s.match(/rgba?\(([^)]+)\)/);
  if (m) {
    const p = m[1].split(',').map(Number);
    return [p[0] | 0, p[1] | 0, p[2] | 0, p[3] == null ? 1 : p[3]];
  }
  m = s.match(/^rgb\(([^)]+)\)/);
  if (m) {
    const p = m[1].split(',').map(Number);
    return [p[0] | 0, p[1] | 0, p[2] | 0, 1];
  }
  return [120, 150, 200, 1];
}

function Ctx(W, H) {
  this.W = W; this.H = H;
  this.buf = new Uint8Array(W * H * 3);
  this.strokeStyle = '#fff'; this.fillStyle = '#fff';
  this.lineWidth = 1; this.font = ''; this.textAlign = 'left';
  this.globalAlpha = 1;
  this._path = [];
  this.clipRect = null;
  this.transform = [1, 1];
}
Ctx.prototype.px = function (x, y, col, a) {
  if (this.clipRect) {
    const c = this.clipRect;
    if (x < c.x || y < c.y || x > c.x + c.w || y > c.y + c.h) return;
  }
  x = Math.round(x); y = Math.round(y);
  if (x < 0 || y < 0 || x >= this.W || y >= this.H) return;
  const i = (y * this.W + x) * 3;
  const al = Math.max(0, Math.min(1, a == null ? 1 : a));
  this.buf[i] = this.buf[i] * (1 - al) + col[0] * al;
  this.buf[i + 1] = this.buf[i + 1] * (1 - al) + col[1] * al;
  this.buf[i + 2] = this.buf[i + 2] * (1 - al) + col[2] * al;
};
Ctx.prototype.rect = function (x, y, w, h) { this._path.push({ t: 'rect', x, y, w, h }); };
Ctx.prototype.beginPath = function () { this._path = []; };
Ctx.prototype.closePath = function () { };
Ctx.prototype.moveTo = function (x, y) { this._path.push({ t: 'm', x, y }); };
Ctx.prototype.lineTo = function (x, y) { this._path.push({ t: 'l', x, y }); };
Ctx.prototype.arc = function (x, y, r) { this._path.push({ t: 'a', x, y, r }); };
Ctx.prototype.arcTo = function () { };
Ctx.prototype.save = function () { this._saved = { clip: this.clipRect }; };
Ctx.prototype.restore = function () { if (this._saved) this.clipRect = this._saved.clip; };
Ctx.prototype.clip = function () {
  const r = this._path.filter(p => p.t === 'rect').pop();
  if (r) this.clipRect = { x: r.x, y: r.y, w: r.w, h: r.h };
};
Ctx.prototype.setLineDash = function () { };
Ctx.prototype.createLinearGradient = function () {
  const g = { stops: [] };
  g.addColorStop = (o, c) => g.stops.push([o, c]);
  return g;
};
Ctx.prototype.createRadialGradient = function () { return this.createLinearGradient(); };
Ctx.prototype.measureText = function (t) { return { width: String(t).length * 8 }; };
Ctx.prototype.fillText = function () { };  /* متن در این بازبینی رسم نمی‌شود */
Ctx.prototype.fillRect = function (x, y, w, h) {
  const col = parseColor(this.fillStyle);
  for (let yy = y; yy < y + h; yy++) for (let xx = x; xx < x + w; xx++) this.px(xx, yy, col, col[3]);
};
Ctx.prototype.clearRect = function (x, y, w, h) {
  for (let yy = y; yy < y + h; yy++) for (let xx = x; xx < x + w; xx++) this.px(xx, yy, [11, 20, 40], 1);
};
Ctx.prototype.drawImage = function (src) {
  if (!src || !src.buf) return;
  for (let y = 0; y < this.H; y++) {
    for (let x = 0; x < this.W; x++) {
      const i = (y * this.W + x) * 3;
      this.buf[i] = src.buf[i]; this.buf[i + 1] = src.buf[i + 1]; this.buf[i + 2] = src.buf[i + 2];
    }
  }
};
Ctx.prototype.stroke = function () {
  const col = parseColor(this.strokeStyle);
  const a = col[3] * this.globalAlpha;
  const w = Math.max(1, this.lineWidth | 0);
  let last = null;
  for (const p of this._path) {
    if (p.t === 'm') { last = p; continue; }
    if (p.t === 'l' && last) {
      const x0 = last.x, y0 = last.y, x1 = p.x, y1 = p.y;
      const steps = Math.max(2, Math.ceil(Math.hypot(x1 - x0, y1 - y0) * 1.6));
      for (let s = 0; s <= steps; s++) {
        const x = x0 + (x1 - x0) * s / steps, y = y0 + (y1 - y0) * s / steps;
        const r = (w - 1) / 2;
        for (let dy = -r; dy <= r; dy += 1) for (let dx = -r; dx <= r; dx += 1) {
          this.px(x + dx, y + dy, col, a);
        }
      }
      last = p;
    } else if (p.t === 'a') {
      for (let ang = 0; ang < Math.PI * 2; ang += 0.02) {
        this.px(p.x + Math.cos(ang) * p.r, p.y + Math.sin(ang) * p.r, col, a);
      }
    }
  }
};
Ctx.prototype.fill = function () {
  const col = parseColor(this.fillStyle);
  const a = col[3] * this.globalAlpha;
  for (const p of this._path) {
    if (p.t !== 'a') continue;
    for (let dy = -p.r; dy <= p.r; dy++) {
      const dx = Math.sqrt(Math.max(0, p.r * p.r - dy * dy));
      for (let x = -dx; x <= dx; x++) this.px(p.x + x, p.y + dy, col, a);
    }
  }
};

/* ── PNG ── */
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
  return Buffer.concat([len, t, data, crc]);
}
function writePng(ctx, file) {
  const { W, H, buf } = ctx;
  const raw = Buffer.alloc((W * 3 + 1) * H);
  for (let y = 0; y < H; y++) {
    raw[y * (W * 3 + 1)] = 0;
    buf.subarray(y * W * 3, (y + 1) * W * 3).forEach((v, i) => {
      raw[y * (W * 3 + 1) + 1 + i] = v;
    });
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
  fs.writeFileSync(file, png);
  return png.length;
}

module.exports = { Ctx, writePng, parseColor };
