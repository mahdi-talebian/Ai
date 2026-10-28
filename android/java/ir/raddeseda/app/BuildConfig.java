package ir.raddeseda.app;

/**
 * مقادیر ساخت (این فایل را اسکریپت ساخت می‌تواند با متغیرهای محیطی بازنویسی کند)
 *
 * DEFAULT_API : آدرس سرور تحلیل. در نسخهٔ آفلاین (پیش‌فرض) **خالی** است و
 *               هیچ درخواست شبکه‌ای زده نمی‌شود؛ همهٔ تحلیل روی گوشی اجرا
 *               می‌شود. فقط اگر عمداً نسخهٔ سروری بخواهید پر کنید:
 *                   DEFAULT_API=https://my-server.example bash android/build_apk.sh
 */
public final class BuildConfig {
    public static final String VERSION = "1.2.3";
    public static final String DEFAULT_API = "";

    private BuildConfig() {
    }
}
