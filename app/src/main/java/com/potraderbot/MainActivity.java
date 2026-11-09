package com.potraderbot;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MarketDataClient dataClient = new MarketDataClient();
    private final MarketAnalyzer analyzer = new MarketAnalyzer();

    private EditText symbolInput;
    private EditText apiKeyInput;
    private TextView status;
    private TextView details;
    private Button analyzeButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32, 36, 32, 32);
        page.setBackgroundColor(0xFF101827);
        scroll.addView(page);

        TextView title = label("PO TRADER BOT", 26, 0xFFFFFFFF, true);
        page.addView(title);
        page.addView(label("Рыночный анализ • 1-минутные свечи • экспирация для теста 3 минуты", 14, 0xFFB8C6D9, false));

        page.addView(label("Валютная пара", 14, 0xFFE5EDF7, true));
        symbolInput = new EditText(this);
        symbolInput.setSingleLine(true);
        symbolInput.setText("EUR/USD");
        symbolInput.setHint("EUR/USD");
        page.addView(symbolInput);

        page.addView(label("API-ключ Twelve Data", 14, 0xFFE5EDF7, true));
        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setHint("Вставьте личный API-ключ");
        apiKeyInput.setInputType(0x00000081);
        page.addView(apiKeyInput);

        status = label("СТАТУС: ОЖИДАНИЕ", 20, 0xFFFFC857, true);
        status.setPadding(0, 24, 0, 12);
        page.addView(status);

        analyzeButton = new Button(this);
        analyzeButton.setText("ЗАГРУЗИТЬ КОТИРОВКИ И АНАЛИЗИРОВАТЬ");
        analyzeButton.setAllCaps(false);
        analyzeButton.setOnClickListener(v -> loadAndAnalyze());
        page.addView(analyzeButton);

        details = label("Нажмите кнопку, чтобы получить последние закрытые минутные свечи.\n\nAPI-ключ вводится только в приложении и не сохраняется.", 15, 0xFFE5EDF7, false);
        details.setPadding(0, 20, 0, 20);
        page.addView(details);

        page.addView(label("Правила безопасности", 17, 0xFFFFFFFF, true));
        page.addView(label("• Только анализ котировок и сигналы CALL / PUT / WAIT.\n"
                + "• Приложение не подключается к аккаунту Pocket Option.\n"
                + "• Не размещает реальные ордера и не хранит пароль брокера.\n"
                + "• Сигнал не гарантирует результат; сначала проверяйте стратегию на демо.", 14, 0xFFFFC857, false));
        setContentView(scroll);
    }

    private void loadAndAnalyze() {
        final String symbol = symbolInput.getText().toString().trim();
        final String key = apiKeyInput.getText().toString().trim();
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
                            "Пара: %s\nЗакрытых свечей: %d\nПоследняя свеча UTC: %tF %tR\nЦена закрытия: %.5f\n\nEMA 9: %.5f\nEMA 21: %.5f\nRSI 14: %.2f\n\n%s\n\nЭто аналитический сигнал, а не команда на сделку.",
                            symbol.toUpperCase(Locale.US), candles.size(), last.time, last.time,
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
