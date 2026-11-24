package com.motexa.openninebot;

import java.nio.ByteBuffer;

/** A small, immutable summary of source pixels. No image or screen coordinates are retained. */
final class CastPalette {
    static final CastPalette EMPTY = new CastPalette(new int[0], new float[0]);
    static final int SAMPLE_WIDTH = 32, SAMPLE_HEIGHT = 18;
    private final int[] colors;
    private final float[] weights;

    CastPalette(int[] colors, float[] weights) {
        if (colors.length != weights.length || colors.length > 3) throw new IllegalArgumentException("palette_size");
        this.colors = colors.clone();
        this.weights = weights.clone();
    }

    int size() { return colors.length; }
    int color(int index) { return colors[index]; }
    float weight(int index) { return weights[index]; }

    static CastPalette fromRgba(ByteBuffer pixels, int width, int height, int rowStride, int pixelStride) {
        if (pixels == null || width <= 0 || height <= 0 || pixelStride < 4
                || rowStride < (long) width * pixelStride) return EMPTY;
        int origin = pixels.position();
        if ((long) origin + (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride + 4 > pixels.limit()) return EMPTY;
        float[] histogram = new float[512], red = new float[512], green = new float[512], blue = new float[512];
        int columns = Math.min(SAMPLE_WIDTH, width), rows = Math.min(SAMPLE_HEIGHT, height);
        for (int y = 0; y < rows; y++) {
            int row = Math.min(height - 1, (int) ((y + .5f) * height / rows));
            for (int x = 0; x < columns; x++) {
                int column = Math.min(width - 1, (int) ((x + .5f) * width / columns));
                int at = origin + row * rowStride + column * pixelStride;
                int r = pixels.get(at) & 255, g = pixels.get(at + 1) & 255, b = pixels.get(at + 2) & 255;
                int alpha = pixels.get(at + 3) & 255;
                int maximum = Math.max(r, Math.max(g, b)), minimum = Math.min(r, Math.min(g, b));
                if (alpha < 16 || maximum < 18) continue;
                float weight = alpha / 255f * (maximum - minimum < 24 ? maximum > 232 ? .05f : .18f : 1f);
                int bin = (r >> 5) * 64 + (g >> 5) * 8 + (b >> 5);
                histogram[bin] += weight;
                red[bin] += r * weight; green[bin] += g * weight; blue[bin] += b * weight;
            }
        }
        int[] result = new int[3]; float[] amounts = new float[3]; int count = 0;
        while (count < 3) {
            int best = -1;
            for (int bin = 0; bin < histogram.length; bin++)
                if (histogram[bin] > 0 && (best < 0 || histogram[bin] > histogram[best])) best = bin;
            if (best < 0) break;
            int reference = rgb(red[best] / histogram[best], green[best] / histogram[best], blue[best] / histogram[best]);
            float amount = 0, r = 0, g = 0, b = 0;
            for (int bin = 0; bin < histogram.length; bin++) {
                if (histogram[bin] == 0) continue;
                int candidate = rgb(red[bin] / histogram[bin], green[bin] / histogram[bin], blue[bin] / histogram[bin]);
                if (distance(reference, candidate) > 64 * 64) continue;
                amount += histogram[bin]; r += red[bin]; g += green[bin]; b += blue[bin];
                histogram[bin] = 0;
            }
            result[count] = rgb(r / amount, g / amount, b / amount);
            amounts[count++] = amount;
        }
        if (count == 0) return EMPTY;
        int[] colors = new int[count]; float[] weights = new float[count]; float total = 0;
        for (int i = 0; i < count; i++) total += amounts[i];
        for (int i = 0; i < count; i++) { colors[i] = result[i]; weights[i] = amounts[i] / total; }
        return new CastPalette(colors, weights);
    }

    static int distance(int first, int second) {
        int r = ((first >> 16) & 255) - ((second >> 16) & 255);
        int g = ((first >> 8) & 255) - ((second >> 8) & 255);
        int b = (first & 255) - (second & 255);
        return r * r + g * g + b * b;
    }

    private static int rgb(float r, float g, float b) {
        return 0xff000000 | Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
    }
}
