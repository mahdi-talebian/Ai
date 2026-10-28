#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
zipalign.py — هم‌ترازسازی APK به‌جای `zipalign` از Android SDK

چرا لازم است؟
  اندروید ۱۱+ (targetSdk 30+) قبول نمی‌کند که `resources.arsc` فشرده یا
  ناهم‌تراز باشد. خروجی `aapt2 link` هم‌تراز نیست؛ بنابراین پیش از امضا،
  ورودی‌های «ذخیره‌شده» (STORED) را با فیلد اضافی (extra field) به مرز
  ۴ بایت (و برای کتابخانه‌های .so به ۴۰۹۶ بایت) می‌رسانیم.

استفاده:
    python3 zipalign.py in.apk out.apk
"""
import struct
import sys
import zipfile
import zlib

ALIGN_EXTRA_ID = 0xD935          # شناسهٔ فیلد اضافیِ هم‌ترازی اندروید


def align_for(name):
    return 4096 if name.endswith(".so") else 4


def make_extra(header_end, align):
    """فیلد اضافی‌ای که شروع داده را روی مضرب align می‌برد."""
    # اول طول زوج (سازگارتر با ابزارهای قدیمی)، بعد هر طولی
    for even_only in (True, False):
        for pad_len in range(0, align + 16):
            extra_len = 6 + pad_len                 # id(2)+size(2)+align(2)+pad
            if even_only and extra_len % 2:
                continue
            if (header_end + extra_len) % align == 0:
                return (struct.pack("<HHH", ALIGN_EXTRA_ID, 2 + pad_len, align)
                        + b"\x00" * pad_len)
    return b""


def dos_time_date(dt):
    t = (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2)
    d = ((max(dt[0], 1980) - 1980) << 9) | (dt[1] << 5) | dt[2]
    return t & 0xFFFF, d & 0xFFFF


def main(src, dst):
    zin = zipfile.ZipFile(src, "r")
    entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
    zin.close()

    out = open(dst, "wb")
    central = []

    for info, data in entries:
        name = info.filename.encode("utf-8")
        flags = info.flag_bits & ~0x0008            # بدون data-descriptor
        t, d = dos_time_date(info.date_time)
        crc = zlib.crc32(data) & 0xFFFFFFFF

        if info.compress_type == zipfile.ZIP_DEFLATED:
            method = zipfile.ZIP_DEFLATED
            co = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = co.compress(data) + co.flush()
        else:
            method = zipfile.ZIP_STORED
            payload = data

        offset = out.tell()
        extra = b""
        if method == zipfile.ZIP_STORED:
            extra = make_extra(offset + 30 + len(name), align_for(info.filename))

        out.write(struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, flags, method, t, d,
                              crc, len(payload), len(data), len(name), len(extra)))
        out.write(name)
        out.write(extra)
        out.write(payload)

        central.append((name, flags, method, t, d, crc, len(payload), len(data),
                        extra, offset, info.external_attr))

    cd_start = out.tell()
    for (name, flags, method, t, d, crc, csize, usize, extra, offset, attrs) in central:
        out.write(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 0x031E, 20, flags,
                              method, t, d, crc, csize, usize, len(name), len(extra),
                              0, 0, 0, attrs or (0o644 << 16), offset))
        out.write(name)
        out.write(extra)
    cd_size = out.tell() - cd_start

    out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central),
                          cd_size, cd_start, 0))
    out.close()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: zipalign.py in.apk out.apk")
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
