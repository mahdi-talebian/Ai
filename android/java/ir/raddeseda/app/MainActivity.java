package ir.raddeseda.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * اپ اندروید «ردِّ صدا»
 * ============================================================================
 * همین رابط وب (پوشهٔ web) را داخل یک WebView نشان می‌دهد، ولی با سه تفاوت
 * که همهٔ قابلیت‌ها را روی گوشی کار می‌اندازد:
 *
 *   ۱) صفحه از مبدأ امنِ داخلی https://appassets.local سرو می‌شود
 *      (نه file://). نتیجه: getUserMedia برای میکروفون، localStorage،
 *      fetch/XHR و WebSocket همه مثل یک سایت واقعی کار می‌کنند.
 *   ۲) ذخیرهٔ فایل‌ها بومی است: MediaStore/پوشه‌های عمومی برای تصویر،
 *      گزارش، صدای ضبط‌شده و ویدیو (WebView خودش blob را دانلود نمی‌کند).
 *   ۳) انتخاب فایل از گوشی و مجوز میکروفون با پل بومی انجام می‌شود.
 *
 * آدرس سرور تحلیل در خود صفحه (دکمهٔ «⚙ سرور») تنظیم و در localStorage
 * ذخیره می‌شود؛ صفحه از همان آدرس برای API و WebSocket استفاده می‌کند.
 * ============================================================================
 */
