.class public final Lcom/mahditalebian/raddeseda/MainActivity$3;
.super Ljava/lang/Object;
.implements Landroid/content/DialogInterface$OnClickListener;

.field final outer:Lcom/mahditalebian/raddeseda/MainActivity;
.field final val$input:Landroid/widget/EditText;

.method public constructor <init>(Lcom/mahditalebian/raddeseda/MainActivity;Landroid/widget/EditText;)V
    .locals 0
    iput-object p1, p0, Lcom/mahditalebian/raddeseda/MainActivity$3;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    iput-object p2, p0, Lcom/mahditalebian/raddeseda/MainActivity$3;->val$input:Landroid/widget/EditText;
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public onClick(Landroid/content/DialogInterface;I)V
    .locals 4

    iget-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity$3;->val$input:Landroid/widget/EditText;
    invoke-virtual {v0}, Landroid/widget/TextView;->getText()Ljava/lang/CharSequence;
    move-result-object v0
    invoke-interface {v0}, Ljava/lang/CharSequence;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/String;->trim()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v1
    if-lez v1, :done

    iget-object v1, p0, Lcom/mahditalebian/raddeseda/MainActivity$3;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    invoke-virtual {v1}, Lcom/mahditalebian/raddeseda/MainActivity;->prefs()Landroid/content/SharedPreferences;
    move-result-object v2
    invoke-interface {v2}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v2
    sget-object v3, Lcom/mahditalebian/raddeseda/MainActivity;->KEY_URL:Ljava/lang/String;
    invoke-interface {v2, v3, v0}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;
    move-result-object v2
    invoke-interface {v2}, Landroid/content/SharedPreferences$Editor;->apply()V

    iget-object v1, p0, Lcom/mahditalebian/raddeseda/MainActivity$3;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    iget-object v1, v1, Lcom/mahditalebian/raddeseda/MainActivity;->web:Landroid/webkit/WebView;
    invoke-virtual {v1, v0}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    :done
    return-void
.end method
