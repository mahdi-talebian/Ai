.class public Lcom/mahditalebian/raddeseda/MainActivity;
.super Landroid/app/Activity;
.source "MainActivity.java"

# آدرس پیش‌فرض سرور تحلیل (پیش‌نمایش زنده) — از منو/دیالوگ قابل تغییر است
.field static final DEFAULT_URL:Ljava/lang/String; = "https://8080-iyo54ifopfphhynhw48uj.e2b.app"
.field static final PREFS:Ljava/lang/String; = "raddeseda"
.field static final KEY_URL:Ljava/lang/String; = "server_url"
.field static final REQ_PICK:I = 0x2a

.field web:Landroid/webkit/WebView;
.field fileCb:Landroid/webkit/ValueCallback;


# =====================================================================
.method protected onCreate(Landroid/os/Bundle;)V
    .locals 5

    invoke-direct {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    # WebView
    new-instance v0, Landroid/webkit/WebView;
    invoke-direct {v0, p0}, Landroid/webkit/WebView;-><init>(Landroid/content/Context;)V
    iput-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity;->web:Landroid/webkit/WebView;
    invoke-virtual {p0, v0}, Landroid/app/Activity;->setContentView(Landroid/view/View;)V

    # تنظیمات WebView
    invoke-virtual {v0}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;
    move-result-object v1

    const/4 v2, 0x1
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setJavaScriptEnabled(Z)V
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setDomStorageEnabled(Z)V
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setDatabaseEnabled(Z)V
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setLoadWithOverviewMode(Z)V
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setUseWideViewPort(Z)V
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setMediaPlaybackRequiresUserGesture(Z)V
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setAllowContentAccess(Z)V

    const/4 v3, 0x0
    invoke-virtual {v1, v3}, Landroid/webkit/WebSettings;->setAllowFileAccess(Z)V

    const/4 v2, 0x1    # MIXED_CONTENT_COMPATIBILITY_MODE
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setMixedContentMode(I)V

    # کوکی‌ها
    invoke-static {}, Landroid/webkit/CookieManager;->getInstance()Landroid/webkit/CookieManager;
    move-result-object v1
    invoke-virtual {v1, v2}, Landroid/webkit/CookieManager;->setAcceptCookie(Z)V

    # WebChromeClient (انتخابگر فایل)
    iget-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity;->web:Landroid/webkit/WebView;
    new-instance v1, Lcom/mahditalebian/raddeseda/MainActivity$1;
    invoke-direct {v1, p0}, Lcom/mahditalebian/raddeseda/MainActivity$1;-><init>(Lcom/mahditalebian/raddeseda/MainActivity;)V
    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->setWebChromeClient(Landroid/webkit/WebChromeClient;)V

    # DownloadListener (ذخیرهٔ خروجی‌ها)
    new-instance v1, Lcom/mahditalebian/raddeseda/MainActivity$2;
    invoke-direct {v1, p0}, Lcom/mahditalebian/raddeseda/MainActivity$2;-><init>(Lcom/mahditalebian/raddeseda/MainActivity;)V
    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->setDownloadListener(Landroid/webkit/DownloadListener;)V

    # بارگذاری آدرس
    invoke-virtual {p0}, Lcom/mahditalebian/raddeseda/MainActivity;->currentUrl()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    # اولین اجرا: پرسیدن آدرس سرور
    invoke-virtual {p0}, Lcom/mahditalebian/raddeseda/MainActivity;->prefs()Landroid/content/SharedPreferences;
    move-result-object v1
    sget-object v2, Lcom/mahditalebian/raddeseda/MainActivity;->KEY_URL:Ljava/lang/String;
    invoke-interface {v1, v2}, Landroid/content/SharedPreferences;->contains(Ljava/lang/String;)Z
    move-result v2
    if-eqz v2, :first_run      # ذخنشده نیست → اولین اجرا
    goto :done

    :first_run
    invoke-virtual {p0}, Lcom/mahditalebian/raddeseda/MainActivity;->showUrlDialog()V

    :done
    return-void
.end method


# =====================================================================
.method prefs()Landroid/content/SharedPreferences;
    .locals 2

    const-string v0, "raddeseda"
    const/4 v1, 0x0
    invoke-virtual {p0, v0, v1}, Landroid/app/Activity;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
    move-result-object v0
    return-object v0
.end method


# =====================================================================
.method currentUrl()Ljava/lang/String;
    .locals 3

    invoke-virtual {p0}, Lcom/mahditalebian/raddeseda/MainActivity;->prefs()Landroid/content/SharedPreferences;
    move-result-object v0
    sget-object v1, Lcom/mahditalebian/raddeseda/MainActivity;->KEY_URL:Ljava/lang/String;
    sget-object v2, Lcom/mahditalebian/raddeseda/MainActivity;->DEFAULT_URL:Ljava/lang/String;
    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method


# =====================================================================
# دیالوگ «آدرس سرور» — اولین اجرا یا از پشت دکمهٔ بازگشت
# =====================================================================
.method showUrlDialog()V
    .locals 6

    new-instance v0, Landroid/widget/EditText;
    invoke-direct {v0, p0}, Landroid/widget/EditText;-><init>(Landroid/content/Context;)V

    invoke-virtual {p0}, Lcom/mahditalebian/raddeseda/MainActivity;->currentUrl()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setText(Ljava/lang/CharSequence;)V

    const-string v1, "https://…"
    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setHint(Ljava/lang/CharSequence;)V
    const/4 v1, 0x1
    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setSelectAllOnFocus(Z)V
    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setSingleLine(Z)V
    const/4 v1, 0x2
    invoke-virtual {v0, v1}, Landroid/view/View;->setTextDirection(I)V

    new-instance v1, Landroid/widget/FrameLayout;
    invoke-direct {v1, p0}, Landroid/widget/FrameLayout;-><init>(Landroid/content/Context;)V

    invoke-virtual {p0}, Landroid/app/Activity;->getResources()Landroid/content/res/Resources;
    move-result-object v2
    invoke-virtual {v2}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;
    move-result-object v2
    iget v2, v2, Landroid/util/DisplayMetrics;->density:F
    const/high16 v3, 0x41800000
    mul-float v2, v2, v3
    float-to-int v2, v2
    invoke-virtual {v1, v2, v2, v2, v2}, Landroid/widget/FrameLayout;->setPadding(IIII)V
    invoke-virtual {v1, v0}, Landroid/widget/FrameLayout;->addView(Landroid/view/View;)V

    new-instance v2, Landroid/app/AlertDialog$Builder;
    invoke-direct {v2, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V

    const-string v3, "آدرس سرور تحلیل"
    invoke-virtual {v2, v3}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;
    move-result-object v2

    const-string v3, "صفحهٔ «ردِّ صدا» از این آدرس بارگذاری می‌شود."
    invoke-virtual {v2, v3}, Landroid/app/AlertDialog$Builder;->setMessage(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;
    move-result-object v2

    invoke-virtual {v2, v1}, Landroid/app/AlertDialog$Builder;->setView(Landroid/view/View;)Landroid/app/AlertDialog$Builder;
    move-result-object v2

    const/4 v3, 0x1
    invoke-virtual {v2, v3}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;
    move-result-object v2

    const-string v3, "انصراف"
    const/4 v4, 0x0
    invoke-virtual {v2, v3, v4}, Landroid/app/AlertDialog$Builder;->setNegativeButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;
    move-result-object v2

    const-string v3, "ذخیره و بازکردن"
    new-instance v4, Lcom/mahditalebian/raddeseda/MainActivity$3;
    invoke-direct {v4, p0, v0}, Lcom/mahditalebian/raddeseda/MainActivity$3;-><init>(Lcom/mahditalebian/raddeseda/MainActivity;Landroid/widget/EditText;)V
    invoke-virtual {v2, v3, v4}, Landroid/app/AlertDialog$Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;
    move-result-object v2

    invoke-virtual {v2}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;
    return-void
.end method


# =====================================================================
# ذخیرهٔ فایل با DownloadManager در پوشهٔ دانلودها
# =====================================================================
.method enqueueDownload(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .locals 6

    :try_start_0
    new-instance v0, Landroid/app/DownloadManager$Request;
    invoke-static {p1}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
    move-result-object v1
    invoke-direct {v0, v1}, Landroid/app/DownloadManager$Request;-><init>(Landroid/net/Uri;)V

    const/4 v1, 0x1
    invoke-virtual {v0, v1}, Landroid/app/DownloadManager$Request;->setNotificationVisibility(I)Landroid/app/DownloadManager$Request;

    invoke-virtual {v0, p4}, Landroid/app/DownloadManager$Request;->setMimeType(Ljava/lang/String;)V

    invoke-static {p2, p1}, Lcom/mahditalebian/raddeseda/MainActivity;->fileNameFrom(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Landroid/app/DownloadManager$Request;->setTitle(Ljava/lang/CharSequence;)V

    sget-object v2, Landroid/os/Environment;->DIRECTORY_DOWNLOADS:Ljava/lang/String;
    invoke-virtual {v0, v2, v1}, Landroid/app/DownloadManager$Request;->setDestinationInExternalPublicDir(Ljava/lang/String;Ljava/lang/String;)V

    invoke-static {}, Landroid/webkit/CookieManager;->getInstance()Landroid/webkit/CookieManager;
    move-result-object v2
    invoke-virtual {v2, p1}, Landroid/webkit/CookieManager;->getCookie(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    if-eqz v2, :no_cookie
    const-string v3, "Cookie"
    invoke-virtual {v0, v3, v2}, Landroid/app/DownloadManager$Request;->addRequestHeader(Ljava/lang/String;Ljava/lang/String;)V
    :no_cookie

    if-eqz p2, :no_ua
    const-string v3, "User-Agent"
    invoke-virtual {v0, v3, p2}, Landroid/app/DownloadManager$Request;->addRequestHeader(Ljava/lang/String;Ljava/lang/String;)V
    :no_ua

    const-string v3, "download"
    invoke-virtual {p0, v3}, Landroid/app/Activity;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
    move-result-object v3
    check-cast v3, Landroid/app/DownloadManager;
    invoke-virtual {v3, v0}, Landroid/app/DownloadManager;->enqueue(Landroid/app/DownloadManager$Request;)J
    move-result-wide v4
    :try_end_0
    goto :ok

    :catch_0
    move-exception v0
    const-string v1, "دانلود انجام نشد"
    invoke-virtual {p0, v1}, Lcom/mahditalebian/raddeseda/MainActivity;->toast(Ljava/lang/String;)V
    goto :done

    :ok
    const-string v1, "ذخیره در پوشهٔ دانلودها…"
    invoke-virtual {p0, v1}, Lcom/mahditalebian/raddeseda/MainActivity;->toast(Ljava/lang/String;)V

    :done
    return-void

    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
.end method


# =====================================================================
# نام فایل از Content-Disposition (یا آخرین بخشِ URL)
# =====================================================================
.method private static fileNameFrom(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .locals 5

    if-eqz p0, :from_url

    sget-object v1, Ljava/util/Locale;->US:Ljava/util/Locale;
    invoke-virtual {p0, v1}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;
    move-result-object v1
    const-string v2, "filename="
    invoke-virtual {v1, v2}, Ljava/lang/String;->indexOf(Ljava/lang/String;)I
    move-result v1
    if-ltz v1, :from_url       # پیدا نشد (-1) → از URL

    add-int/lit8 v1, v1, 0x8
    invoke-virtual {p0, v1}, Ljava/lang/String;->substring(I)Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/String;->trim()Ljava/lang/String;
    move-result-object v0

    const-string v2, "\""
    invoke-virtual {v0, v2}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v3
    if-eqz v3, :check
    const/4 v3, 0x1
    invoke-virtual {v0, v2, v3}, Ljava/lang/String;->indexOf(Ljava/lang/String;I)I
    move-result v3
    if-ltz v3, :check
    const/4 v4, 0x0
    invoke-virtual {v0, v4, v3}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v0

    :check
    const-string v2, ";"
    invoke-virtual {v0, v2}, Ljava/lang/String;->indexOf(Ljava/lang/String;)I
    move-result v3
    if-ltz v3, :checked
    const/4 v4, 0x0
    invoke-virtual {v0, v4, v3}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v0

    :checked
    const-string v2, "/"
    const-string v4, "_"
    invoke-virtual {v0, v2, v4}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/String;->trim()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v3
    if-lez v3, :from_url
    return-object v0

    :from_url
    invoke-static {p1}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
    move-result-object v0
    invoke-virtual {v0}, Landroid/net/Uri;->getLastPathSegment()Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :fallback       # null → نام پیش‌فرض
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v3
    if-lez v3, :fallback       # طول ≤ ۰ (خالی) → نام پیش‌فرض
    return-object v0           # نام معتبر → همان
    :fallback
    const-string v0, "radde-seda.bin"
    return-object v0
.end method


# =====================================================================
# نتیجهٔ انتخاب فایل → WebView
# =====================================================================
.method protected onActivityResult(IILandroid/content/Intent;)V
    .locals 6

    const/16 v0, 0x2a
    if-ne p1, v0, :super

    iget-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity;->fileCb:Landroid/webkit/ValueCallback;
    if-eqz v0, :ret

    const/4 v1, 0x0
    const/4 v2, -0x1
    if-ne p2, v2, :deliver
    if-eqz p3, :deliver

    invoke-virtual {p3}, Landroid/content/Intent;->getClipData()Landroid/content/ClipData;
    move-result-object v2
    if-eqz v2, :single

    invoke-virtual {v2}, Landroid/content/ClipData;->getItemCount()I
    move-result v3
    if-lez v3, :single

    new-array v1, v3, [Landroid/net/Uri;
    const/4 v4, 0x0
    :mloop
    if-ge v4, v3, :deliver
    invoke-virtual {v2, v4}, Landroid/content/ClipData;->getItemAt(I)Landroid/content/ClipData$Item;
    move-result-object v5
    invoke-virtual {v5}, Landroid/content/ClipData$Item;->getUri()Landroid/net/Uri;
    move-result-object v5
    aput-object v5, v1, v4
    add-int/lit8 v4, v4, 0x1
    goto :mloop

    :single
    invoke-virtual {p3}, Landroid/content/Intent;->getData()Landroid/net/Uri;
    move-result-object v2
    if-eqz v2, :deliver
    const/4 v1, 0x1
    new-array v1, v1, [Landroid/net/Uri;
    const/4 v4, 0x0
    aput-object v2, v1, v4

    :deliver
    invoke-virtual {v0, v1}, Landroid/webkit/ValueCallback;->onReceiveValue(Ljava/lang/Object;)V
    const/4 v0, 0x0
    iput-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity;->fileCb:Landroid/webkit/ValueCallback;
    return-void

    :super
    invoke-super {p0, p1, p2, p3}, Landroid/app/Activity;->onActivityResult(IILandroid/content/Intent;)V
    :ret
    return-void
.end method


# =====================================================================
# دکمهٔ بازگشت: در ریشه → دیالوگ آدرس/خروج
# =====================================================================
.method public onBackPressed()V
    .locals 4

    iget-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity;->web:Landroid/webkit/WebView;
    invoke-virtual {v0}, Landroid/webkit/WebView;->canGoBack()Z
    move-result v1
    if-eqz v1, :root
    invoke-virtual {v0}, Landroid/webkit/WebView;->goBack()V
    return-void

    :root
    new-instance v1, Landroid/app/AlertDialog$Builder;
    invoke-direct {v1, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V
    const-string v2, "ردِّ صدا"
    invoke-virtual {v1, v2}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;
    move-result-object v1

    const/4 v2, 0x2
    new-array v2, v2, [Ljava/lang/CharSequence;
    const-string v3, "تغییر آدرس سرور"
    const/4 v0, 0x0
    aput-object v3, v2, v0
    const-string v3, "خروج"
    const/4 v0, 0x1
    aput-object v3, v2, v0

    new-instance v3, Lcom/mahditalebian/raddeseda/MainActivity$4;
    invoke-direct {v3, p0}, Lcom/mahditalebian/raddeseda/MainActivity$4;-><init>(Lcom/mahditalebian/raddeseda/MainActivity;)V
    invoke-virtual {v1, v2, v3}, Landroid/app/AlertDialog$Builder;->setItems([Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;
    move-result-object v1
    invoke-virtual {v1}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;
    return-void
.end method


# =====================================================================
.method toast(Ljava/lang/String;)V
    .locals 2
    const/4 v0, 0x0
    invoke-static {p0, p1, v0}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
    move-result-object v0
    invoke-virtual {v0}, Landroid/widget/Toast;->show()V
    return-void
.end method
