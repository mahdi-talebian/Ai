#!/usr/bin/env bash
# ==========================================================================
# init-ssl.sh — دریافت اولین گواهی SSL از Let's Encrypt (فقط یک‌بار اجرا شود)
#
# مشکل مرغ‌وتخم‌مرغ: nginx.conf.template نهایی به گواهی SSL نیاز دارد که
# هنوز وجود ندارد (nginx بدون گواهی موجود اصلاً بالا نمی‌آید)؛ اما
# certbot هم برای گرفتن گواهی نیاز دارد که nginx روی پورت ۸۰ بالا باشد
# (تا چالش ACME HTTP-01 را پاسخ دهد). این اسکریپت این چرخه را با یک
# پیکربندی nginx «موقت» (فقط HTTP، بدون نیاز به گواهی) می‌شکند.
#
# اجرا: cd audio_analyzer && ./deploy/init-ssl.sh
# ==========================================================================
set -e

if [ ! -f .env ]; then
    echo "خطا: فایل .env پیدا نشد. اول از .env.example کپی و پر کنید:"
    echo "  cp .env.example .env && nano .env"
    exit 1
fi

set -a
source .env
set +a

if [ -z "$DOMAIN" ] || [ -z "$EMAIL" ]; then
    echo "خطا: DOMAIN و EMAIL باید در .env مقداردهی شده باشند."
    exit 1
fi

echo "==> دامنه: $DOMAIN | ایمیل: $EMAIL"

mkdir -p deploy
cp deploy/nginx.conf.template deploy/nginx.conf.template.bak

echo "==> مرحله ۱/۴: بالا آوردن nginx با پیکربندی موقت (بدون SSL) برای اعتبارسنجی دامنه..."
cp deploy/nginx.bootstrap.conf.template deploy/nginx.conf.template
docker compose up -d nginx

echo "==> منتظر آماده شدن nginx..."
sleep 5

echo "==> مرحله ۲/۴: درخواست گواهی SSL از Let's Encrypt..."
docker compose run --rm certbot certonly \
    --webroot -w /var/www/certbot \
    -d "$DOMAIN" \
    --email "$EMAIL" \
    --agree-tos \
    --no-eff-email \
    --non-interactive

echo "==> مرحله ۳/۴: بازگردانی پیکربندی نهایی nginx (با HTTPS)..."
mv deploy/nginx.conf.template.bak deploy/nginx.conf.template

echo "==> مرحله ۴/۴: راه‌اندازی مجدد کامل با HTTPS..."
docker compose up -d --build

echo ""
echo "تمام شد! سایت باید الان از طریق https://$DOMAIN در دسترس باشد."
echo "برای دیدن لاگ‌ها: docker compose logs -f"
