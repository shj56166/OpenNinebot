package com.motexa.openninebot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.function.Consumer;

/** Fixed XY dots: the shell never moves, only its light changes. */
final class CastInstrumentView extends View {
    static final float WIDTH = 512, HEIGHT = 288;
    static final float SCREEN_LEFT = 104, SCREEN_TOP = 66, SCREEN_RIGHT = 408, SCREEN_BOTTOM = 230;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect visibleRect = new Rect();
    private final Field field = new Field();
    private final int[] colors = new int[3], fromColors = new int[3], targetColors = new int[3];
    private final float[] weights = new float[3], fromWeights = new float[3], targetWeights = new float[3];
    private final Consumer<Boolean> visibility;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::syncVisibility;
    private CastVisualFeed.State state = CastVisualFeed.State.OFFLINE;
    private CastPalette palette = CastPalette.EMPTY;
    private long transitionAt, connectingAt;
    private boolean foreground, ticking, reportedVisible, motion;
    private final Runnable frame = () -> { ticking = false; invalidate(); };

    public CastInstrumentView(Context context) { this(context, null); }

    CastInstrumentView(Context context, Consumer<Boolean> visibility) {
        super(context);
        this.visibility = visibility;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("投屏仪表 · 未连接");
    }

    void foreground(boolean value) { foreground = value; syncVisibility(); }

    void update(CastVisualFeed.State next, CastPalette nextPalette) {
        long now = SystemClock.elapsedRealtime();
        if (next != state) {
            state = next;
            if (next == CastVisualFeed.State.CONNECTING) connectingAt = now;
            setContentDescription(next == CastVisualFeed.State.OFFLINE ? "投屏仪表 · 未连接"
                    : next == CastVisualFeed.State.CONNECTING ? "投屏仪表 · 连接中" : "投屏仪表 · 投屏中，画面需在车辆仪表确认");
        }
        if (next != CastVisualFeed.State.ONLINE) nextPalette = CastPalette.EMPTY;
        if (palette != nextPalette) retarget(nextPalette, now);
        invalidate();
    }

    private void retarget(CastPalette next, long now) {
        blend(now, motion);
        System.arraycopy(colors, 0, fromColors, 0, 3);
        System.arraycopy(weights, 0, fromWeights, 0, 3);
        boolean[] used = new boolean[next.size()];
        for (int slot = 0; slot < 3; slot++) {
            int closest = -1;
            for (int color = 0; color < next.size(); color++)
                if (!used[color] && (closest < 0 || CastPalette.distance(colors[slot], next.color(color))
                        < CastPalette.distance(colors[slot], next.color(closest)))) closest = color;
            targetColors[slot] = closest < 0 ? colors[slot] : next.color(closest);
            targetWeights[slot] = closest < 0 ? 0 : next.weight(closest);
            if (closest >= 0) used[closest] = true;
        }
        palette = next;
        transitionAt = now;
    }

    private void blend(long now, boolean animate) {
        float progress = animate ? Math.max(0, Math.min(1, (now - transitionAt) / 600f)) : 1;
        progress = progress * progress * (3 - 2 * progress);
        for (int i = 0; i < 3; i++) {
            colors[i] = mix(fromColors[i], targetColors[i], progress);
            weights[i] = fromWeights[i] + (targetWeights[i] - fromWeights[i]) * progress;
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(Math.round(360 * getResources().getDisplayMetrics().density), widthSpec);
        setMeasuredDimension(width, resolveSize(Math.round(width * HEIGHT / WIDTH), heightSpec));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); getViewTreeObserver().addOnScrollChangedListener(scrollListener); syncVisibility();
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        removeCallbacks(frame); ticking = false; reportVisible(false); super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changed, int value) { super.onVisibilityChanged(changed, value); syncVisibility(); }
    @Override protected void onWindowVisibilityChanged(int value) { super.onWindowVisibilityChanged(value); syncVisibility(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); syncVisibility(); }

