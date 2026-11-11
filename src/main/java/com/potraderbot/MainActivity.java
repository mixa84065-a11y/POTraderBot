package com.potraderbot;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(8, 20, 39);
    private static final int PANEL = Color.rgb(14, 31, 55);
    private static final int GOLD = Color.rgb(224, 183, 91);
    private static final int TEXT = Color.rgb(231, 239, 249);
    private static final int MUTED = Color.rgb(139, 160, 187);
    private ChartView chart;
    private LinearLayout root;
    private TextView analysis;
    private boolean analysisMode = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        buildScreen();
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(10));
        root.setBackgroundColor(NAVY);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView brand = label("GOD OF TRADING", 20, GOLD, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(label("2.0  •  DEMO", 12, MUTED, true));
        root.addView(header);

        LinearLayout account = panelRow();
        account.addView(label("ВИРТУАЛЬНЫЙ БАЛАНС", 11, MUTED, true), new LinearLayout.LayoutParams(0,-2,1));
        account.addView(label("20 000 ₽", 18, TEXT, true));
        root.addView(account);

        chart = new ChartView(this);
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(-1, 0, 1);
        chartParams.topMargin = dp(12);
        root.addView(chart, chartParams);

        Button analysisButton = actionButton("АНАЛИЗ", GOLD, NAVY);
        analysisButton.setOnClickListener(v -> toggleAnalysis());
        LinearLayout.LayoutParams analysisParams = new LinearLayout.LayoutParams(-1, dp(46));
        analysisParams.topMargin = dp(10);
        root.addView(analysisButton, analysisParams);

        analysis = label("Ожидание подключения проверенного потока реальных котировок. Искусственные свечи не отображаются.", 13, MUTED, false);
        analysis.setPadding(dp(8), dp(8), dp(8), dp(4));
        root.addView(analysis);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button up = actionButton("ВВЕРХ", Color.rgb(29, 143, 111), Color.WHITE);
        Button down = actionButton("ВНИЗ", Color.rgb(190, 67, 78), Color.WHITE);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(54), 1);
        half.rightMargin = dp(5);
        actions.addView(up, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(54), 1);
        half2.leftMargin = dp(5);
        actions.addView(down, half2);
        up.setOnClickListener(v -> blockedUntilLiveFeed());
        down.setOnClickListener(v -> blockedUntilLiveFeed());
        root.addView(actions);

        TextView history = label("ИСТОРИЯ СДЕЛОК  •  Пока нет сделок", 12, MUTED, true);
        history.setPadding(dp(4), dp(12), dp(4), dp(4));
        root.addView(history);

        setContentView(root);
    }

    private void toggleAnalysis() {
        analysisMode = !analysisMode;
        analysis.setVisibility(analysisMode ? View.VISIBLE : View.GONE);
        chart.setExpanded(analysisMode);
        Toast.makeText(this, analysisMode ? "Полноэкранный режим графика и анализа" : "Главный экран", Toast.LENGTH_SHORT).show();
    }

    private void blockedUntilLiveFeed() {
        Toast.makeText(this, "Демо-сделка пока заблокирована: сначала подключаем и проверяем реальные котировки.", Toast.LENGTH_LONG).show();
    }

    private LinearLayout panelRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        root.addView(row, p);
        return row;
    }

    private Button actionButton(String title, int background, int foreground) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextColor(foreground);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(null, 1);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        return b;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, 1);
        return t;
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
}