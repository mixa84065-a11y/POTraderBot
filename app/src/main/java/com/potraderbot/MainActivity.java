package com.potraderbot;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private final TradeEngine engine = new TradeEngine();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView status, stats, log;
    private Button analyzeButton;
    private volatile boolean running = false;
    private Future<?> analysisTask;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(36, 40, 36, 36);
        page.setBackgroundColor(0xFF101827);
        scroll.addView(page);

        TextView title = text("GOD OF TRADING", 26, 0xFFFFFFFF, true);
        page.addView(title);
        page.addView(text("Binarium • manual analysis only", 14, 0xFF9FB0C5, false));

        status = text("STATUS: READY", 20, 0xFFFFC857, true);
        status.setPadding(0, 36, 0, 12);
        page.addView(status);

        TextView config = text("EUR/USD • 1-minute candles • 3-minute expiry\nEMA 9/21 + RSI 14 • max 10 manual records/day\nPause after 2 consecutive recorded losses", 15, 0xFFE5EDF7, false);
        config.setPadding(0, 12, 0, 24);
        page.addView(config);

        analyzeButton = button("ANALYZE BINARIUM DATA");
        analyzeButton.setOnClickListener(v -> runLiveAnalysis());
        page.addView(analyzeButton);

        Button stop = button("STOP ANALYSIS");
        stop.setOnClickListener(v -> {
            running = false;
            if (analysisTask != null) analysisTask.cancel(true);
            analyzeButton.setEnabled(true);
            status.setText("STATUS: STOPPED");
            status.setTextColor(0xFFFFC857);
            log.setText("Analysis stopped. No trade was placed.");
        });
        page.addView(stop);

        stats = text("Manual trades recorded: " + engine.getTradesToday() + " / 10", 17, 0xFFFFFFFF, true);
        stats.setPadding(0, 28, 0, 10);
        page.addView(stats);

        log = text("Press ANALYZE to load current one-minute candles and check the economic calendar.\nNo automatic trades are placed.", 14, 0xFFB8C6D9, false);
        page.addView(log);
        page.addView(text("\nIMPORTANT: Market data comes from Twelve Data and may differ from Binarium quotes. Signals are informational, not a guarantee. Trades must be checked and confirmed manually in Binarium.", 13, 0xFFFFC857, true));
        setContentView(scroll);
    }

    private void runLiveAnalysis() {
        if (running) return;
        if (BuildConfig.TWELVE_DATA_API_KEY.trim().isEmpty()
                || BuildConfig.API_SH_KEY.trim().isEmpty()) {
            status.setText("STATUS: API KEYS REQUIRED");
            status.setTextColor(0xFFFFC857);
            log.setText("Для анализа нужны оба ключа. Добавь их локально в local.properties: "
                    + "TWELVE_DATA_API_KEY=... и API_SH_KEY=... . "
                    + "Не публикуй этот файл в GitHub и не отправляй ключи в чат.");
            return;
        }

        running = true;
        analyzeButton.setEnabled(false);
        status.setText("STATUS: LOADING MARKET DATA...");
        status.setTextColor(0xFFFFC857);
        log.setText("Запрашиваю минутные свечи и экономический календарь. Сделки не открываются.");

        analysisTask = executor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                BinariumDataEngine dataEngine = new BinariumDataEngine(
                        BuildConfig.TWELVE_DATA_API_KEY, BuildConfig.API_SH_KEY);
                BinariumDataEngine.Result result = dataEngine.analyze(
                        "EUR/USD", calendarDate(-1), calendarDate(2), now);

                mainHandler.post(() -> {
                    if (!running || isFinishing()) return;
                    status.setText("SIGNAL: " + result.signal);
                    status.setTextColor(result.signal == MarketAnalyzer.Signal.WAIT
                            ? 0xFFFFC857 : 0xFF70D6A5);
                    log.setText(result.reason + "\nПолучено свечей: " + result.candleCount
                            + "\nИсточник котировок: Twelve Data"
                            + "\nЭкономический календарь: API.SH"
                            + "\nСделка не открывалась. Проверь котировку на Binarium вручную.");
                    running = false;
                    analyzeButton.setEnabled(true);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!running || isFinishing()) return;
                    status.setText("STATUS: DATA ERROR");
                    status.setTextColor(0xFFFF6B6B);
                    String message = error.getMessage();
                    log.setText("Не удалось выполнить анализ: "
                            + (message == null ? error.getClass().getSimpleName() : message)
                            + "\nПроверь API-ключи, интернет и доступность сервисов. Сигнал не сформирован.");
                    running = false;
                    analyzeButton.setEnabled(true);
                });
            }
        });
    }

    private String calendarDate(int dayOffset) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        calendar.add(Calendar.DAY_OF_YEAR, dayOffset);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(calendar.getTimeInMillis()));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    @Override protected void onDestroy() {
        running = false;
        executor.shutdownNow();
        super.onDestroy();
    }
}
