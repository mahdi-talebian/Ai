#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ساخت/به‌روزرسانیِ شاخهٔ مادرِ «android» در GitHub
#
# این شاخه کنار main و MOBILE می‌ایستد و **مستقل** است:
#   • هیچ‌وقت با main یا MOBILE مرج نمی‌شود (این اسکریپت هیچ merge/PR نمی‌سازد)
#   • فقط «جلو بردن» (fast-forward) مجاز است؛ بازنویسیِ تاریخ ممنوع
#   • اگر شاخهٔ android جلوتر یا جداافتاده باشد، اسکریپت کاری نمی‌کند و
#     دلیلش را می‌گوید (هیچ چیزی پاک/بازنویسی نمی‌شود)
#
# استفاده:
#   bash android-qari/tools/sync_android_branch.sh            # ساخت یا هم‌گام‌سازی
#   bash android-qari/tools/sync_android_branch.sh --dry-run  # فقط بررسی، بدون تغییر
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO="${REPO:-mahdi-talebian/Ai}"
TARGET="${TARGET:-android}"
SOURCE="${SOURCE:-arena/36b6e6b3-ai}"
DRY=0
[[ "${1:-}" == "--dry-run" ]] && DRY=1

cd "$(git rev-parse --show-toplevel)"

say()  { printf '%s\n' "$*"; }
fail() { printf '✖ %s\n' "$*" >&2; exit 1; }

# ── ۱) وضعیت شاخهٔ منبع ────────────────────────────────────────────────────
git fetch -q origin "$SOURCE" 2>/dev/null || true
SRC_SHA="$(git rev-parse "origin/$SOURCE")"
say "منبع: $SOURCE → $SRC_SHA"
say "       $(git log -1 --format='%s' "$SRC_SHA")"

# ── ۲) وضعیت شاخهٔ مقصد روی گیت‌هاب ────────────────────────────────────────
REMOTE_SHA="$(git ls-remote --heads origin "refs/heads/$TARGET" | cut -f1 || true)"

if [[ -z "$REMOTE_SHA" ]]; then
  say "مقصد: $TARGET وجود ندارد → ساخته می‌شود"
  if [[ $DRY -eq 1 ]]; then
    say "   [dry-run] gh api -X POST repos/$REPO/git/refs -f ref=refs/heads/$TARGET -f sha=$SRC_SHA"
    exit 0
  fi
  gh api -X POST "repos/$REPO/git/refs" \
     -f "ref=refs/heads/$TARGET" -f "sha=$SRC_SHA" >/dev/null
  say "✅ ساخته شد: $TARGET → $SRC_SHA"
else
  say "مقصد: $TARGET → $REMOTE_SHA"
  if [[ "$REMOTE_SHA" == "$SRC_SHA" ]]; then
    say "✅ از قبل هم‌گام است؛ کاری لازم نیست"
    exit 0
  fi
  if ! git cat-file -e "$REMOTE_SHA^{commit}" 2>/dev/null; then
    git fetch -q origin "$TARGET" || true
  fi
  if ! git merge-base --is-ancestor "$REMOTE_SHA" "$SRC_SHA"; then
    if git merge-base --is-ancestor "$SRC_SHA" "$REMOTE_SHA"; then
      fail "$TARGET جلوتر از $SOURCE است. چیزی عوض نشد (بازنویسی ممنوع)."
    fi
    fail "$TARGET با $SOURCE هم‌نیا نیست (تاریخ‌ها جدا شده‌اند). چیزی عوض نشد.
   برای حل دستی: شاخهٔ android را از $SRC_SHA بسازید یا تصمیم بگیرید کدام تاریخ بماند."
  fi
  say "مقصد عقب‌تر است → fast-forward (بدون merge)"
  if [[ $DRY -eq 1 ]]; then
    say "   [dry-run] gh api -X PATCH repos/$REPO/git/refs/heads/$TARGET -f sha=$SRC_SHA -F force=false"
    exit 0
  fi
  gh api -X PATCH "repos/$REPO/git/refs/heads/$TARGET" \
     -f "sha=$SRC_SHA" -F force=false >/dev/null
  say "✅ به‌روزرسانی شد: $TARGET → $SRC_SHA"
fi

# ── ۳) محافظت: جلوگیری از حذف و بازنویسیِ شاخهٔ مادر ────────────────────────
if [[ $DRY -eq 0 ]]; then
  gh api -X PUT "repos/$REPO/branches/$TARGET/protection" \
    --input - >/dev/null 2>&1 <<'JSON' || say "• (محافظت شاخه اعمال نشد — احتمالاً نیاز به دسترسی ادمین)"
{
  "required_status_checks": null,
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
  say "• محافظت: حذف/بازنویسیِ $TARGET ممنوع شد"
fi

say ""
say "یادآوری: این اسکریپت هیچ merge و هیچ pull requestی نمی‌سازد."
say "شاخهٔ $TARGET مستقل از main و MOBILE زندگی می‌کند."
