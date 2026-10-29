package ir.raddeseda.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * کدگذارِ بومی (MediaCodec) — پایهٔ مسیرِ «ویدیوی بومی» در اپ.
 * ---------------------------------------------------------------------------
 * چرا این کلاس هست:
 *   WebCodecs یک قابلیتِ خودِ WebView است؛ روی بعضی گوشی‌ها وجود ندارد و
 *   هیچ کتابخانهٔ جاوااسکریپتی جای آن را نمی‌گیرد. اندروید اما از اول یک
 *   کدگذارِ سخت‌افزاریِ H.264 دارد (MediaCodec). این کلاس همان را راه
 *   می‌اندازد و یک آزمونِ واقعی اجرا می‌کند: چند فریم را کدگذاری می‌کند، در
 *   فایلِ MP4 می‌نویسد و بعد طولِ فایل را با MediaMetadataRetriever می‌خواند.
 *
 * این مرحله فقط «اثباتِ توانِ دستگاه» است (probe + selfTest)؛ بعد از اینکه
 * روی گوشیِ واقعی عددها سالم بود، رندرِ فریم‌ها هم به همین مسیر منتقل می‌شود.
 */
final class NativeVideo {

    private NativeVideo() {
    }

    /** اطلاعاتِ کدگذارهای H.264 موجود روی دستگاه (JSON) */
    static String probeJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        int encoders = 0, hw = 0;
        boolean s1080 = false, s720 = false, s1080p60 = false;
        StringBuilder names = new StringBuilder();
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean avc = false;
            try {
                for (String t : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(t)) { avc = true; break; }
                }
            } catch (Exception ignored) {
            }
            if (!avc) continue;
            encoders++;
            if (names.length() > 0) names.append("، ");
            names.append(info.getName());
            try {
                MediaCodecInfo.CodecCapabilities caps =
                        info.getCapabilitiesForType("video/avc");
                MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                if (vc != null) {
                    if (vc.isSizeSupported(1920, 1080)) {
                        s1080 = true;
                        if (vc.getSupportedFrameRatesFor(1920, 1080).getUpper() >= 55) {
                            s1080p60 = true;
                        }
                    }
                    if (vc.isSizeSupported(1280, 720)) s720 = true;
                }
                try {
                    if (caps.isFormatSupported(MediaFormat.createVideoFormat(
                            "video/avc", 1280, 720))) hw++;
                } catch (Exception ignored) {
                }
            } catch (Exception ignored) {
            }
        }
        sb.append("\"encoders\":").append(encoders).append(",");
        sb.append("\"names\":\"").append(esc(names.toString())).append("\",");
        sb.append("\"avc720p\":").append(s720).append(",");
        sb.append("\"avc1080p\":").append(s1080).append(",");
        sb.append("\"avc1080p60\":").append(s1080p60).append(",");
        sb.append("\"aac\":").append(hasAacEncoder()).append(",");
        sb.append("\"sdk\":").append(android.os.Build.VERSION.SDK_INT);
        sb.append("}");
        return sb.toString();
    }

    private static boolean hasAacEncoder() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if ("audio/mp4a-latm".equalsIgnoreCase(t)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * آزمونِ واقعی: {@code frames} فریمِ رنگی را با نرخِ {@code fps} کدگذاری
     * می‌کند، در MP4 می‌نویسد و طولِ خروجی را از خودِ فایل می‌خواند.
     *
     * @return JSON با ok/ms/bytes/frames/durationMs/path/error
     */
    static String selfTestJson(File outFile, int w, int h, int frames, int fps) {
        long t0 = System.currentTimeMillis();
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        boolean muxStarted = false;
        int track = -1;
        long bytes = 0;
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
            int colorFmt = pickColorFormat();
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFmt);
            int bitrate = (int) Math.max(2000000, (long) w * h * fps * 0.09);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new MediaMuxer(outFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(0);

            byte[] frame = new byte[w * h * 3 / 2];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int submitted = 0, guard = 0;
            boolean inputDone = false;

            while (guard++ < 20000) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIdx);
                        if (submitted < frames) {
                            fillFrame(frame, w, h, submitted, frames, colorFmt);
                            in.clear();
                            in.put(frame);
                            long pts = (long) submitted * 1000000L / fps;
                            codec.queueInputBuffer(inIdx, 0, frame.length, pts, 0);
                            submitted++;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0,
                                    (long) submitted * 1000000L / fps,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (outIdx >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIdx);
                    if (out != null && info.size > 0 && muxStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        muxer.writeSampleData(track, out, info);
                        bytes += info.size;
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                if (inputDone && submitted >= frames && muxStarted) {
                    /* ادامهٔ حلقه تا EOS */
                }
            }

            long ms = System.currentTimeMillis() - t0;
            long durMs = readDurationMs(outFile);
            long size = outFile.length();
            return "{\"ok\":true,\"ms\":" + ms + ",\"bytes\":" + size
                    + ",\"encoded\":" + bytes
                    + ",\"frames\":" + frames
                    + ",\"fps\":" + fps
                    + ",\"w\":" + w + ",\"h\":" + h
                    + ",\"durationMs\":" + durMs
                    + ",\"colorFormat\":" + colorFmt
                    + ",\"path\":\"" + esc(outFile.getAbsolutePath()) + "\"}";
        } catch (Throwable e) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        } finally {
            try { if (codec != null) codec.stop(); } catch (Exception ignored) {}
            try { if (codec != null) codec.release(); } catch (Exception ignored) {}
            try { if (muxer != null && muxStarted) muxer.stop(); } catch (Exception ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
        }
    }

    /** طولِ واقعیِ فایلِ MP4 (۰ = خوانده نشد) */
    static long readDurationMs(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d == null) return 0;
            return Long.parseLong(d.trim());
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    /* ── رنگ‌بندی: هر کدگذاری یکی از این‌ها را می‌خواهد ─────────────────── */
    private static int pickColorFormat() {
        try {
            MediaCodecInfo.CodecCapabilities caps =
                    MediaCodec.createEncoderByType("video/avc")
                            .getCodecInfo().getCapabilitiesForType("video/avc");
            int[] prefs = new int[]{
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            };
            for (int p : prefs) {
                for (int c : caps.colorFormats) {
                    if (c == p) return p;
                }
            }
        } catch (Exception ignored) {
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    }

    /** فریمِ آزمایشی: گرادیانِ متحرک (Y) و رنگِ ثابتِ Cb/Cr */
    private static void fillFrame(byte[] buf, int w, int h, int i, int total, int colorFmt) {
        int ySize = w * h;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int v = ((x * 255 / w) + (y * 255 / h) + i * 255 / Math.max(1, total)) / 3;
                buf[row + x] = (byte) (16 + (v * 219 / 255));
            }
        }
        int u = 128 + (int) (40 * Math.sin(i / 4.0));
        int vC = 128 + (int) (40 * Math.cos(i / 4.0));
        if (colorFmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            int uStart = ySize;
            int vStart = ySize + ySize / 4;
            for (int k = 0; k < ySize / 4; k++) {
                buf[uStart + k] = (byte) u;
                buf[vStart + k] = (byte) vC;
            }
        } else {
            /* NV12: Y + (U,V) درهم */
            int uvStart = ySize;
            for (int k = 0; k < ySize / 4; k++) {
                buf[uvStart + k * 2] = (byte) u;
                buf[uvStart + k * 2 + 1] = (byte) vC;
            }
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        return b.toString();
    }
}