    private void syncVisibility() {
        // View's constructor can invoke visibility callbacks before our fields are initialized.
        if (visibleRect == null) return;
        motion = Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled()
                : Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1) != 0;
        boolean visible = foreground && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty();
        reportVisible(visible);
        if (!visible) { removeCallbacks(frame); ticking = false; }
        else invalidate();
    }

    private void reportVisible(boolean value) {
        if (reportedVisible == value) return;
        reportedVisible = value;
        if (visibility != null) visibility.accept(value);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawAt(canvas, SystemClock.elapsedRealtime(), motion);
        if (reportedVisible && motion && state != CastVisualFeed.State.OFFLINE && !ticking) {
            ticking = true; postDelayed(frame, 34);
        }
    }

    /** Also used to render deterministic native Canvas acceptance images. */
    void drawAt(Canvas canvas, long now, boolean animate) {
        blend(now, animate);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        float scale = Math.min(getWidth() / WIDTH, getHeight() / HEIGHT);
        int save = canvas.save();
        canvas.translate((getWidth() - WIDTH * scale) / 2, (getHeight() - HEIGHT * scale) / 2);
        canvas.scale(scale, scale);
        float time = animate ? (now % 600000) / 1000f : 0;
        float phase = animate ? ((now - connectingAt) % 1400) / 1400f : .5f;
        float sweep = SCREEN_LEFT + (SCREEN_RIGHT - SCREEN_LEFT) * (-.25f + (1 - (float) Math.cos(phase * Math.PI)) * .75f);
        for (int index = 0; index < Field.COUNT; index++) {
            float x = field.x(index), y = field.y(index), shell = field.shell[index];
            float alpha = .16f + shell * .48f, radius = .68f + shell * 1.55f, glow = 0;
            int color = dark ? 0xff9caec3 : 0xff6f829b;
            float screen = field.screen[index];
            if (screen > 0 && state != CastVisualFeed.State.OFFLINE) {
                float signal;
                int signalColor;
                if (state == CastVisualFeed.State.CONNECTING) {
                    float band = (float) Math.exp(-.5 * Math.pow((x - sweep) / 24, 2));
                    signal = screen * band; signalColor = 0xffc0c7d0;
                } else {
                    float r = 0, g = 0, b = 0, total = 0;
                    for (int slot = 0; slot < 3; slot++) {
                        float center = SCREEN_LEFT + (SCREEN_RIGHT - SCREEN_LEFT) * (.18f + slot * .32f);
                        float spread = .12f + .88f * (float) Math.exp(-Math.pow((x - center) / 92, 2));
                        float light = .86f + .14f * (float) Math.sin(x * .026f + y * .019f - time * .65f + slot * 2.1f);
                        float amount = weights[slot] * spread * light;
                        r += Color.red(colors[slot]) * amount; g += Color.green(colors[slot]) * amount;
                        b += Color.blue(colors[slot]) * amount; total += amount;
                    }
                    signal = screen * Math.min(1, total * 1.5f);
                    signalColor = total < .001f ? color : Color.rgb(Math.min(255, Math.round(r / total)),
                            Math.min(255, Math.round(g / total)), Math.min(255, Math.round(b / total)));
                }
                color = mix(color, signalColor, Math.min(1, signal * 2));
                alpha = Math.max(alpha, .16f + signal * .66f);
                radius = Math.max(radius, .68f + signal * 1.8f); glow = signal * .08f;
            }
            float lamp = field.lamp[index];
            color = mix(color, 0xff258eff, lamp);
            alpha = Math.max(alpha, lamp * (state == CastVisualFeed.State.OFFLINE ? .62f : .92f));
            radius = Math.max(radius, .68f + lamp * 1.87f);
            glow = Math.max(glow, lamp * (state == CastVisualFeed.State.OFFLINE ? .09f : .17f));
            alpha *= field.fade[index]; glow *= field.fade[index];
            radius *= Field.PARTICLE_SCALE * (.72f + .28f * field.fade[index]);
            if (glow > .004f) {
                paint.setColor(color); paint.setAlpha(Math.round(255 * glow * .3f)); canvas.drawCircle(x, y, radius * 3, paint);
                paint.setAlpha(Math.round(255 * glow)); canvas.drawCircle(x, y, radius * 1.9f, paint);
            }
            paint.setColor(color); paint.setAlpha(Math.max(1, Math.round(255 * alpha))); canvas.drawCircle(x, y, radius, paint);
        }
        paint.setAlpha(255); canvas.restoreToCount(save);
    }

    private static int mix(int first, int second, float amount) {
        return Color.rgb(Math.round(Color.red(first) + (Color.red(second) - Color.red(first)) * amount),
                Math.round(Color.green(first) + (Color.green(second) - Color.green(first)) * amount),
                Math.round(Color.blue(first) + (Color.blue(second) - Color.blue(first)) * amount));
    }

    static final class Field {
        static final int COLUMNS = 42, ROWS = 24, COUNT = COLUMNS * ROWS;
        static final float SPACING = 12, PARTICLE_SCALE = 1.8f;
        static final float ORIGIN_X = (WIDTH - (COLUMNS - 1) * SPACING) / 2;
        static final float ORIGIN_Y = (HEIGHT - (ROWS - 1) * SPACING) / 2;
        final float[] shell = new float[COUNT], screen = new float[COUNT], lamp = new float[COUNT], fade = new float[COUNT];
        private static final float[] OUTLINE = {68,36, 444,36, 478,54, 498,88, 486,221, 451,256, 61,256, 26,221, 14,88, 34,54};

        Field() {
            for (int i = 0; i < COUNT; i++) {
                float x = x(i), y = y(i);
                screen[i] = smooth((12 - roundedDistance(x, y, 256, 148, 152, 82, 12)) / 28);
                float lampDistance = Math.max(0, Math.min(roundedDistance(x, y, 76, 151, 6, 66, 6),
                        roundedDistance(x, y, 436, 151, 6, 66, 6)));
                lamp[i] = (float) Math.exp(-.5 * lampDistance * lampDistance / 36);
                float distance = outlineDistance(x, y);
                float inner = roundedDistance(x, y, 256, 146, 214, 96, 27);
                float rim = (float) Math.exp(-.5 * distance * distance / 196);
                float bezel = (float) Math.exp(-.5 * inner * inner / 100);
                shell[i] = Math.min(1, rim * .74f + bezel * .35f) * (1 - screen[i] * .94f);
                fade[i] = .04f + .96f * smooth(Math.min(x, WIDTH - x) / 96) * smooth(Math.min(y, HEIGHT - y) / 72);
            }
        }

        float x(int index) { return ORIGIN_X + index % COLUMNS * SPACING; }
        float y(int index) { return ORIGIN_Y + index / COLUMNS * SPACING; }

        private static float smooth(float value) {
            float t = Math.max(0, Math.min(1, value));
            return t * t * (3 - 2 * t);
        }

        private static float roundedDistance(float x, float y, float cx, float cy, float halfWidth, float halfHeight, float radius) {
            float dx = Math.abs(x - cx) - halfWidth + radius, dy = Math.abs(y - cy) - halfHeight + radius;
            return (float) Math.hypot(Math.max(0, dx), Math.max(0, dy)) + Math.min(Math.max(dx, dy), 0) - radius;
        }

        private static float outlineDistance(float x, float y) {
            float distance = Float.MAX_VALUE; boolean inside = false;
            for (int i = 0, previous = OUTLINE.length - 2; i < OUTLINE.length; previous = i, i += 2) {
                float ax = OUTLINE[previous], ay = OUTLINE[previous + 1], bx = OUTLINE[i], by = OUTLINE[i + 1];
                float dx = bx - ax, dy = by - ay;
                float position = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
                distance = Math.min(distance, (float) Math.hypot(x - ax - position * dx, y - ay - position * dy));
                if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside;
            }
            return inside ? -distance : distance;
        }
    }
}