public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.local";
    private static final String APP_URL = "https://appassets.local/index.html";
    private static final int REQ_FILE = 9001;
    private static final int REQ_MIC = 9002;
    private static final int REQ_WRITE = 9003;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest micRequest;
    private final Map<String, File> pendingSaves = new ConcurrentHashMap<String, File>();
    private long lastBackAt = 0L;

    /* ======================================================================== */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // نوارهای سیستم هم‌رنگ رابط (سرمه‌ای)
        Window win = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            win.setStatusBarColor(0xFF0B1224);
            win.setNavigationBarColor(0xFF0B1224);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View dv = win.getDecorView();
            dv.setSystemUiVisibility(dv.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1224);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);   // پخش صدا/ویدیو بدون لمس دوم
        st.setAllowContentAccess(true);
        st.setAllowFileAccess(false);
        st.setLoadsImagesAutomatically(true);
        st.setSupportMultipleWindows(false);
        st.setJavaScriptCanOpenWindowsAutomatically(false);
        st.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // سرور تحلیل ممکن است http باشد؛ اجازهٔ درخواست‌های مخلوط را می‌دهیم
            st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        try {
            st.setUserAgentString(st.getUserAgentString() + " RaddeSedaApp/1.0");
        } catch (Exception ignored) {
        }

        try {
            CookieManager.getInstance().setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            }
        } catch (Exception ignored) {
        }

        WebView.setWebContentsDebuggingEnabled(true);

        web.addJavascriptInterface(new Bridge(), "NativeApp");
        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new AppChromeClient());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                handleDownload(url, contentDisposition, mimeType);
            }
        });

        // روی اندروید ۹ و پایین‌تر برای ذخیره در پوشه‌های عمومی مجوز لازم است
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            } catch (Exception ignored) {
            }
        }

        web.loadUrl(APP_URL);
    }

    /* ===================== سرو کردن صفحه از assets (مبدأ امن) ============== */
    private class AppClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (u == null || !APP_HOST.equals(u.getHost())) return null;   // سرور تحلیل → شبکهٔ عادی
            if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
            return serveAsset(u.getPath());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (u == null) return false;
            if (APP_HOST.equals(u.getHost())) return false;               // داخل خود اپ
            // لینک‌های بیرونی (مثلاً دکمهٔ نمونه در حالت وب) → مرورگر گوشی
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) {
                toast("لینک باز نشد: " + u);
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
        }
    }

    private WebResourceResponse serveAsset(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.contains("..")) return null;
        try {
            if ("index.html".equals(rel) && !BuildConfig.DEFAULT_API.isEmpty()) {
                return serveIndexWithDefaultApi();
            }
            InputStream in = getAssets().open("web/" + rel);
            String mime = mimeFor(rel);
            String enc = needsCharset(mime) ? "utf-8" : null;
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mime, enc, 200, "OK", headers, in);
        } catch (IOException e) {
            Map<String, String> headers = new HashMap<String, String>();
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    headers, new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    /**
     * index.html را با یک اسکریپت کوچک در ابتدای <head> برمی‌گرداند که آدرس
     * سرور پیش‌فرض (اگر کاربر خودش چیزی ذخیره نکرده باشد) را می‌نویسد.
     * همین کار باعث می‌شود APK ساختِ خودتان بدون هیچ تنظیمی کار کند، ولی
     * انتخاب دستی کاربر همیشه اولویت داشته باشد.
     */
    private WebResourceResponse serveIndexWithDefaultApi() throws IOException {
        InputStream in = getAssets().open("web/index.html");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        String html = new String(bos.toByteArray(), "UTF-8");
        String inject = "<script>try{if(!localStorage.getItem('api_base'))"
                + "localStorage.setItem('api_base','" + BuildConfig.DEFAULT_API + "');}catch(e){}</script>";
        if (html.contains("<head>")) html = html.replaceFirst("<head>", "<head>" + inject);
        else html = inject + html;
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                new java.io.ByteArrayInputStream(html.getBytes("UTF-8")));
    }

    private static boolean needsCharset(String mime) {
        return mime.startsWith("text/")
                || mime.contains("javascript")
                || mime.contains("json")
                || mime.contains("xml")
                || mime.contains("svg");
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": return "application/javascript";
            case "css": return "text/css";
            case "json": return "application/json";
            case "webmanifest": case "manifest": return "application/manifest+json";
            case "woff2": return "font/woff2";
            case "woff": return "font/woff";
            case "ttf": return "font/ttf";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "svg": return "image/svg+xml";
            case "ico": return "image/x-icon";
            case "wav": return "audio/wav";
            case "mp3": return "audio/mpeg";
            case "ogg": return "audio/ogg";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "txt": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    /* ===================== مجوز میکروفون + انتخاب فایل ====================== */
    private class AppChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    boolean wantsAudio = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
                    }
                    if (!wantsAudio) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        micRequest = request;
                        try {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                        } catch (Exception e) {
                            micRequest = null;
                            request.deny();
                        }
                    }
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) {
                try { fileCallback.onReceiveValue(null); } catch (Exception ignored) {}
            }
            fileCallback = callback;
            Intent intent = null;
            try {
                intent = params.createIntent();     // نوع‌ها از accept خود صفحه می‌آید
            } catch (Exception ignored) {
            }
            if (intent == null) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "audio/*", "video/*", "application/ogg", "application/octet-stream"});
            }
            try {
                startActivityForResult(Intent.createChooser(intent, "انتخاب فایل صوتی یا ویدیو"), REQ_FILE);
                return true;
            } catch (Exception e) {
                fileCallback = null;
                toast("انتخاب فایل باز نشد");
                return false;
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage cm) {
            return true;   // لاگ‌های صفحه در logcat نویز نکنند
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                try {
                    result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                } catch (Exception ignored) {
                    result = null;
                }
            }
            try { fileCallback.onReceiveValue(result); } catch (Exception ignored) {}
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] granted) {
        if (requestCode == REQ_MIC) {
            boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
            PermissionRequest pr = micRequest;
            micRequest = null;
            if (pr != null) {
                if (ok) pr.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else {
                    pr.deny();
                    toast("برای «اجرای زنده» اجازهٔ میکروفون لازم است");
                }
            }
        } else if (requestCode == REQ_WRITE) {
            boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) toast("بدون اجازهٔ حافظه، فایل‌ها در پوشهٔ مخصوص اپ ذخیره می‌شوند");
        }
        super.onRequestPermissionsResult(requestCode, permissions, granted);
    }

    /* ===================== ذخیرهٔ فایل: مسیرهای بومی ======================== */
    private void handleDownload(String url, String contentDisposition, String mime) {
        if (url == null) return;
        String name = guessName(contentDisposition, url);
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (url.contains("://" + APP_HOST + "/")) {
                // فایل خود اپ (مثل نمونهٔ آزمایشی) → از assets روی گوشی
                saveAppAssetToDownloads(url, name);
            } else {
                enqueueDownload(url, name, mime, Environment.DIRECTORY_DOWNLOADS);
            }
        } else if (url.startsWith("data:")) {
            saveDataUrl(url, name);
        } else {
            toast("این نوع ذخیره در اپ پشتیبانی نمی‌شود");
        }
    }

    private void saveAppAssetToDownloads(String url, String name) {
        try {
            Uri u = Uri.parse(url);
            String path = u.getPath();
            String rel = path == null ? "" : (path.startsWith("/") ? path.substring(1) : path);
            InputStream in = getAssets().open("web/" + rel);
            File tmp = new File(getCacheDir(), "asset_" + System.currentTimeMillis());
            copyStream(in, new FileOutputStream(tmp));
            in.close();
            finishSave(tmp, name, mimeFor(rel), false);
            tmp.delete();
        } catch (Exception e) {
            toast("ذخیرهٔ نمونه ناموفق بود: " + e.getMessage());
        }
    }

    private void saveDataUrl(String dataUrl, String name) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) { toast("فایل نامعتبر"); return; }
            String meta = dataUrl.substring(0, comma);            // data:<mime>;base64
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            String mime = "application/octet-stream";
            int c = meta.indexOf(':');
            if (c >= 0) {
                String m = meta.substring(c + 1);
                int semi = m.indexOf(';');
                if (semi >= 0) m = m.substring(0, semi);
                if (!m.trim().isEmpty()) mime = m.trim();
            }
            File tmp = new File(getCacheDir(), "data_" + System.currentTimeMillis());
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(bytes);
            fos.close();
            finishSave(tmp, name, mime, false);
            tmp.delete();
        } catch (Exception e) {
            toast("ذخیره ناموفق بود: " + e.getMessage());
        }
    }

    private void enqueueDownload(String url, String name, String mime, String dir) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String m = (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime))
                    ? guessMimeFromName(name) : mime;
            req.setMimeType(m);
            req.setTitle(name);
            req.setDescription("ردِّ صدا");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(dir, name);
            req.allowScanningByMediaScanner();
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) { toast("سیستم دانلود در دسترس نیست"); return; }
            dm.enqueue(req);
        } catch (Exception e) {
            toast("شروع دانلود ناموفق بود: " + e.getMessage());
        }
    }

    /** ویدیو را می‌گیرد و در گالری (پوشهٔ Movies) ذخیره می‌کند. */
    private void downloadToGallery(final String url, final String name) {
        if (url == null || url.isEmpty()) return;
        toast("ویدیو در حال آماده‌سازی برای گالری…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = null;
                try {
                    tmp = File.createTempFile("gallery_", ".mp4", getCacheDir());
                    downloadToFile(url, tmp);
                    String mime = guessMimeFromName(name);
                    if ("application/octet-stream".equals(mime)) mime = "video/mp4";
                    String where = finishSave(tmp, name, mime, true);
                    toastUi("✅ ذخیره شد: " + where);
                } catch (Exception e) {
                    toastUi("ذخیره در گالری ناموفق بود: " + e.getMessage());
                } finally {
                    if (tmp != null) tmp.delete();
                }
            }
        }).start();
    }

    private void downloadToFile(String urlStr, File out) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            in = conn.getInputStream();
            copyStream(in, new FileOutputStream(out));
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * نوشتن فایل موقت در پوشهٔ عمومی درست + ایندکس رسانه (تا در گالری دیده شود).
     * کدِ مخصوص اندروید ۱۰+ در کلاس جداگانه است تا روی اندروید ۹ و پایین‌تر
     * هیچ کلاسی از MediaStore تازه بارگذاری نشود.
     *
     * @return مسیر/جای readable برای نمایش به کاربر
     */
    private String finishSave(File src, String name, String mime, boolean gallery) throws IOException {
        return finishSave(src, name, mime, gallery, 0L);
    }

    private String finishSave(File src, String name, String mime, boolean gallery,
                              long durationMs) throws IOException {
        if (src == null || !src.exists()) throw new IOException("فایل موقت ساخته نشد");
        if (src.length() <= 0) throw new IOException("فایل خالی است");

        String m = (mime == null || mime.trim().isEmpty()) ? guessMimeFromName(name) : mime.trim();
        String safe = sanitize(fitName(name, m));
        boolean video = m.startsWith("video/");
        boolean image = m.startsWith("image/");
        String sub = video ? Environment.DIRECTORY_MOVIES
                : image ? Environment.DIRECTORY_PICTURES
                : Environment.DIRECTORY_DOWNLOADS;

        /* مسیر اصلی: MediaStore (اندروید ۱۰+) — هم فایل را می‌نویسد هم در گالری ایندکس می‌کند */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Exception first = null;
            try {
                return QStorage.save(this, src, safe, m, durationMs);
            } catch (Exception e) {
                first = e;
            }
            /* پشتیبان ۱: نوشتن مستقیم در پوشهٔ عمومی (اگر دستگاه scoped storage را نیمه‌فعال دارد) */
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(sub), "");
                if (dir.exists() || dir.mkdirs()) {
                    File out = uniqueFile(dir, safe);
                    copyStream(new FileInputStream(src), new FileOutputStream(out));
                    MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()},
                            new String[]{m}, null);
                    return out.getAbsolutePath();
                }
            } catch (Exception ignored) {
            }
            /* پشتیبان ۲: پوشهٔ اختصاصی اپ (همیشه کار می‌کند) + اسکن رسانه */
            File priv = getExternalFilesDir(sub);
            if (priv == null) priv = getFilesDir();
            if (priv == null) throw new IOException("پوشهٔ مقصد در دسترس نیست");
            if (!priv.exists() && !priv.mkdirs()) throw new IOException("پوشه ساخته نشد");
            File out = uniqueFile(priv, safe);
            copyStream(new FileInputStream(src), new FileOutputStream(out));
            try {
                MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()},
                        new String[]{m}, null);
            } catch (Exception ignored) {
            }
            String why = (first != null && first.getMessage() != null) ? first.getMessage() : "";
            return out.getAbsolutePath() + (why.isEmpty() ? "" : "  (" + why + ")");
        }

        /* اندروید ۹ و پایین‌تر */
        File dir;
        if (hasWritePermission()) {
            dir = Environment.getExternalStoragePublicDirectory(sub);
        } else {
            dir = getExternalFilesDir(sub);
            if (dir == null) dir = getFilesDir();
        }
        if (dir == null) throw new IOException("پوشهٔ مقصد در دسترس نیست");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("پوشه ساخته نشد");
        File out = uniqueFile(dir, safe);
        copyStream(new FileInputStream(src), new FileOutputStream(out));
        try {
            MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()},
                    new String[]{m}, null);
        } catch (Exception ignored) {
        }
        if (!hasWritePermission()) {
            throw new IOException("دسترسی به حافظه داده نشد؛ فایل در حافظهٔ داخلی اپ ذخیره شد: "
                    + out.getAbsolutePath());
        }
        return out.getAbsolutePath();
    }

    /** ذخیره با MediaStore — فقط روی اندروید ۱۰ (API 29) و بالاتر اجرا می‌شود. */
    private static class QStorage {
        static String save(Context ctx, File src, String name, String mime) throws IOException {
            return save(ctx, src, name, mime, 0L);
        }

        static String save(Context ctx, File src, String name, String mime,
                           long durationMs) throws IOException {
            boolean video = mime.startsWith("video/");
            boolean image = mime.startsWith("image/");
            String dirName = video ? Environment.DIRECTORY_MOVIES
                    : image ? Environment.DIRECTORY_PICTURES
                    : Environment.DIRECTORY_DOWNLOADS;
            /* RELATIVE_PATH باید اسلش پایانی داشته باشد، وگرنه بعضی دستگاه‌ها
               فایل را در ریشه می‌سازند یا insert را برمی‌گردانند (منبع خطای «ناموفق بود») */
            String rel = dirName + "/";
            Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Downloads.EXTERNAL_CONTENT_URI;

            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            /* طولِ واقعی ویدیو از همان اول در ردیفِ رسانه نوشته می‌شود؛
               گالری‌ها پیش از ایندکس‌شدنِ کاملِ فایل همین را نشان می‌دهند. */
            if (video && durationMs > 0) {
                try {
                    v.put(MediaStore.Video.Media.DURATION, durationMs);
                } catch (Exception ignored) {
                }
            }

            Uri item = null;
            Exception lastErr = null;

            /* تلاش ۱: مسیر استاندارد با IS_PENDING (دسترسی انحصاری تا پایان نوشتن) */
            try {
                ContentValues pending = new ContentValues(v);
                pending.put(MediaStore.MediaColumns.IS_PENDING, 1);
                item = ctx.getContentResolver().insert(collection, pending);
            } catch (Exception e) {
                lastErr = e;
            }
            /* تلاش ۲: بدون IS_PENDING (بعضی فروشنده‌ها آن را قبول نمی‌کنند) */
            if (item == null) {
                try {
                    item = ctx.getContentResolver().insert(collection, v);
                } catch (Exception e) {
                    lastErr = e;
                }
            }
            if (item == null) {
                throw new IOException("MediaStore insert failed"
                        + (lastErr != null && lastErr.getMessage() != null ? ": " + lastErr.getMessage() : ""));
            }

            try {
                OutputStream os = ctx.getContentResolver().openOutputStream(item, "w");
                if (os == null) os = ctx.getContentResolver().openOutputStream(item);
                if (os == null) throw new IOException("MediaStore open failed");
                boolean ok = false;
                try {
                    copyStream(new FileInputStream(src), os);
                    ok = true;
                } finally {
                    if (!ok) try { os.close(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                try { ctx.getContentResolver().delete(item, null, null); } catch (Exception ignored) {}
                throw new IOException("نوشتن در گالری ناموفق بود"
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }

            /* پایان کار: IS_PENDING=0 تا فایل در گالری دیده شود */
            try {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (video && durationMs > 0) {
                    done.put(MediaStore.Video.Media.DURATION, durationMs);
                }
                ctx.getContentResolver().update(item, done, null, null);
            } catch (Exception ignored) {
            }
            return rel + name;
        }
    }

    /** پسوند فایل را با نوع واقعی آن هم‌خوان می‌کند (وگرنه گالری فایل را نشان نمی‌دهد) */
    private static String fitName(String name, String mime) {
        String n = sanitize(name);
        String m = (mime == null) ? "" : mime.toLowerCase(Locale.US);
        String ext = "";
        if (m.startsWith("video/mp4")) ext = ".mp4";
        else if (m.startsWith("video/webm")) ext = ".webm";
        else if (m.startsWith("video/3gpp")) ext = ".3gp";
        else if (m.startsWith("image/png")) ext = ".png";
        else if (m.startsWith("image/jpeg")) ext = ".jpg";
        else if (m.startsWith("image/webp")) ext = ".webp";
        else if (m.startsWith("audio/wav") || m.startsWith("audio/wave")) ext = ".wav";
        else if (m.startsWith("audio/mpeg")) ext = ".mp3";
        if (ext.isEmpty()) return n;
        String lower = n.toLowerCase(Locale.US);
        if (lower.endsWith(ext)) return n;
        return n.replaceAll("\\.[a-z0-9]{1,5}$", "") + ext;
    }

    /** نتیجهٔ ذخیره را به صفحه برمی‌گرداند تا پیام دقیق نشان داده شود */
    private void notifySave(final String token, final boolean ok, final String msg) {
        if (web == null) return;
        final String js = "window.__nativeSaveDone&&window.__nativeSaveDone("
                + jsStr(token) + "," + ok + "," + jsStr(msg) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript(js, null);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static String jsStr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private boolean hasWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 999; i++) {
            File g = new File(dir, base + "-" + i + ext);
            if (!g.exists()) return g;
        }
        return f;
    }

    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) return "output";
        String n = name.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    private static String guessMimeFromName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private String guessName(String contentDisposition, String url) {
        String name = null;
        if (contentDisposition != null) {
            String lower = contentDisposition.toLowerCase(Locale.US);
            int i = lower.indexOf("filename*=");
            if (i >= 0) {
                name = contentDisposition.substring(i + 10);
                int semicolon = name.indexOf(';');
                if (semicolon >= 0) name = name.substring(0, semicolon);
                name = name.trim().replace("\"", "");
                int q = name.indexOf("''");
                if (q >= 0) name = name.substring(q + 2);
                try {
                    name = Uri.decode(name);
                } catch (Exception ignored) {
                }
            } else {
                i = lower.indexOf("filename=");
                if (i >= 0) {
                    name = contentDisposition.substring(i + 9);
                    int semicolon = name.indexOf(';');
                    if (semicolon >= 0) name = name.substring(0, semicolon);
                    name = name.trim().replace("\"", "");
                }
            }
        }
        if (name == null || name.trim().isEmpty()) {
            try {
                name = Uri.parse(url).getLastPathSegment();
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.trim().isEmpty()) name = "download";
        return sanitize(name);
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    /* ===================== پل جاوااسکریپت ================================== */
    public class Bridge {

        @JavascriptInterface
        public String info() {
            return "{\"platform\":\"android\",\"sdk\":" + Build.VERSION.SDK_INT
                    + ",\"version\":\"" + BuildConfig.VERSION + "\"}";
        }

        @JavascriptInterface
        public void toast(String msg) {
            toastUi(msg);
        }

        /** دانلود از سرور تحلیل با DownloadManager (در پوشهٔ Downloads) */
        @JavascriptInterface
        public void saveUrl(String url, String name) {
            if (url == null) return;
            if (url.contains("://" + APP_HOST + "/")) {
                saveAppAssetToDownloads(url, sanitize(name));
                return;
            }
            String n = name == null || name.trim().isEmpty()
                    ? guessName(null, url) : sanitize(name);
            enqueueDownload(url, n, guessMimeFromName(n), Environment.DIRECTORY_DOWNLOADS);
        }

        /** ذخیرهٔ ویدیو در گالری (پوشهٔ Movies) */
        @JavascriptInterface
        public void saveToGallery(String url, String name) {
            String n = (name == null || name.trim().isEmpty()) ? "radde-seda.mp4" : sanitize(name);
            String mime = "application/octet-stream".equals(guessMimeFromName(n))
                    ? "video/mp4" : guessMimeFromName(n);
            downloadToGallery(url, fitName(n, mime));
        }

        /** شروع ذخیرهٔ تکه‌تکه (برای blobهای صفحه) — مقصد: پوشهٔ دانلودها */
        @JavascriptInterface
        public String beginSave(String name, String mime) {
            return beginSaveInternal(name, mime, "downloads", 0L);
        }

        /** شروع ذخیرهٔ تکه‌تکه با مقصد مشخص: "gallery" یا "downloads" */
        @JavascriptInterface
        public String beginSaveTo(String name, String mime, String target) {
            return beginSaveInternal(name, mime, target, 0L);
        }

        /**
         * همان beginSaveTo، به‌همراهِ طولِ ویدیو (میلی‌ثانیه).
         * طول در MediaStore.Video.Media.DURATION نوشته می‌شود؛ بعضی گالری‌ها
         * (و Google Photos) طول را فقط از متادیتای رسانه می‌خوانند و بدونِ
         * این مقدار، ویدیو «بدونِ طول» نشان داده می‌شود.
         */
        @JavascriptInterface
        public String beginSaveToEx(String name, String mime, String target, String durationMs) {
            long dur = 0L;
            try {
                if (durationMs != null && durationMs.trim().length() > 0) {
                    dur = (long) Double.parseDouble(durationMs.trim());
                }
            } catch (Exception ignored) {
            }
            return beginSaveInternal(name, mime, target, dur);
        }

        @JavascriptInterface
        public boolean appendSave(String token, String base64Chunk) {
            File f = pendingSaves.get(token);
            if (f == null) return false;
            try {
                byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                FileOutputStream fos = new FileOutputStream(f, true);
                try {
                    fos.write(bytes);
                    fos.flush();
                } finally {
                    fos.close();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void endSave(String token) {
            final File f = pendingSaves.remove(token);
            String[] meta = pendingMeta.remove(token);
            if (f == null) {
                notifySave(token, false, "ذخیره ناموفق بود");
                return;
            }
            final String name = (meta != null && meta[0] != null) ? meta[0] : "output";
            final String mime = (meta != null && meta[1] != null) ? meta[1] : guessMimeFromName(name);
            final boolean gallery = meta != null && meta.length > 2 && "gallery".equals(meta[2]);
            long durMs = 0L;
            if (meta != null && meta.length > 3 && meta[3] != null) {
                try { durMs = Long.parseLong(meta[3]); } catch (Exception ignored) {}
            }
            final long durationMs = durMs;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String msg;
                    try {
                        String where = finishSave(f, name, mime, gallery, durationMs);
                        msg = "✅ ذخیره شد: " + where;
                        toastUi(msg);
                        notifySave(token, true, msg);
                    } catch (Exception e) {
                        msg = "❌ ذخیره ناموفق بود: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                        toastUi(msg);
                        notifySave(token, false, msg);
                    } finally {
                        f.delete();
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void abortSave(String token) {
            File f = pendingSaves.remove(token);
            pendingMeta.remove(token);
            if (f != null) f.delete();
        }

        private String beginSaveInternal(String name, String mime, String target, long durationMs) {
            try {
                File f = File.createTempFile("save_", ".part", getCacheDir());
                String token = UUID.randomUUID().toString();
                String m = (mime == null || mime.trim().isEmpty()) ? guessMimeFromName(name) : mime.trim();
                pendingSaves.put(token, f);
                pendingMeta.put(token, new String[]{
                        sanitize(fitName(name, m)),
                        m,
                        "gallery".equals(target) ? "gallery" : "downloads",
                        String.valueOf(durationMs)});
                return token;
            } catch (Exception e) {
                toastUi("شروع ذخیره ناموفق بود: " + e.getMessage());
                return null;
            }
        }
    }

    private final Map<String, String[]> pendingMeta = new ConcurrentHashMap<String, String[]>();

    /* ===================== کمکی‌ها ========================================= */
    private void toast(String msg) {
        toastUi(msg);
    }

    private void toastUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2200) {
            super.onBackPressed();
            return;
        }
        lastBackAt = now;
        toast("برای خروج دوباره «بازگشت» را بزن");
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.removeJavascriptInterface("NativeApp");
                web.setWebChromeClient(null);
                web.setWebViewClient(null);
                web.loadUrl("about:blank");
                web.destroy();
            }
        } catch (Exception ignored) {
        }
        for (File f : pendingSaves.values()) {
            try { f.delete(); } catch (Exception ignored) {}
        }
        pendingSaves.clear();
        pendingMeta.clear();
        super.onDestroy();
    }
}
