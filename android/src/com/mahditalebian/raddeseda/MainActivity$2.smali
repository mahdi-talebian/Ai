.class public final Lcom/mahditalebian/raddeseda/MainActivity$2;
.super Ljava/lang/Object;
.implements Landroid/webkit/DownloadListener;

.field final outer:Lcom/mahditalebian/raddeseda/MainActivity;

.method public constructor <init>(Lcom/mahditalebian/raddeseda/MainActivity;)V
    .locals 0
    iput-object p1, p0, Lcom/mahditalebian/raddeseda/MainActivity$2;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public onDownloadStart(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V
    .locals 1
    iget-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity$2;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    invoke-virtual {v0, p1, p2, p3, p4}, Lcom/mahditalebian/raddeseda/MainActivity;->enqueueDownload(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    return-void
.end method
