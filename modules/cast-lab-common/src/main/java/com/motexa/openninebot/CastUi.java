package com.motexa.openninebot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** Shared cards and controls, used by the independent casting tools. */
final class CastUi {
    private final Context context;
    final int foreground, background, secondary, cardColor, cellColor, accent;
    CastUi(Context context) {
        this.context = context;
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        foreground = dark ? 0xfff2f2f7 : 0xff1c1c1e;
        background = dark ? Color.BLACK : 0xfff4f4f4;
        secondary = dark ? 0xffa1a1a6 : 0xff6e6e73;
        cardColor = dark ? 0xff1c1c1e : 0xfffbfbfb;
        cellColor = dark ? 0xff2c2c2e : 0xfff0f0f1;
        accent = dark ? 0xff0a84ff : 0xff007aff;
    }
    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    LinearLayout column() {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    TextView text(String value, int size) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(foreground);
        view.setPadding(0, dp(7), 0, dp(7)); return view;
    }
    TextView hint(String value) { TextView view = text(value, 13); view.setTextColor(secondary); return view; }
    void castHeader(LinearLayout parent, String title, CastInstrumentView instrument) {
        parent.setPadding(dp(20), dp(40), dp(20), dp(24));
        TextView heading = text(title, 28); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); parent.addView(heading);
        LinearLayout.LayoutParams figure = new LinearLayout.LayoutParams(-1, -2);
        figure.topMargin = dp(28); figure.bottomMargin = dp(32); parent.addView(instrument, figure);
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    LinearLayout section(LinearLayout parent, String title) {
        TextView heading = hint(title); heading.setTextSize(14); heading.setPadding(0, dp(20), 0, dp(8)); parent.addView(heading);
        LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackground(rounded(cardColor, 24));
        parent.addView(card); return card;
    }
    Button button(String title, boolean primary, Runnable action) {
        Button view = new Button(context); view.setText(title); view.setAllCaps(false); view.setTextSize(14); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinWidth(0); view.setMinimumWidth(0); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48)); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setTextColor(primary ? Color.WHITE : accent); view.setBackgroundTintList(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33ffffff : 0x22007aff), rounded(primary ? accent : cellColor, 16), null));
        view.setElevation(0); view.setStateListAnimator(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48)); params.topMargin = dp(8); view.setLayoutParams(params);
        view.setOnClickListener(v -> { try { action.run(); } catch (RuntimeException error) { Toast.makeText(context, error.getMessage() == null ? "操作失败，请重试" : error.getMessage(), Toast.LENGTH_LONG).show(); } });
        return view;
    }
    void buttons(LinearLayout parent, Button left, Button right) {
        LinearLayout row = new LinearLayout(context);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(48), 1), b = new LinearLayout.LayoutParams(0, dp(48), 1);
        a.topMargin = b.topMargin = dp(8); a.rightMargin = b.leftMargin = dp(4); row.addView(left, a); row.addView(right, b); parent.addView(row);
    }
    void enabled(Button button, boolean enabled) { button.setEnabled(enabled); button.setAlpha(enabled ? 1f : 0.45f); }
    Spinner spinner(String[] labels) {
        Spinner view = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View reuse, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, reuse, parent); row.setTextColor(foreground); row.setTextSize(16); row.setGravity(Gravity.CENTER_VERTICAL);
                Drawable arrow = context.getDrawable(R.drawable.ic_chevron_down).mutate(); arrow.setTint(secondary); arrow.setBounds(0, 0, dp(18), dp(18));
                row.setCompoundDrawablesRelative(null, null, arrow, null); row.setCompoundDrawablePadding(dp(12)); row.setPadding(dp(14), 0, dp(14), 0); return row;
            }
            @Override public View getDropDownView(int position, View reuse, ViewGroup parent) {
                TextView row = (TextView) super.getDropDownView(position, reuse, parent); row.setTextColor(foreground); row.setBackgroundColor(cardColor);
                row.setMinHeight(dp(52)); row.setGravity(Gravity.CENTER_VERTICAL); return row;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); view.setAdapter(adapter);
        view.setBackground(rounded(cellColor, 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52)); params.topMargin = dp(8); view.setLayoutParams(params); return view;
    }
}
