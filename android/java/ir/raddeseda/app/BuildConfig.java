package ir.raddeseda.app;

/**
 * مقادیر ساخت (این فایل را اسکریپت ساخت می‌تواند با متغیرهای محیطی بازنویسی کند)
 *
 * DEFAULT_API : آدرس سرور تحلیل که «پیش‌فرض» در اپ ست می‌شود.
 *               خالی = کاربر اولین بار خودش آدرس را وارد می‌کند (دکمهٔ «⚙ سرور»).
 *               پرکردن آن برای APK شخصی راحت‌تر است:
 *                   DEFAULT_API=https://my-server.example bash android/build_apk.sh
 */
public final class BuildConfig {
    public static final String VERSION = "1.0.0";
    public static final String DEFAULT_API = "";

    private BuildConfig() {
    }
}
