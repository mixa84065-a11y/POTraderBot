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
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private static final int BG = 0xFF080808;
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
        applyImmersiveMode();
        buildScreen();
        checkForUpdate();
        handler.postDelayed(updateCheckRunnable, UPDATE_CHECK_MS);
    }

    private void buildScreen() {
        SharedPreferences preferences = getSharedPreferences("po_trader_settings", MODE_PRIVATE);
        paperTrading = new PaperTradingEngine(preferences);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF080808);

        ImageView backgroundArt = new ImageView(this);
        backgroundArt.setImageResource(R.drawable.dedgod_logo);
        backgroundArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backgroundArt.setAlpha(0.24f);
        root.addView(backgroundArt, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View darkOverlay = new View(this);
        darkOverlay.setBackgroundColor(0xA8000000);
        root.addView(darkOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0x00000000);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(18, 18, 18, 18);
        page.setBackgroundColor(0x00000000);
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView crown = label("♛", 34, 0xFFFFC72C, true);
        crown.setGravity(android.view.Gravity.CENTER);
        page.addView(crown);
        TextView title = label("GOD OF TRADING", 28, 0xFFFFC72C, true);
        title.setGravity(android.view.Gravity.CENTER);
        page.addView(title);
        TextView subtitle = label("ТОРГОВЫЙ ПОМОЩНИК", 14, 0xFFFFD76A, true);
        subtitle.setGravity(android.view.Gravity.CENTER);
        page.addView(subtitle);

        ImageView brandImage = new ImageView(this);
        brandImage.setImageResource(R.drawable.dedgod_logo);
        brandImage.setAdjustViewBounds(true);
        brandImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 245);
        brandParams.topMargin = 8;
        brandParams.bottomMargin = 4;
        page.addView(brandImage, brandParams);

        TextView ribbon = label("★  GOD OF TRADING  ★", 20, 0xFFFFD45A, true);
        ribbon.setGravity(android.view.Gravity.CENTER);
        ribbon.setPadding(0, 4, 0, 16);
        page.addView(ribbon);

        status = label("ТЕКУЩИЙ СИГНАЛ: ЖДАТЬ", 20, 0xFFFFD45A, true);
        status.setGravity(android.view.Gravity.CENTER);
        status.setPadding(0, 10, 0, 12);
        page.addView(status);

        LinearLayout safetyPanel = new LinearLayout(this);
        safetyPanel.setOrientation(LinearLayout.VERTICAL);
        safetyPanel.setPadding(12, 10, 12, 10);
        android.graphics.drawable.GradientDrawable safetyBg = new android.graphics.drawable.GradientDrawable();
        safetyBg.setColor(0xFF17130A);
        safetyBg.setCornerRadius(14);
        safetyBg.setStroke(1, 0xFF80652D);
        safetyPanel.setBackground(safetyBg);
        TextView safetyText = label("ПРАВИЛО БЕЗОПАСНОСТИ\\nСигналы и автосимуляция не гарантируют прибыль. Симуляция виртуальная и не размещает реальные ордера.", 12, 0xFFFFDFA0, false);
        safetyPanel.addView(safetyText);
        Button hideSafety = makeNavButton("СКРЫТЬ ПРАВИЛО БЕЗОПАСНОСТИ");
        safetyPanel.addView(hideSafety);
        page.addView(safetyPanel);
        boolean safetyHidden = preferences.getBoolean("hide_safety_rule", true);
        safetyPanel.setVisibility(safetyHidden ? View.GONE : View.VISIBLE);
        Button showSafety = makeNavButton("ⓘ ПРАВИЛО БЕЗОПАСНОСТИ");
        showSafety.setVisibility(safetyHidden ? View.VISIBLE : View.GONE);
        page.addView(showSafety);
        hideSafety.setOnClickListener(v -> {
            preferences.edit().putBoolean("hide_safety_rule", true).apply();
            safetyPanel.setVisibility(View.GONE);
            showSafety.setVisibility(View.VISIBLE);
        });
        showSafety.setOnClickListener(v -> {
            preferences.edit().putBoolean("hide_safety_rule", false).apply();
            safetyPanel.setVisibility(View.VISIBLE);
            showSafety.setVisibility(View.GONE);
        });

        Button upSignal = makeSignalButton("↑     ВВЕРХ", 0xFF00D94A);
        Button downSignal = makeSignalButton("↓     ВНИЗ", 0xFFFF2424);
        Button waitSignal = makeSignalButton("◷     ЖДАТЬ", 0xFFE6E6E6);
        page.addView(upSignal);
        page.addView(downSignal);
        page.addView(waitSignal);

        analyzeButton = makeButton("⟳   НОВЫЙ АНАЛИЗ");
        analyzeButton.setOnClickListener(v -> loadAndAnalyze(false));
        addButtonWithHelp(page, analyzeButton, "Новый анализ",
                "Загружает закрытые минутные свечи и рассчитывает EMA 9, EMA 21 и RSI 14. Сигнал является аналитическим, а не командой на сделку.");

        autoButton = makeButton("ВКЛЮЧИТЬ АВТООБНОВЛЕНИЕ");
        autoButton.setOnClickListener(v -> toggleAutoRefresh());
        addButtonWithHelp(page, autoButton, "Автообновление",
                "Обновляет котировки и сигнал каждые 10 секунд, пока приложение открыто.");

        paperButton = makeButton("ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ");
        paperButton.setOnClickListener(v -> togglePaperAuto());
        addButtonWithHelp(page, paperButton, "Автосимуляция",
                "Автосимуляция использует реальные котировки выбранного графика, но открывает только виртуальные сделки и не отправляет ордера брокеру. Можно включать и при режиме «Реальный — сигналы».");

        details = label("Сначала укажите API-ключ Twelve Data в настройках и нажмите «Новый анализ».",
                14, 0xFFE8E0CF, false);
        details.setPadding(10, 18, 10, 18);
        page.addView(details);

        TextView settingsHeading = label("НАСТРОЙКИ АНАЛИЗА", 16, 0xFFFFD45A, true);
        settingsHeading.setPadding(0, 16, 0, 8);
        page.addView(settingsHeading);

        addHelpTitle(page, "Валютная пара", "Укажите пару в формате EUR/USD. Анализ использует минутные свечи.");
        symbolInput = editField("EUR/USD");
        symbolInput.setText("EUR/USD");
        page.addView(symbolInput);

        addHelpTitle(page, "API-ключ Twelve Data", "Ключ нужен для загрузки котировок. Не вводите здесь пароль от брокера.");
        apiKeyInput = editField("Вставьте личный API-ключ");
        apiKeyInput.setInputType(0x00000081);
        page.addView(apiKeyInput);

        LinearLayout selectors = new LinearLayout(this);
        selectors.setOrientation(LinearLayout.HORIZONTAL);
        selectors.setGravity(android.view.Gravity.CENTER_VERTICAL);
        selectors.setPadding(0, 14, 0, 10);

        LinearLayout platformBox = selectorBox("ПЛОЩАДКА");
        platformInput = new Spinner(this);
        String[] platforms = {"Binarium", "Pocket Option", "Универсальный"};
        ArrayAdapter<String> platformAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, platforms);
        platformAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platformInput.setAdapter(platformAdapter);
        platformInput.setSelection(Math.max(0, Math.min(preferences.getInt("platform_index", 0), platforms.length - 1)));
        platformInput.setBackgroundTintList(ColorStateList.valueOf(0xFFFFC72C));
        platformBox.addView(platformInput);
        selectors.addView(platformBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout modeBox = selectorBox("РЕЖИМ");
        modeInput = new Spinner(this);
        String[] modes = {"Демо", "Реальный — сигналы"};
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeInput.setAdapter(modeAdapter);
        modeInput.setSelection(Math.max(0, Math.min(preferences.getInt("trading_mode", 0), modes.length - 1)));
        modeInput.setBackgroundTintList(ColorStateList.valueOf(0xFFFFC72C));
        modeBox.addView(modeInput);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modeParams.leftMargin = 8;
        selectors.addView(modeBox, modeParams);
        page.addView(selectors);

        platformInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("platform_index", position).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        modeInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("trading_mode", position).apply();
                if (paperButton != null && !paperAutoEnabled) {
                    paperButton.setText(position == 0 ? "ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ"
                            : "АВТОСИМУЛЯЦИЯ (ВИРТУАЛЬНО, НА РЕАЛЬНОМ ГРАФИКЕ)");
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        Button openPlatform = makeButton("ОТКРЫТЬ ПЛОЩАДКУ");
        openPlatform.setOnClickListener(v -> openSelectedPlatform());
        page.addView(openPlatform);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, 12, 0, 8);
        Button stats = makeNavButton("🏆\nСтатистика");
        stats.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Статистика")
                .setMessage(paperTrading == null ? "Пока нет данных." : paperTrading.getSummary())
                .setPositiveButton("Закрыть", null).show());
        Button about = makeNavButton("ⓘ\nО программе");
        about.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("GOD OF TRADING")
                .setMessage("Торговый помощник для анализа минутных свечей. Сигналы не гарантируют прибыль. Автоматическое размещение реальных ордеров не поддерживается.")
                .setPositiveButton("Понятно", null).show());
        updateButton = makeNavButton("⟳\nОбновить");
        updateButton.setOnClickListener(v -> checkForUpdate());
        nav.addView(stats, new LinearLayout.LayoutParams(0, 66, 1f));
        nav.addView(about, new LinearLayout.LayoutParams(0, 66, 1f));
        nav.addView(updateButton, new LinearLayout.LayoutParams(0, 66, 1f));
        page.addView(nav);

        TextView slogan = label("TRADE SMART. RULE THE MARKET.", 15, 0xFFFFC72C, true);
        slogan.setGravity(android.view.Gravity.CENTER);
        slogan.setPadding(0, 12, 0, 18);
        page.addView(slogan);

        setContentView(root);
    }

    private LinearLayout selectorBox(String caption) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(10, 8, 10, 8);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF111111);
        bg.setCornerRadius(16);
        bg.setStroke(2, 0xFF80652D);
        box.setBackground(bg);
        TextView title = label(caption, 11, 0xFFFFD45A, true);
        box.addView(title);
        return box;
    }

    private Button makeSignalButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(25);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(color);
        android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
        border.setColor(0xFF050505);
        border.setCornerRadius(28);
        border.setStroke(4, color);
        button.setBackground(border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 76);
        params.topMargin = 7;
        params.bottomMargin = 7;
        button.setLayoutParams(params);
        button.setClickable(false);
        button.setFocusable(false);
        return button;
    }

    private Button makeNavButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFD45A);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF111111);
        bg.setCornerRadius(14);
        bg.setStroke(2, 0xFFB88924);
        button.setBackground(bg);
        return button;
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
        button.setTextColor(0xFF15100A);
        button.setTypeface(null, Typeface.BOLD);
        button.setBackgroundTintList(ColorStateList.valueOf(0xFFFFC72C));
        return button;
    }

    private EditText editField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextColor(0xFFFFE6A0);
        field.setHintTextColor(0xFF9C8A65);
        field.setBackgroundTintList(ColorStateList.valueOf(0xFFB88924));
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
            paperButton.setText("ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ");
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



    private void addButtonWithHelp(LinearLayout parent, Button action, String title, String explanation) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(action, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button help = new Button(this);
        help.setText("?");
        help.setAllCaps(false);
        help.setTextColor(0xFFFFFFFF);
        help.setBackgroundTintList(ColorStateList.valueOf(0xFF64748B));
        help.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title).setMessage(explanation)
                .setPositiveButton("Понятно", null).show());
        row.addView(help, new LinearLayout.LayoutParams(44, 48));
        parent.addView(row);
    }

    private void addHelpTitle(LinearLayout parent, String title, String explanation) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView heading = label(title, 15, 0xFF344054, true);
        row.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button help = new Button(this);
        help.setText("?");
        help.setAllCaps(false);
        help.setTextSize(13);
        help.setMinWidth(38);
        help.setMinimumWidth(38);
        help.setPadding(0, 0, 0, 0);
        help.setTextColor(0xFFFFFFFF);
        help.setBackgroundTintList(ColorStateList.valueOf(0xFF64748B));
        help.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title).setMessage(explanation)
                .setPositiveButton("Понятно", null).show());
        row.addView(help, new LinearLayout.LayoutParams(40, 40));
        parent.addView(row);
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
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
