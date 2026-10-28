.class public final Lcom/mahditalebian/raddeseda/MainActivity$4;
.super Ljava/lang/Object;
.implements Landroid/content/DialogInterface$OnClickListener;

.field final outer:Lcom/mahditalebian/raddeseda/MainActivity;

.method public constructor <init>(Lcom/mahditalebian/raddeseda/MainActivity;)V
    .locals 0
    iput-object p1, p0, Lcom/mahditalebian/raddeseda/MainActivity$4;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public onClick(Landroid/content/DialogInterface;I)V
    .locals 1
    iget-object v0, p0, Lcom/mahditalebian/raddeseda/MainActivity$4;->outer:Lcom/mahditalebian/raddeseda/MainActivity;
    if-nez p2, :do_exit
    invoke-virtual {v0}, Lcom/mahditalebian/raddeseda/MainActivity;->showUrlDialog()V
    return-void
    :do_exit
    invoke-virtual {v0}, Landroid/app/Activity;->finish()V
    return-void
.end method
