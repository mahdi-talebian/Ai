.class public final Lcom/mahditalebian/raddeseda/MainActivity$1;
.super Landroid/webkit/WebChromeClient;

.field final outer:Lcom/mahditalebian/raddeseda/MainActivity;

.method public constructor <init>(Lcom/mahditalebian/raddeseda/MainActivity;)V
    .locals 0
    iput-object p1, p0, Lcom/mahditalebian/raddeseda/MainActivity$1;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    invoke-direct {p0}, Landroid/webkit/WebChromeClient;-><init>()V
    return-void
.end method

.method public onShowFileChooser(Landroid/webkit/WebView;Landroid/webkit/ValueCallback;Landroid/webkit/WebChromeClient$FileChooserParams;)Z
    .locals 4

    iget-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity$1;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    iget-object v1, v0, Lcom/mahditalebian/raddeseda/MainActivity;->fileCb:Landroid/webkit/ValueCallback;
    if-eqz v1, :store
    const/4 v2, 0x0
    invoke-virtual {v1, v2}, Landroid/webkit/ValueCallback;->onReceiveValue(Ljava/lang/Object;)V

    :store
    iget-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity$1;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    iput-object p2, v0, Lcom/mahditalebian/raddeseda/MainActivity;->fileCb:Landroid/webkit/ValueCallback;

    new-instance v0, Landroid/content/Intent;
    const-string v1, "android.intent.action.GET_CONTENT"
    invoke-direct {v0, v1}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
    const-string v1, "android.intent.category.OPENABLE"
    invoke-virtual {v0, v1}, Landroid/content/Intent;->addCategory(Ljava/lang/String;)Landroid/content/Intent;
    const-string v1, "*/*"
    invoke-virtual {v0, v1}, Landroid/content/Intent;->setType(Ljava/lang/String;)Landroid/content/Intent;

    const/4 v1, 0x3
    new-array v1, v1, [Ljava/lang/String;
    const-string v2, "audio/*"
    const/4 v3, 0x0
    aput-object v2, v1, v3
    const-string v2, "video/*"
    const/4 v3, 0x1
    aput-object v2, v1, v3
    const-string v2, "application/octet-stream"
    const/4 v3, 0x2
    aput-object v2, v1, v3
    const-string v2, "android.intent.extra.MIME_TYPES"
    invoke-virtual {v0, v2, v1}, Landroid/content/Intent;->putExtra(Ljava/lang/String;[Ljava/lang/String;)Landroid/content/Intent;

    const-string v1, "انتخاب فایل"
    invoke-static {v0, v1}, Landroid/content/Intent;->createChooser(Landroid/content/Intent;Ljava/lang/CharSequence;)Landroid/content/Intent;
    move-result-object v0

    :try_start_0
    iget-object v1, p0, Lcom/mahditalebian/raddeseda/MainActivity$1;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    const/16 v2, 0x2a
    invoke-virtual {v1, v0, v2}, Landroid/app/Activity;->startActivityForResult(Landroid/content/Intent;I)V
    :try_end_0
    goto :ok

    :catch_0
    move-exception v0
    iget-object v1, p0, Lcom/mahditalebian/raddeseda/MainActivity$1;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    const/4 v2, 0x0
    iput-object v2, v1, Lcom/mahditalebian/raddeseda/MainActivity;->fileCb:Landroid/webkit/ValueCallback;
    invoke-virtual {p2, v2}, Landroid/webkit/ValueCallback;->onReceiveValue(Ljava/lang/Object;)V
    const/4 v0, 0x0
    return v0

    :ok
    const/4 v0, 0x1
    return v0

    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
.end method
