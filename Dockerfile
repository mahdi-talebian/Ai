########################################################################
# Dockerfile — تحلیلگر تلاوت قرآن (FastAPI + Praat/parselmouth + ffmpeg)
#
# این ایمیج شامل همهٔ وابستگی‌های سیستمی و پایتونی لازم برای اجرای
# کامل پروژه است (تحلیل صدا، رسم ملوگراف فارسی، خروجی ویدیو، مقایسه).
########################################################################

FROM python:3.11-slim

# --- وابستگی‌های سیستمی ---
# ffmpeg          : برش/ترکیب صدا-تصویر (compare trim، خروجی ویدیوی پیانو)
# libportaudio2   : برای import سالم piano_visualizer (حتی اگر میکروفون
#                   واقعی روی سرور استفاده نشود، ماژول در ایمپورت به آن نیاز دارد)
# libsndfile1     : بک‌اند soundfile برای خواندن/نوشتن wav/flac/...
# fonts-*         : فونت پایه برای matplotlib (پشتیبان، در کنار Vazirmatn)
# wget, ca-certificates : برای دانلود فونت Vazirmatn حین build
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        libportaudio2 \
        libsndfile1 \
        fonts-dejavu-core \
        wget \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# --- فونت Vazirmatn (برای نمایش صحیح متن فارسی/عربی در ملوگراف) ---
RUN mkdir -p /root/.fonts && \
    wget -q https://github.com/rastikerdar/vazirmatn/raw/master/fonts/ttf/Vazirmatn-Regular.ttf \
        -O /root/.fonts/Vazirmatn-Regular.ttf && \
    wget -q https://github.com/rastikerdar/vazirmatn/raw/master/fonts/ttf/Vazirmatn-Bold.ttf \
        -O /root/.fonts/Vazirmatn-Bold.ttf && \
    fc-cache -f /root/.fonts || true

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
