package com.motexa.openninebot;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class CastPaletteTest {
    @Test public void keepsActualSourceColorAndDoesNotMoveBufferPosition() {
        ByteBuffer pixels = solid(32, 18, 28, 146, 224);
        CastPalette palette = CastPalette.fromRgba(pixels, 32, 18, 128, 4);
        assertEquals(1, palette.size()); assertEquals(0xff1c92e0, palette.color(0));
        assertEquals(1, palette.weight(0), .001f); assertEquals(0, pixels.position());
    }

    @Test public void favorsColoredContentOverWhiteAndBlackBackgrounds() {
        ByteBuffer pixels = solid(32, 18, 255, 255, 255);
        for (int y = 0; y < 18; y++) for (int x = 0; x < 5; x++) pixel(pixels, y * 128 + x * 4, 20, 180, 220, 255);
        CastPalette palette = CastPalette.fromRgba(pixels, 32, 18, 128, 4);
        assertEquals(0xff14b4dc, palette.color(0)); assertTrue(palette.weight(0) > .75f);
        assertSame(CastPalette.EMPTY, CastPalette.fromRgba(solid(32, 18, 0, 0, 0), 32, 18, 128, 4));
    }

    @Test public void supportsPaddedRowsPixelStrideAndNonzeroOrigin() {
        ByteBuffer pixels = ByteBuffer.allocate(7 + 2 * 20);
        pixels.position(7);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) pixel(pixels, 7 + y * 20 + x * 8, 190, 50, 90, 255);
        pixels.limit(7 + 20 + 8 + 4);
        CastPalette palette = CastPalette.fromRgba(pixels, 2, 2, 20, 8);
        assertEquals(1, palette.size()); assertEquals(0xffbe325a, palette.color(0)); assertEquals(7, pixels.position());
    }

    @Test public void mergesNearbyShadesAndKeepsAtMostThreeColors() {
        ByteBuffer pixels = solid(32, 18, 20, 180, 100);
        for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
            if (x < 8) pixel(pixels, y * 128 + x * 4, 30, 190, 110, 255);
            else if (x < 16) pixel(pixels, y * 128 + x * 4, 220, 30, 50, 255);
            else if (x < 24) pixel(pixels, y * 128 + x * 4, 20, 60, 230, 255);
        }
        CastPalette palette = CastPalette.fromRgba(pixels, 32, 18, 128, 4);
        assertEquals(3, palette.size());
        assertEquals(1, palette.weight(0) + palette.weight(1) + palette.weight(2), .001f);
    }

    @Test public void grayscaleStaysNeutralAndTransparentPixelsAreIgnored() {
        CastPalette gray = CastPalette.fromRgba(solid(8, 8, 150, 150, 150), 8, 8, 32, 4);
        assertEquals(1, gray.size()); assertEquals(0xff969696, gray.color(0));
        ByteBuffer transparent = solid(8, 8, 255, 0, 0);
        for (int i = 3; i < transparent.limit(); i += 4) transparent.put(i, (byte) 0);
        assertSame(CastPalette.EMPTY, CastPalette.fromRgba(transparent, 8, 8, 32, 4));
    }

    @Test public void invalidOrTruncatedPlanesDoNotReadPastTheBuffer() {
        ByteBuffer pixels = solid(8, 8, 255, 0, 0); pixels.limit(7);
        assertSame(CastPalette.EMPTY, CastPalette.fromRgba(pixels, 8, 8, 32, 4));
        assertSame(CastPalette.EMPTY, CastPalette.fromRgba(pixels, Integer.MAX_VALUE, 8, 32, 4));
        assertSame(CastPalette.EMPTY, CastPalette.fromRgba(null, 8, 8, 32, 4));
    }

    static ByteBuffer solid(int width, int height, int r, int g, int b) {
        ByteBuffer pixels = ByteBuffer.allocate(width * height * 4);
        for (int i = 0; i < width * height; i++) pixel(pixels, i * 4, r, g, b, 255);
        return pixels;
    }

    private static void pixel(ByteBuffer pixels, int at, int r, int g, int b, int a) {
        pixels.put(at, (byte) r); pixels.put(at + 1, (byte) g); pixels.put(at + 2, (byte) b); pixels.put(at + 3, (byte) a);
    }
}
