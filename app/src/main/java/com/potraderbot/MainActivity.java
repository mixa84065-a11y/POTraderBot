package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = 0xFFF1F3F6;
    private static final int AUTO_REFRESH_MS = 10_000;
    private static final long UPDATE_CHECK_MS = 6L * 60L * 60L * 1000L;
    private static final String RELEASES_API = "https://api.github.com/repos/mixa84065-a11y/POTraderBot/releases/latest";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MarketDataClient dataClient = new MarketDataClient();
    private final MarketAnalyzer analyzer = new MarketAnalyzer();

    private EditText symbolInput;
    private EditText apiKeyInput;
    private Spinner platformInput;
    private Spinner modeInput;
    private TextView status;
    private TextView details;
    private Button analyzeButton;
    private Button autoButton;
    private Button updateButton;
    private Button platformButton;
    private Button paperButton;
    private PaperTradingEngine paperTrading;
    private boolean paperAutoEnabled = false;
    private boolean autoRefreshEnabled = false;
    private boolean requestInProgress = false;
    private String updateUrl;
    private String updateVersion = "";
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (!autoRefreshEnabled || isFinishing()) return;
            if (!requestInProgress) loadAndAnalyze(true);
            handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };
    private final Runnable updateCheckRunnable = new Runnable() {
        @Override public void run() {
            checkForUpdate();
            handler.postDelayed(this, UPDATE_CHECK_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
        checkForUpdate();
        handler.postDelayed(updateCheckRunnable, UPDATE_CHECK_MS);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32, 28, 32, 24);
        page.setBackgroundColor(BG);
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        page.addView(label("DeD_GoD", 26, 0xFF1D2939, true));
        page.addView(label("Рыночный анализ • 1-минутные свечи • тестовая экспирация 3 минуты", 14, 0xFF667085, false));

        SharedPreferences preferences = getSharedPreferences("po_trader_settings", MODE_PRIVATE);
        paperTrading = new PaperTradingEngine(preferences);
        page.addView(label("Валютная пара", 14, 0xFFFFFFFF, true));
        symbolInput = editField("EUR/USD");
        symbolInput.setText("EUR/USD");
        page.addView(symbolInput);

        page.addView(label("API-ключ Twelve Data", 14, 0xFFFFFFFF, true));
        apiKeyInput = editField("Вставьте личный API-ключ");
        apiKeyInput.setInputType(0x00000081);
        page.addView(apiKeyInput);

        page.addView(label("Режим торговли", 14, 0xFF344054, true));
        modeInput = new Spinner(this);
        String[] modes = {"Демо — виртуальные сделки", "Реальный — только сигналы"};
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        int savedMode = preferences.getInt("trading_mode", 0);
        modeInput.setAdapter(modeAdapter);
        modeInput.setSelection(Math.max(0, Math.min(savedMode, modes.length - 1)));
        modeInput.setBackgroundTintList(ColorStateList.valueOf(0xFF64748B));
        page.addView(modeInput);
        modeInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("trading_mode", position).apply();
                if (position == 1 && paperAutoEnabled) {
                    paperAutoEnabled = false;
                    autoRefreshEnabled = false;
                    handler.removeCallbacks(refreshRunnable);
                    paperButton.setText("АВТОСИМУЛЯЦИЯ ДОСТУПНА ТОЛЬКО В ДЕМО");
                    autoButton.setText("ВКЛЮЧИТЬ АВТООБНОВЛЕНИЕ (10 СЕК.)");
                    status.setText("РЕАЛЬНЫЙ РЕЖИМ: ТОЛЬКО СИГНАЛЫ");
                    status.setTextColor(0xFFFFC857);
                } else if (paperButton != null) {
                    paperButton.setText(position == 0 ? "ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ СДЕЛОК" : "АВТОСИМУЛЯЦИЯ ТОЛЬКО В ДЕМО");
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        status = label("СТАТУС: ОЖИДАНИЕ", 20, 0xFFFFC857, true);
        status.setPadding(0, 24, 0, 12);
        page.addView(status);

        analyzeButton = makeButton("ЗАГРУЗИТЬ КОТИРОВКИ И АНАЛИЗИРОВАТЬ");
        analyzeButton.setOnClickListener(v -> loadAndAnalyze(false));
        page.addView(analyzeButton);

        autoButton = makeButton("ВКЛЮЧИТЬ АВТООБНОВЛЕНИЕ (10 СЕК.)");
        autoButton.setOnClickListener(v -> toggleAutoRefresh());
        page.addView(autoButton);

        paperButton = makeButton("ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ СДЕЛОК");
        paperButton.setOnClickListener(v -> togglePaperAuto());
        page.addView(paperButton);

        details = label("Нажмите кнопку, чтобы получить последние закрытые минутные свечи.\n\nAPI-ключ вводится только в приложении и не сохраняется.", 15, 0xFF344054, false);
        details.setPadding(0, 20, 0, 20);
        page.addView(details);

        page.addView(label("Обновление приложения", 17, 0xFFFFFFFF, true));
        updateButton = makeButton("ПРОВЕРИТЬ ОБНОВЛЕНИЕ");
        updateButton.setOnClickListener(v -> checkForUpdate());
        page.addView(updateButton);

        page.addView(label("Правила безопасности", 17, 0xFFFFFFFF, true));
        page.addView(label("• Автообновление запрашивает новые котировки и пересчитывает EMA/RSI каждые 10 секунд, пока приложение открыто.\n"
                + "• Выбор площадки меняет адрес кнопки «Открыть площадку»; вход выполняется на официальном сайте.\n"
                + "• Котировки во всех режимах загружаются из Twelve Data, а не из терминала брокера.\n"
                + "• Автоматическое исполнение ордеров не реализовано: публичная официальная API-документация Binarium для выставления сделок не обнаружена. Не вводите пароль от брокера в приложение.\n"
                + "• Сигнал не гарантирует результат; сначала проверяйте стратегию на демо.\n"
                + "• Новая версия скачивается только из GitHub Releases; установку Android нужно подтвердить вручную.", 14, 0xFF8A5A00, false));
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(16, 8, 16, 8);
        bottomBar.setBackgroundColor(0xFFE1E6ED);

        TextView platformLabel = label("Площадка", 13, 0xFF263445, true);
        bottomBar.addView(platformLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));

        platformInput = new Spinner(this);
        String[] platforms = {"Binarium", "Pocket Option", "Универсальный"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, platforms);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platformInput.setAdapter(adapter);
        int savedPlatform = preferences.getInt("platform_index", 0);
        platformInput.setSelection(Math.max(0, Math.min(savedPlatform, platforms.length - 1)));
        platformInput.setBackgroundTintList(ColorStateList.valueOf(0xFF64748B));
        bottomBar.addView(platformInput, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.7f));

        platformButton = makeButton("ОТКРЫТЬ");
        platformButton.setTextSize(12);
        platformButton.setOnClickListener(v -> openSelectedPlatform());
        bottomBar.addView(platformButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        platformInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("platform_index", position).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private String signalLabel(MarketAnalyzer.Signal signal) {
        switch (signal) {
            case CALL: return "ВВЕРХ";
            case PUT: return "ВНИЗ";
            default: return "ЖДАТЬ";
        }
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setBackgroundTintList(ColorStateList.valueOf(0xFF315A82));
        return button;
    }

    private EditText editField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextColor(0xFF1D2939);
        field.setHintTextColor(0xFF98A2B3);
        field.setBackgroundTintList(ColorStateList.valueOf(0xFF98A2B3));
        return field;
    }

    private void openSelectedPlatform() {
        int selected = platformInput.getSelectedItemPosition();
        if (selected == 2) {
            new AlertDialog.Builder(this)
                    .setTitle("Выберите торговую площадку")
                    .setItems(new String[]{"Binarium", "Pocket Option"}, (dialog, which) ->
                            openPlatformUrl(which == 0
                                    ? "https://binarium.com/"
                                    : "https://pocketoption.com/"))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        openPlatformUrl(selected == 0 ? "https://binarium.com/" : "https://pocketoption.com/");
    }

    private void openPlatformUrl(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (Exception e) {
            status.setText("НЕ УДАЛОСЬ ОТКРЫТЬ ПЛОЩАДКУ");
            status.setTextColor(0xFFFF7B72);
        }
    }

    private void togglePaperAuto() {
        if (modeInput != null && modeInput.getSelectedItemPosition() == 1) {
            status.setText("РЕАЛЬНЫЙ РЕЖИМ: АВТООРДЕРА НЕ ПОДДЕРЖИВАЮТСЯ");
            status.setTextColor(0xFFFFC857);
            return;
        }
        paperAutoEnabled = !paperAutoEnabled;
        if (paperAutoEnabled) {
            paperButton.setText("ОСТАНОВИТЬ АВТОСИМУЛЯЦИЮ");
            autoRefreshEnabled = true;
            autoButton.setText("ВЫКЛЮЧИТЬ АВТООБНОВЛЕНИЕ");
            status.setText("АВТОСИМУЛЯЦИЯ: ВКЛЮЧЕНА");
            status.setTextColor(0xFF80BFFF);
            loadAndAnalyze(true);
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, AUTO_REFRESH_MS);
        } else {
            autoRefreshEnabled = false;
            handler.removeCallbacks(refreshRunnable);
            paperButton.setText("ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ СДЕЛОК");
            autoButton.setText("ВКЛЮЧИТЬ АВТООБНОВЛЕНИЕ (10 СЕК.)");
            status.setText("АВТОСИМУЛЯЦИЯ: ОСТАНОВЛЕНА");
            status.setTextColor(0xFFFFC857);
        }
    }

    private void toggleAutoRefresh() {
        autoRefreshEnabled = !autoRefreshEnabled;
        if (autoRefreshEnabled) {
            autoButton.setText("ВЫКЛЮЧИТЬ АВТООБНОВЛЕНИЕ");
            status.setText("АВТООБНОВЛЕНИЕ: ВКЛЮЧЕНО");
            status.setTextColor(0xFF80BFFF);
            loadAndAnalyze(true);
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, AUTO_REFRESH_MS);
        } else {
            handler.removeCallbacks(refreshRunnable);
            autoButton.setText("ВКЛЮЧИТЬ АВТООБНОВЛЕНИЕ (10 СЕК.)");
            status.setText("АВТООБНОВЛЕНИЕ: ВЫКЛЮЧЕНО");
            status.setTextColor(0xFFFFC857);
        }
    }

    private void loadAndAnalyze(boolean automatic) {
        final String symbol = symbolInput.getText().toString().trim();
        final String key = apiKeyInput.getText().toString().trim();
        final String platform = platformInput.getSelectedItem().toString();
        final String mode = modeInput.getSelectedItem().toString();
        if (key.isEmpty()) {
            status.setText("СТАТУС: НУЖЕН API-КЛЮЧ");
            status.setTextColor(0xFFFFC857);
            if (automatic) {
                autoRefreshEnabled = false;
                handler.removeCallbacks(refreshRunnable);
                autoButton.setText("ВКЛЮЧИТЬ АВТООБНОВЛЕНИЕ (10 СЕК.)");
            }
            return;
        }
        if (requestInProgress) return;
        requestInProgress = true;
        analyzeButton.setEnabled(false);
        status.setText(automatic ? "АВТООБНОВЛЕНИЕ: ПОЛУЧЕНИЕ КОТИРОВОК…" : "СТАТУС: ЗАГРУЗКА КОТИРОВОК…");
        status.setTextColor(0xFF80BFFF);

        executor.execute(() -> {
            try {
                List<Candle> candles = dataClient.fetchOneMinuteCandles(symbol, key);
                MarketAnalyzer.Analysis result = analyzer.analyze(candles);
                Candle last = candles.get(candles.size() - 1);
                runOnUiThread(() -> {
                    requestInProgress = false;
                    analyzeButton.setEnabled(true);
                    status.setText((autoRefreshEnabled ? "АВТО • " : "") + "СИГНАЛ: " + signalLabel(result.signal));
                    status.setTextColor(result.signal == MarketAnalyzer.Signal.WAIT ? 0xFFFFC857 : 0xFF70D6A5);
                    String analysisText = String.format(Locale.US,
                            "Режим: %s\nПлощадка в интерфейсе: %s\nИсточник котировок: Twelve Data\nПара: %s\nЗакрытых свечей: %d\nПоследняя свеча UTC: %tF %tR\nЦена закрытия: %.5f\n\nEMA 9: %.5f\nEMA 21: %.5f\nRSI 14: %.2f\n\n%s\n\nЭто аналитический сигнал, а не команда на сделку.",
                            platform, symbol.toUpperCase(Locale.US), candles.size(), last.time, last.time,
                            last.close, result.ema9, result.ema21, result.rsi14, result.explanation);
                    if (paperAutoEnabled) paperTrading.process(candles, result.signal);
                    details.setText(analysisText + (paperAutoEnabled ? "\n\n" + paperTrading.getSummary() : ""));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    requestInProgress = false;
                    analyzeButton.setEnabled(true);
                    status.setText(autoRefreshEnabled ? "АВТООБНОВЛЕНИЕ: ОШИБКА" : "СТАТУС: ОШИБКА ЗАГРУЗКИ");
                    status.setTextColor(0xFFFF7B72);
                    details.setText(e.getMessage() == null ? "Не удалось получить котировки." : e.getMessage());
                });
            }
        });
    }

    private void checkForUpdate() {
        updateButton.setEnabled(false);
        updateButton.setText("ПРОВЕРКА ВЕРСИИ…");
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "PO-Trader-Bot-Android");
                int code = connection.getResponseCode();
                if (code == 404) throw new IllegalStateException("Пока нет опубликованного GitHub Release с APK.");
                if (code < 200 || code >= 300) throw new IllegalStateException("GitHub вернул HTTP " + code);
                JSONObject release = new JSONObject(readAll(connection.getInputStream()));
                String tag = release.optString("tag_name", "");
                String pageUrl = release.optString("html_url", "");
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset != null && asset.optString("name").toLowerCase(Locale.US).endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            if (apkUrl != null && !apkUrl.isEmpty()) break;
                        }
                    }
                }
                final String latestTag = tag;
                final String download = apkUrl == null || apkUrl.isEmpty() ? pageUrl : apkUrl;
                final boolean newer = compareVersions(tag, BuildConfig.VERSION_NAME) > 0;
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    if (newer && download != null && !download.isEmpty()) {
                        updateUrl = download;
                        updateVersion = latestTag;
                        updateButton.setText("СКАЧАТЬ ОБНОВЛЕНИЕ " + latestTag);
                        updateButton.setOnClickListener(v -> openUpdate());
                    } else if (newer) {
                        updateButton.setText("ОТКРЫТЬ СТРАНИЦУ РЕЛИЗА");
                        updateUrl = pageUrl;
                        updateButton.setOnClickListener(v -> openUpdate());
                    } else {
                        updateButton.setText("ПРИЛОЖЕНИЕ АКТУАЛЬНО • " + BuildConfig.VERSION_NAME);
                        updateUrl = null;
                    }
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "Не удалось проверить обновления." : e.getMessage();
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    updateButton.setText("ПОВТОРИТЬ ПРОВЕРКУ ОБНОВЛЕНИЯ");
                    details.setText(details.getText() + "\n\nПроверка обновлений: " + message);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void openUpdate() {
        if (updateUrl == null || updateUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)));
        } catch (Exception e) {
            status.setText("НЕ УДАЛОСЬ ОТКРЫТЬ ССЫЛКУ ОБНОВЛЕНИЯ");
            status.setTextColor(0xFFFF7B72);
        }
    }

    private int compareVersions(String latest, String current) {
        int[] a = versionParts(latest);
        int[] b = versionParts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private int[] versionParts(String value) {
        String cleaned = value == null ? "" : value.replaceFirst("^[vV]", "").split("[-+]", 2)[0];
        String[] parts = cleaned.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { numbers[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { numbers[i] = 0; }
        }
        return numbers;
    }

    private String readAll(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    @Override protected void onPause() {
        autoRefreshEnabled = false;
        paperAutoEnabled = false;
        handler.removeCallbacks(refreshRunnable);
        if (autoButton != null) autoButton.setText("ВКЛЮЧИТЬ АВТООБНОВЛЕНИЕ (10 СЕК.)");
        if (paperButton != null) paperButton.setText("ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ СДЕЛОК");
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
