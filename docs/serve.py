#!/usr/bin/env python3
"""سرور پیش‌نمایش و دانلود «ردِّ صدا» — پورت 8099

  /             صفحهٔ دانلود APK (نسخهٔ جاری)
  /apk          دانلود مستقیم APK
  /app/         خودِ اپ وب (کاملاً آفلاین)
  /preview/     پیش‌نمایش: نوار متحرک ویدیو + فیلتر سخت‌گیرانهٔ نت‌ها

اجرا:  python3 docs/serve.py
"""
import http.server
import os
import socketserver

PORT = 8099
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
PREVIEW_DIR = os.path.join(DOCS, "preview")
WEB_DIR = os.path.join(ROOT, "web")
RELEASE_DIR = os.path.join(ROOT, "release")


def current_apk():
    """آخرین APK ساخته‌شده در release/"""
    try:
        apks = [f for f in os.listdir(RELEASE_DIR) if f.endswith(".apk") and "radde-seda-" in f]
        apks.sort(key=lambda f: [int(p) if p.isdigit() else p for p in f.replace(".apk", "").split("-")[-1].split(".")])
        if apks:
            return apks[-1]
    except Exception:
        pass
    return "radde-seda.apk"


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=DOCS, **kw)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def guess_type(self, path):
        if str(path).endswith(".apk"):
            return "application/vnd.android.package-archive"
        if str(path).endswith(".js"):
            return "application/javascript; charset=utf-8"
        return super().guess_type(path)

    def log_message(self, *a):
        pass

    def _serve_file(self, full):
        if not os.path.isfile(full):
            self.send_error(404)
            return
        data = open(full, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", self.guess_type(full))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_under(self, prefix, base):
        rel = self.path.split("?", 1)[0][len(prefix):].lstrip("/") or "index.html"
        full = os.path.normpath(os.path.join(base, rel))
        if not full.startswith(base):
            self.send_error(403)
            return
        self._serve_file(full)

    def do_GET(self):
        p = self.path.split("?", 1)[0]
        if p.startswith("/preview"):
            return self._serve_under("/preview", PREVIEW_DIR)
        if p.startswith("/app"):
            return self._serve_under("/app", WEB_DIR)
        if p.rstrip("/") == "/apk":
            apk = current_apk()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="%s"' % apk)
            size = os.path.getsize(os.path.join(RELEASE_DIR, apk))
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(os.path.join(RELEASE_DIR, apk), "rb") as f:
                while True:
                    chunk = f.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return
        if p in ("/", "/index.html"):
            self.path = "/index.html"
        super().do_GET()


socketserver.TCPServer.allow_reuse_address = True
with socketserver.ThreadingTCPServer(("0.0.0.0", PORT), Handler) as httpd:
    print("serving on 0.0.0.0:%d   /  download | /app/  web app | /preview/  demo" % PORT, flush=True)
    httpd.serve_forever()
