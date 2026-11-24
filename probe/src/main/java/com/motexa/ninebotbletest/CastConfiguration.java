package com.motexa.ninebotbletest;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class CastConfiguration {
    final int channel, codec, width, height, fps;
    final boolean supportedVersion;
    final JSONObject json;

    CastConfiguration(byte[] verifiedBytes) throws Exception {
        int size = verifiedBytes.length;
        if (size > 0 && verifiedBytes[size - 1] == 0) size--;
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(verifiedBytes, 0, size)).toString();
        json = new JSONObject(text);
        supportedVersion = supportsVersion(json.optString("version", ""));
        int rtp = route(json.optString("rtpChannel", ""));
        int transport = route(json.optString("channel", ""));
        channel = rtp >= 0 ? rtp : transport >= 0 ? transport : 0;
        int selectedCodec = route(json.optString("codecType", ""));
        if (selectedCodec < 0) {
            String name = json.optString("codecName", "").toLowerCase(java.util.Locale.ROOT);
            selectedCodec = name.equals("mpeg2") ? 0 : name.equals("h264") ? 1 : name.equals("mjpeg") ? 2 : -1;
        }
        codec = selectedCodec;
        width = dimension("codecWidth", "dimensionWidth");
        height = dimension("codecHeight", "dimensionHeight");
        fps = json.optInt("frameRate", 10);
    }

    boolean accepts(Mpeg2ProbeClip clip) {
        return supportedVersion && channel == 0 && codec == 0 && width > 0 && height > 0
                && width == clip.width && height == clip.height && fps >= clip.fps;
    }
    String summary() {
        return "承载=" + (channel == 0 ? "BLE" : channel == 1 ? "Wi-Fi" : "Wi-Fi KCP")
                + " 编码=" + (codec == 0 ? "MPEG2" : codec == 1 ? "H264" : codec == 2 ? "MJPEG" : "未知")
                + " " + width + "×" + height + " " + fps + "fps" + (supportedVersion ? "" : " · 配置版本不支持");
    }
    private static boolean supportsVersion(String version) {
        String major = version.split("\\.", -1)[0];
        if (!major.matches("[+-]?[0-9]+")) return true;
        try { return Integer.parseInt(major) <= 5; }
        catch (NumberFormatException ignored) { return true; }
    }
    private int dimension(String primary, String fallback) {
        return json.has(primary) && !json.isNull(primary) ? json.optInt(primary, 0) : json.optInt(fallback, 0);
    }
    private static int route(String value) {
        return "0".equals(value) ? 0 : "1".equals(value) ? 1 : "2".equals(value) ? 2 : -1;
    }
}
