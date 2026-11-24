package com.motexa.openninebot;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class CastInstrumentViewTest {
    private Context context(boolean dark) {
        Context context = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.uiMode = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        return context.createConfigurationContext(configuration);
    }

    private CastInstrumentView view(boolean dark) {
        CastInstrumentView view = new CastInstrumentView(context(dark), ignored -> {});
        view.layout(0, 0, 512, 288);
        return view;
    }

    @Test public void scalesProportionallyAtNarrowAndWideWidths() {
        CastInstrumentView view = view(false);
        for (int width : new int[]{240, 320, 512, 960}) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertEquals(width, view.getMeasuredWidth()); assertEquals(Math.round(width * 288f / 512), view.getMeasuredHeight());
        }
    }

    @Test public void offlineAndEmptySourceKeepSmallParticlesWithoutASolidPanelInBothAppearances() {
        for (boolean dark : new boolean[]{false, true}) {
            CastInstrumentView view = view(dark);
            try (RecordingCanvas canvas = new RecordingCanvas(dark)) {
                view.drawAt(canvas, 1000, false);
                assertEquals(0, canvas.solidPanels);
                assertEquals(canvas.background, canvas.colorAt(256, 148));
                assertNotEquals(canvas.background, canvas.particleColorNear(256, 148));
                assertTrue(canvas.particleRadiusNear(256, 148) < canvas.particleRadiusNear(76, 148));
                assertTrue(canvas.particleRadiusNear(8, 4) < canvas.particleRadiusNear(256, 148));
            }
            view.update(CastVisualFeed.State.ONLINE, CastPalette.EMPTY);
            try (RecordingCanvas canvas = new RecordingCanvas(dark)) {
                view.drawAt(canvas, 2000, false);
                assertEquals(0, canvas.solidPanels);
                assertNotEquals(canvas.background, canvas.particleColorNear(256, 148));
            }
        }
    }

    @Test public void everyAnimatedDotStaysOnTheSameGridAndStatesRemainAccessible() {
        CastInstrumentView view = view(true);
        for (CastVisualFeed.State state : CastVisualFeed.State.values()) {
            view.update(state, palette());
            try (RecordingCanvas canvas = new RecordingCanvas(true)) {
                view.drawAt(canvas, 5000, false);
                assertEquals(CastInstrumentView.Field.COUNT, canvas.positions);
                assertEquals(0, canvas.offGrid); assertEquals(0, canvas.solidPanels);
            }
        }
        assertTrue(view.getContentDescription().toString().contains("投屏中"));
        view.update(CastVisualFeed.State.OFFLINE, CastPalette.EMPTY);
        assertTrue(view.getContentDescription().toString().contains("未连接"));
    }

    @Test public void disconnectRestoresNeutralParticlesImmediatelyEvenDuringAColorTransition() {
        CastInstrumentView view = view(true);
        int offlineColor;
        try (RecordingCanvas canvas = new RecordingCanvas(true)) {
            view.drawAt(canvas, 1000, false); offlineColor = canvas.particleColorNear(256, 148);
        }
        view.update(CastVisualFeed.State.ONLINE, palette());
        view.update(CastVisualFeed.State.OFFLINE, CastPalette.EMPTY);
        try (RecordingCanvas canvas = new RecordingCanvas(true)) {
            view.drawAt(canvas, 1000, true); assertEquals(offlineColor, canvas.particleColorNear(256, 148));
        }
    }

    @Test public void exportsThreeStatesFromTheProductionCanvasDrawingCommands() throws Exception {
        File directory = new File("build/reports/cast-instrument");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        int panelHeight = header(false, false).getHeight();
        StringBuilder sheet = contactSheet(3, panelHeight), headers = contactSheet(2, panelHeight);
        for (int theme = 0; theme < 2; theme++) {
            boolean dark = theme == 1; CastInstrumentView view = view(dark);
            LinearLayout header = header(dark, false);
            for (int row = 0; row < 3; row++) {
                CastVisualFeed.State state = CastVisualFeed.State.values()[row]; view.update(state, palette());
                try (RecordingCanvas canvas = new RecordingCanvas(dark)) {
                    view.drawAt(canvas, 5000, false);
                    String name = (dark ? "dark-" : "light-") + state.name().toLowerCase(Locale.ROOT) + ".svg";
                    Files.write(new File(directory, name).toPath(), canvas.svg().getBytes(StandardCharsets.UTF_8));
                    appendHeader(sheet, header, canvas, dark, theme, row, panelHeight);
                    if (state == CastVisualFeed.State.ONLINE) {
                        appendHeader(headers, header, canvas, dark, theme, 0, panelHeight);
                        appendHeader(headers, header(dark, true), canvas, dark, theme, 1, panelHeight);
                    }
                }
            }
        }
        Files.write(new File(directory, "states-contact-sheet.svg").toPath(), sheet.append("</svg>").toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "headers-contact-sheet.svg").toPath(), headers.append("</svg>").toString().getBytes(StandardCharsets.UTF_8));
    }

    private LinearLayout header(boolean dark, boolean ninebot) {
        CastUi ui = new CastUi(context(dark)); LinearLayout header = ui.column();
        ui.castHeader(header, "OpenNinebot", new CastInstrumentView(context(dark)));
        header.addView(ui.hint(ninebot ? "非官方九号连接、投屏与协议研究 · 0.1.0"
                : "协议、编码与链路稳定性 · v" + BuildConfig.VERSION_NAME));
        header.measure(View.MeasureSpec.makeMeasureSpec(512, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        header.layout(0, 0, header.getMeasuredWidth(), header.getMeasuredHeight());
        return header;
    }

    private static StringBuilder contactSheet(int rows, int panelHeight) {
        int height = 32 + rows * panelHeight + (rows - 1) * 24;
        return new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1088\" height=\"")
                .append(height).append("\" viewBox=\"0 0 1088 ").append(height)
                .append("\"><rect width=\"1088\" height=\"").append(height).append("\" fill=\"#e6e9ee\"/>");
    }

    private static void appendHeader(StringBuilder sheet, LinearLayout header, RecordingCanvas canvas, boolean dark,
            int column, int row, int panelHeight) {
        View figure = header.getChildAt(1);
        float scale = Math.min(figure.getWidth() / CastInstrumentView.WIDTH, figure.getHeight() / CastInstrumentView.HEIGHT);
        sheet.append("<g transform=\"translate(").append(16 + column * 544).append(' ').append(16 + row * (panelHeight + 24))
                .append(")\"><rect width=\"512\" height=\"").append(panelHeight).append("\" rx=\"20\" fill=\"")
                .append(dark ? "#000000" : "#f4f4f4").append("\"/>");
        appendText(sheet, (TextView) header.getChildAt(0));
        sheet.append("<g transform=\"translate(").append(figure.getLeft() + (figure.getWidth() - CastInstrumentView.WIDTH * scale) / 2)
                .append(' ').append(figure.getTop() + (figure.getHeight() - CastInstrumentView.HEIGHT * scale) / 2)
                .append(") scale(").append(scale).append(")\">").append(canvas.body).append("</g>");
        appendText(sheet, (TextView) header.getChildAt(2)); sheet.append("</g>");
    }

    private static void appendText(StringBuilder svg, TextView label) {
        svg.append("<text x=\"").append(label.getLeft()).append("\" y=\"").append(label.getTop() + label.getBaseline())
                .append("\" font-family=\"Microsoft YaHei,sans-serif\" font-size=\"").append(label.getTextSize())
                .append("\" font-weight=\"").append(label.getTypeface() != null && label.getTypeface().isBold() ? "700" : "400")
                .append("\" fill=\"").append(RecordingCanvas.rgb(label.getCurrentTextColor())).append("\">")
                .append(label.getText()).append("</text>");
    }

    private static CastPalette palette() {
        return new CastPalette(new int[]{0xff2597e8, 0xff20cda5, 0xffd4e3ed}, new float[]{.49f, .34f, .17f});
    }

    /** Records the real Canvas primitives as SVG; works without native Android graphics on Windows. */
    private static final class RecordingCanvas extends Canvas implements AutoCloseable {
        final StringBuilder body = new StringBuilder();
        final List<float[]> dots = new ArrayList<>();
        final List<Integer> dotColors = new ArrayList<>();
        final boolean[] occupied = new boolean[CastInstrumentView.Field.COUNT];
        final int background;
        int positions, offGrid, solidPanels;
        RecordingCanvas(boolean dark) {
            background = dark ? 0xff000000 : 0xfff4f4f4;
            body.append("<rect width=\"512\" height=\"288\" fill=\"").append(rgb(background)).append("\"/>");
        }
        @Override public int save() { return 1; }
        @Override public void restoreToCount(int count) {}
        @Override public void translate(float dx, float dy) { assertEquals(0, dx, .001f); assertEquals(0, dy, .001f); }
        @Override public void scale(float x, float y) { assertEquals(1, x, .001f); assertEquals(1, y, .001f); }
        @Override public void drawRoundRect(float l, float t, float r, float b, float rx, float ry, Paint paint) {
            solidPanels++;
            body.append("<rect x=\"").append(l).append("\" y=\"").append(t).append("\" width=\"").append(r-l).append("\" height=\"").append(b-t)
                    .append("\" rx=\"").append(rx).append("\" fill=\"").append(rgb(paint.getColor())).append("\"/>");
        }
        @Override public void drawCircle(float cx, float cy, float radius, Paint paint) {
            float column = (cx - CastInstrumentView.Field.ORIGIN_X) / CastInstrumentView.Field.SPACING;
            float row = (cy - CastInstrumentView.Field.ORIGIN_Y) / CastInstrumentView.Field.SPACING;
            if (column < 0 || column >= CastInstrumentView.Field.COLUMNS || row < 0 || row >= CastInstrumentView.Field.ROWS
                    || Math.abs(column - Math.round(column)) > .001f || Math.abs(row - Math.round(row)) > .001f) offGrid++;
            else if (paint.getAlpha() > 0) {
                int index = Math.round(row) * CastInstrumentView.Field.COLUMNS + Math.round(column);
                if (!occupied[index]) { occupied[index] = true; positions++; }
            }
            dots.add(new float[]{cx,cy,radius}); dotColors.add(paint.getColor());
            body.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy).append("\" r=\"").append(radius)
                    .append("\" fill=\"").append(rgb(paint.getColor())).append("\" fill-opacity=\"").append(paint.getAlpha()/255f).append("\"/>");
        }
        int colorAt(float x, float y) {
            int color = background;
            for (int i=0;i<dots.size();i++) {
                float[] dot=dots.get(i);
                if (Math.hypot(x-dot[0],y-dot[1])>dot[2]) continue;
                int foreground=dotColors.get(i); float alpha=(foreground>>>24)/255f;
                int r=Math.round(((foreground>>16)&255)*alpha+((color>>16)&255)*(1-alpha));
                int g=Math.round(((foreground>>8)&255)*alpha+((color>>8)&255)*(1-alpha));
                int b=Math.round((foreground&255)*alpha+(color&255)*(1-alpha));
                color=0xff000000|r<<16|g<<8|b;
            }
            return color;
        }
        int particleColorNear(float x, float y) { return colorAt(nearestX(x), nearestY(y)); }
        float particleRadiusNear(float x, float y) {
            x = nearestX(x); y = nearestY(y);
            for (int i = dots.size() - 1; i >= 0; i--) {
                float[] dot = dots.get(i);
                if (dot[0] == x && dot[1] == y) return dot[2];
            }
            throw new AssertionError("Missing particle at " + x + ", " + y);
        }
        private static float nearestX(float x) {
            return CastInstrumentView.Field.ORIGIN_X + Math.round((x - CastInstrumentView.Field.ORIGIN_X)
                    / CastInstrumentView.Field.SPACING) * CastInstrumentView.Field.SPACING;
        }
        private static float nearestY(float y) {
            return CastInstrumentView.Field.ORIGIN_Y + Math.round((y - CastInstrumentView.Field.ORIGIN_Y)
                    / CastInstrumentView.Field.SPACING) * CastInstrumentView.Field.SPACING;
        }
        String svg() { return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"288\" viewBox=\"0 0 512 288\">"+body+"</svg>"; }
        static String rgb(int color) { return String.format(Locale.ROOT, "#%06x",color&0xffffff); }
        public void close() {}
    }
}
