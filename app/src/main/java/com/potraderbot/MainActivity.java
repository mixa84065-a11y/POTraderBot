package com.potraderbot;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF25282E;
    private static final int PANEL = 0xFF333840;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MarketDataClient dataClient = new MarketDataClient();
    private final MarketAnalyzer analyzer = new MarketAnalyzer();

    private EditText symbolInput;
    private EditText apiKeyInput;
    private Spinner platformInput;
    private TextView status;
    private TextView details;
    private Button analyzeButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32, 36, 32, 32);
        page.setBackgroundColor(BG);
        scroll.addView(page);

        page.addView(label("PO TRADER BOT", 26, 0xFFFFFFFF, true));
        page.addView(label("Рыночный анализ • 1-минутные свечи • тестовая экспирация 3 минуты", 14, 0xFFCDD2DA, false));

        page.addView(label("Торговая платформа", 14, 0xFFFFFFFF, true));
        platformInput = new Spinner(this);
        String[] platforms = {"Binarium", "Pocket Option"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, platforms);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platformInput.setAdapter(adapter);
        platformInput.setSelection(0);
        platformInput.setBackgroundTintList(ColorStateList.valueOf(0xFFB8C0CC));
        page.addView(platformInput);

        page.addView(label("Валютная пара", 14, 0xFFFFFFFF, true));
        symbolInput = editField("EUR/USD");
        symbolInput.setText("EUR/USD");
        page.addView(symbolInput);

        page.addView(label("API-ключ Twelve Data", 14, 0xFFFFFFFF, true));
        apiKeyInput = editField("Вставьте личный API-ключ");
        apiKeyInput.setInputType(0x00000081);
        page.addView(apiKeyInput);

        status = label("СТАТУС: ОЖИДАНИЕ", 20, 0xFFFFC857, true);
        status.setPadding(0, 24, 0, 12);
        page.addView(status);

        analyzeButton = new Button(this);
        analyzeButton.setText("ЗАГРУЗИТЬ КОТИРОВКИ И АНАЛИЗИРОВАТЬ");
        analyzeButton.setAllCaps(false);
        analyzeButton.setTextColor(0xFFFFFFFF);
        analyzeButton.setBackgroundTintList(ColorStateList.valueOf(0xFF465568));
        analyzeButton.setOnClickListener(v -> loadAndAnalyze());
        page.addView(analyzeButton);

        details = label("Нажмите кнопку, чтобы получить последние закрытые минутные свечи.\n\nAPI-ключ вводится только в приложении и не сохраняется.", 15, 0xFFE5E8ED, false);
        details.setPadding(0, 20, 0, 20);
        page.addView(details);

        page.addView(label("Правила безопасности", 17, 0xFFFFFFFF, true));
        page.addView(label("• Выбор Binarium меняет обозначение платформы в интерфейсе.\n"
                + "• Котировки загружаются из Twelve Data, а не из аккаунта Binarium.\n"
                + "• Приложение не подключается к аккаунту и не размещает реальные ордера.\n"
                + "• Сигнал не гарантирует результат; сначала проверяйте стратегию на демо.", 14, 0xFFFFC857, false));
        setContentView(scroll);
    }

    private EditText editField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0xFFB8C0CC);
        field.setBackgroundTintList(ColorStateList.valueOf(0xFF9AA4B2));
        return field;
    }

    private void loadAndAnalyze() {
        final String symbol = symbolInput.getText().toString().trim();
        final String key = apiKeyInput.getText().toString().trim();
        final String platform = platformInput.getSelectedItem().toString();
        if (key.isEmpty()) {
            status.setText("СТАТУС: НУЖЕН API-КЛЮЧ");
            status.setTextColor(0xFFFFC857);
            return;
        }
        analyzeButton.setEnabled(false);
        status.setText("СТАТУС: ЗАГРУЗКА КОТИРОВОК…");
        status.setTextColor(0xFF80BFFF);

        executor.execute(() -> {
            try {
                List<Candle> candles = dataClient.fetchOneMinuteCandles(symbol, key);
                MarketAnalyzer.Analysis result = analyzer.analyze(candles);
                Candle last = candles.get(candles.size() - 1);
                runOnUiThread(() -> {
                    analyzeButton.setEnabled(true);
                    status.setText("СИГНАЛ: " + result.signal);
                    status.setTextColor(result.signal == MarketAnalyzer.Signal.WAIT ? 0xFFFFC857 : 0xFF70D6A5);
                    details.setText(String.format(Locale.US,
                            "Платформа в интерфейсе: %s\nИсточник котировок: Twelve Data\nПара: %s\nЗакрытых свечей: %d\nПоследняя свеча UTC: %tF %tR\nЦена закрытия: %.5f\n\nEMA 9: %.5f\nEMA 21: %.5f\nRSI 14: %.2f\n\n%s\n\nЭто аналитический сигнал, а не команда на сделку.",
                            platform, symbol.toUpperCase(Locale.US), candles.size(), last.time, last.time,
                            last.close, result.ema9, result.ema21, result.rsi14, result.explanation));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    analyzeButton.setEnabled(true);
                    status.setText("СТАТУС: ОШИБКА ЗАГРУЗКИ");
                    status.setTextColor(0xFFFF7B72);
                    details.setText(e.getMessage() == null ? "Не удалось получить котировки." : e.getMessage());
                });
            }
        });
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
