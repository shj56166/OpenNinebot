package com.motexa.ninebotbletest;

import java.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small pre-encoded raw AVPackets, with an independently decodable initial I picture. */
final class Mpeg2ProbeClip {
    final int width, height, fps;
    final List<byte[]> frames = new ArrayList<>();
    final List<Boolean> keys = new ArrayList<>();

    Mpeg2ProbeClip(InputStream input) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int size; (size = input.read(buffer)) >= 0;) {
            if (data.size() + size > 4 * 1024 * 1024) throw new IllegalArgumentException("clip-too-large");
            data.write(buffer, 0, size);
        }
        JSONObject json = new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
        if (!"mpeg2video".equals(json.getString("codec"))) throw new IllegalArgumentException("clip-codec");
        width = json.getInt("width"); height = json.getInt("height"); fps = json.getInt("fps");
        JSONArray packets = json.getJSONArray("packets");
        if (width <= 0 || height <= 0 || width > 4095 || height > 4095 || fps < 1 || fps > 30
                || packets.length() < 1 || packets.length() > 100) throw new IllegalArgumentException("clip-range");
        for (int i = 0; i < packets.length(); i++) {
            JSONObject packet = packets.getJSONObject(i);
            byte[] bytes = Base64.getDecoder().decode(packet.getString("base64"));
            boolean key = packet.getBoolean("key");
            if (bytes.length == 0 || bytes.length > 65536) throw new IllegalArgumentException("packet-size");
            int pictureType = pictureType(bytes);
            if (pictureType < 1 || pictureType > 3 || key != (pictureType == 1))
                throw new IllegalArgumentException("picture-type");
            if (i == 0 && (!key || !hasSequence(bytes, width, height)))
                throw new IllegalArgumentException("initial-sequence-and-key-required");
            frames.add(bytes); keys.add(key);
        }
    }

    private static int pictureType(byte[] data) {
        for (int i = 0; i + 5 < data.length; i++)
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && data[i + 3] == 0)
                return (data[i + 5] >>> 3) & 7;
        return -1;
    }
    private static boolean hasSequence(byte[] data, int width, int height) {
        for (int i = 0; i + 6 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 255) == 0xb3) {
                int w = ((data[i + 4] & 255) << 4) | ((data[i + 5] & 255) >> 4);
                int h = ((data[i + 5] & 15) << 8) | (data[i + 6] & 255);
                return w == width && h == height;
            }
        }
        return false;
    }
}
