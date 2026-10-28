########################################################################
# Dockerfile — ردِّ صدا (FastAPI + Praat/parselmouth + ffmpeg)
#
# این ایمیج شامل همهٔ وابستگی‌های سیستمی و پایتونی لازم برای اجرای
# پروژه است (تحلیل دامنهٔ صدا، تصویر PNG، خروجی ویدیو).
########################################################################

FROM python:3.11-slim

# --- وابستگی‌های سیستمی ---
# ffmpeg          : رندر ویدیوی «ردِّ صدا» (ورودی rawvideo + mux صدا)
# libsndfile1     : بک‌اند soundfile برای خواندن/نوشتن wav/flac/...
# fonts-dejavu-core : فونت برچسب‌های نمودار (نام نت‌ها + زمان)
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        libsndfile1 \
        fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# --- نصب وابستگی‌های پایتون (کش لایه‌ای docker: قبل از کپی کد) ---
COPY python/requirements.txt /app/python/requirements.txt
COPY server/requirements.txt /app/server/requirements.txt
RUN pip install --no-cache-dir -r /app/python/requirements.txt \
    && pip install --no-cache-dir -r /app/server/requirements.txt

# --- کپی کد پروژه ---
COPY python /app/python
COPY server /app/server
COPY web /app/web

WORKDIR /app/server

EXPOSE 8080

# --workers 1: تحلیل صدا CPU-bound و در ترد پس‌زمینه اجرا می‌شود؛ چند
# worker مستقل یعنی چند نسخهٔ جدا از JOBS (حافظه) که با هم هماهنگ
# نیستند. برای مقیاس بیشتر به‌جای افزایش worker از چند container پشت
# یک صف/بار-متعادل‌کن با state مشترک استفاده کنید (فعلاً خارج از scope).
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1"]
