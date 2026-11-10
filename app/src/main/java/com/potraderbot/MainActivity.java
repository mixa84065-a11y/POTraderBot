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

    private Button[] platformTabs;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildScreen() {
        SharedPreferences preferences = getSharedPreferences("po_trader_settings", MODE_PRIVATE);
        paperTrading = new PaperTradingEngine(preferences);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF090909);

        ImageView backgroundArt = new ImageView(this);
        backgroundArt.setImageResource(R.drawable.god_trading_art);
        backgroundArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backgroundArt.setAlpha(0.58f);
        root.addView(backgroundArt, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        View overlay = new View(this);
        overlay.setBackgroundColor(0x72090909);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(10), dp(12), dp(16));
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button hamburger = makeNavButton("☰");
        hamburger.setTextSize(22);
        hamburger.setPadding(dp(0), dp(0), dp(0), dp(0));
        header.addView(hamburger, new LinearLayout.LayoutParams(dp(46), dp(48)));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("GOD OF TRADING", 25, 0xFFFFD36A, true);
        title.setShadowLayer(10, 0, 0, 0xFFFFA000);
        TextView subtitle = label("СИЛА В АНАЛИЗЕ", 12, 0xFFFFD45A, true);
        TextView sloganTop = label("— Торгуй грамотно, управляй рынком —", 10, 0xFFE7E7E7, true);
        brand.addView(title);
        brand.addView(subtitle);
        brand.addView(sloganTop);
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button menuButton = makeNavButton("⋮");
        menuButton.setTextSize(28);
        header.addView(menuButton, new LinearLayout.LayoutParams(dp(48), dp(52)));
        page.addView(header);

        status = label("●  ОЖИДАНИЕ ДАННЫХ", 12, 0xFFFFC857, true);
        status.setGravity(android.view.Gravity.CENTER);
        status.setPadding(dp(8), dp(6), dp(8), dp(6));
        page.addView(status);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button demoMode = makeModeButton("♟  ДЕМО", "Учебный режим", 0xFF168BFF);
        Button realMode = makeModeButton("▤  РЕАЛЬНЫЙ\nСЧЁТ", "Только сигналы", 0xFFFF3434);
        modeRow.addView(demoMode, new LinearLayout.LayoutParams(0, dp(76), 1f));
        LinearLayout.LayoutParams realModeParams = new LinearLayout.LayoutParams(0, dp(76), 1f);
        realModeParams.leftMargin = dp(7);
        modeRow.addView(realMode, realModeParams);
        page.addView(modeRow);
        Runnable updateModeStyle = () -> {
            int selected = modeInput == null ? preferences.getInt("trading_mode", 0) : modeInput.getSelectedItemPosition();
            styleModeButton(demoMode, selected == 0, 0xFF168BFF);
            styleModeButton(realMode, selected == 1, 0xFFFF3434);
        };
        demoMode.setOnClickListener(v -> { modeInput.setSelection(0); updateModeStyle.run(); });
        realMode.setOnClickListener(v -> { modeInput.setSelection(1); updateModeStyle.run(); });

        LinearLayout assetRow = new LinearLayout(this);
        assetRow.setOrientation(LinearLayout.HORIZONTAL);
        assetRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView assetCaption = label("Выбор актива:", 14, 0xFFF4F4F4, true);
        assetRow.addView(assetCaption, new LinearLayout.LayoutParams(0, dp(54), 1f));
        Button assetButton = makeNavButton("EUR/USD  ▾");
        assetButton.setTextSize(14);
        assetRow.addView(assetButton, new LinearLayout.LayoutParams(dp(116), dp(54)));
        TextView quoteView = label("—", 20, 0xFF00E676, true);
        quoteView.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams quoteParams = new LinearLayout.LayoutParams(dp(58), dp(54));
        quoteParams.leftMargin = dp(6);
        assetRow.addView(quoteView, quoteParams);
        Button assetHelp = makeTinyHelp("Выбор актива",
                "Выберите валютную пару для анализа. Котировки загружаются из Twelve Data; выбор не открывает и не размещает сделку.");
        assetRow.addView(assetHelp, new LinearLayout.LayoutParams(dp(34), dp(38)));
        assetButton.setOnClickListener(v -> {
            EditText input = editField("Например, EUR/USD");
            input.setText(symbolInput.getText().toString());
            new AlertDialog.Builder(this).setTitle("Выбор актива")
                    .setView(input)
                    .setPositiveButton("Сохранить", (d, w) -> {
                        String value = input.getText().toString().trim();
                        if (!value.isEmpty()) {
                            symbolInput.setText(value);
                            assetButton.setText(value.toUpperCase(Locale.US) + "  ▾");
                        }
                    }).setNegativeButton("Отмена", null).show();
        });
        LinearLayout.LayoutParams assetParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        assetParams.topMargin = dp(10);
        page.addView(assetRow, assetParams);

        LinearLayout signalButtons = new LinearLayout(this);
        signalButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button upSignal = makeSignalButton("⬆  ВВЕРХ  ?", 0xFF00D83A);
        Button downSignal = makeSignalButton("⬇  ВНИЗ  ?", 0xFFFF2525);
        upSignal.setTextSize(18);
        downSignal.setTextSize(18);
        upSignal.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Сигнал ВВЕРХ")
                .setMessage("Это визуальный индикатор направления аналитического сигнала. Он не отправляет торговую заявку.")
                .setPositiveButton("Понятно", null).show());
        downSignal.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Сигнал ВНИЗ")
                .setMessage("Это визуальный индикатор направления аналитического сигнала. Он не отправляет торговую заявку.")
                .setPositiveButton("Понятно", null).show());
        signalButtons.addView(upSignal, new LinearLayout.LayoutParams(0, dp(70), 1f));
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(0, dp(70), 1f);
        downParams.leftMargin = dp(8);
        signalButtons.addView(downSignal, downParams);
        LinearLayout.LayoutParams signalButtonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        signalButtonsParams.topMargin = dp(10);
        page.addView(signalButtons, signalButtonsParams);
        signalDisplayView = label("ЖДАТЬ  ◷", 20, 0xFFFFC857, true);
        signalDisplayView.setGravity(android.view.Gravity.CENTER);
        signalDisplayView.setPadding(dp(0), dp(6), dp(0), dp(2));
        page.addView(signalDisplayView);
        waitSignalView = label("EMA 9/21  •  RSI 14  •  ФИЛЬТР ТРЕНДА", 11, 0xFFD8D8D8, false);
        waitSignalView.setGravity(android.view.Gravity.CENTER);
        waitSignalView.setPadding(dp(4), dp(2), dp(4), dp(6));
        page.addView(waitSignalView);

        analyzeButton = makeAccentButton("⟳  НОВЫЙ АНАЛИЗ");
        analyzeButton.setOnClickListener(v -> loadAndAnalyze(false));
        autoButton = makeAccentButton("ВКЛЮЧИТЬ АВТООБНОВЛЕНИЕ");
        autoButton.setOnClickListener(v -> toggleAutoRefresh());
        paperButton = makeAccentButton("ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ");
        paperButton.setOnClickListener(v -> togglePaperAuto());

        details = label("Укажите API-ключ Twelve Data через меню ⋮ и запустите анализ.", 12, 0xFFE1E8F0, false);
        details.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout detailsCard = panel();
        detailsCard.addView(details);
        page.addView(detailsCard);

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(10), dp(8), dp(10), dp(8));
        settings.addView(label("ВАЛЮТНАЯ ПАРА", 12, 0xFFFFD45A, true));
        symbolInput = editField("EUR/USD");
        symbolInput.setText("EUR/USD");
        settings.addView(symbolInput);
        settings.addView(label("API-КЛЮЧ TWELVE DATA", 12, 0xFFFFD45A, true));
        apiKeyInput = editField("Вставьте личный API-ключ");
        apiKeyInput.setInputType(0x00000081);
        settings.addView(apiKeyInput);
        TextView platformNote = label("Котировки поступают из Twelve Data. Выбор площадки меняет только профиль интерфейса.", 11, 0xFFB9C5D5, false);
        settings.addView(platformNote);
        settings.setVisibility(View.GONE);
        page.addView(settings);

        platformInput = new Spinner(this);
        String[] platforms = {"Pocket Option", "Binarium", "Универсальный"};
        ArrayAdapter<String> platformAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, platforms);
        platformAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platformInput.setAdapter(platformAdapter);
        platformInput.setSelection(Math.max(0, Math.min(preferences.getInt("platform_index", 0), platforms.length - 1)));
        platformInput.setVisibility(View.GONE);
        modeInput = new Spinner(this);
        String[] modes = {"Демо", "Реальный счёт — только сигналы"};
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeInput.setAdapter(modeAdapter);
        modeInput.setSelection(Math.max(0, Math.min(preferences.getInt("trading_mode", 0), modes.length - 1)));
        modeInput.setVisibility(View.GONE);
        modeInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("trading_mode", position).apply();
                updateModeStyle.run();
                if (paperButton != null && !paperAutoEnabled) paperButton.setText("ВКЛЮЧИТЬ АВТОСИМУЛЯЦИЮ");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        updateModeStyle.run();
        platformInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("platform_index", position).apply();
                refreshPlatformTabs(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        LinearLayout platformNav = new LinearLayout(this);
        platformNav.setOrientation(LinearLayout.HORIZONTAL);
        platformTabs = new Button[3];
        String[] tabNames = {"🔵\nPocket Option", "◢\nBinarium", "◎\nУниверсальный"};
        for (int i = 0; i < tabNames.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER);
            Button tab = makePlatformButton(tabNames[i]);
            tab.setTextSize(i == 2 ? 9 : 10);
            tab.setOnClickListener(v -> platformInput.setSelection(index));
            platformTabs[i] = tab;
            item.addView(tab, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
            Button help = makeTinyHelp(i == 0 ? "Pocket Option" : i == 1 ? "Binarium" : "Универсальный",
                    "Выбор площадки меняет только профиль интерфейса и ссылку открытия. Приложение не подключается к аккаунту и не отправляет сделки.");
            item.addView(help, new LinearLayout.LayoutParams(dp(30), dp(26)));
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(78), 1f);
            if (i > 0) itemParams.leftMargin = dp(6);
            platformNav.addView(item, itemParams);
        }
                        LinearLayout.LayoutParams platformParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        platformParams.topMargin = dp(10);
        page.addView(platformNav, platformParams);
        refreshPlatformTabs(platformInput.getSelectedItemPosition());

        TextView featuresCaption = label("ИНСТРУМЕНТЫ АНАЛИЗА", 11, 0xFFFFD45A, true);
        featuresCaption.setGravity(android.view.Gravity.CENTER);
        featuresCaption.setPadding(0, dp(12), 0, dp(5));
        page.addView(featuresCaption);

        LinearLayout featureRow = new LinearLayout(this);
        featureRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] featureIcons = {"◔", "▥", "▦", "◷", "↗"};
        String[] featureNames = {"Сила\nсигнала", "Объяснение\nсигнала", "Калькулятор\nриска", "Часы\nсессий", "Умный\nжурнал"};
        int[] featureIndexes = {5, 6, 7, 8, 9};
        for (int i = 0; i < featureNames.length; i++) {
            final int featureIndex = featureIndexes[i];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(android.view.Gravity.CENTER);
            tile.setPadding(dp(5), dp(7), dp(5), dp(6));
            android.graphics.drawable.GradientDrawable tileBg = new android.graphics.drawable.GradientDrawable();
            tileBg.setColor(0xE9101010);
            tileBg.setCornerRadius(dp(12));
            tileBg.setStroke(dp(1), 0xFFB88924);
            tile.setBackground(tileBg);
            TextView glyph = label(featureIcons[i], 20, 0xFFFFD45A, true);
            glyph.setGravity(android.view.Gravity.CENTER);
            tile.addView(glyph);
            TextView tileLabel = label(featureNames[i], 8, 0xFFF3E7C8, true);
            tileLabel.setGravity(android.view.Gravity.CENTER);
            tile.addView(tileLabel);
            Button help = makeTinyHelp(featureNames[i].replace("\n", " "),
                    "Открыть описание и инструменты этого раздела.");
            tile.addView(help, new LinearLayout.LayoutParams(dp(22), dp(22)));
            tile.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(featureNames[featureIndex == 5 ? 0 : featureIndex == 6 ? 1 : featureIndex == 7 ? 2 : featureIndex == 8 ? 3 : 4].replace("\n", " "))
                    .setView(featureContent(featureIndex))
                    .setPositiveButton("Готово", null).show());
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(98), 1f);
            if (i > 0) tileParams.leftMargin = dp(3);
            featureRow.addView(tile, tileParams);
        }
        page.addView(featureRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(102)));

        TextView disclaimer = label("— ТОРГУЙ ГРАМОТНО, УПРАВЛЯЙ РЫНКОМ —", 11, 0xFFFFC72C, true);
        disclaimer.setGravity(android.view.Gravity.CENTER);
        disclaimer.setPadding(dp(2), dp(10), dp(2), dp(4));
        page.addView(disclaimer);

        LinearLayout bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(android.view.Gravity.CENTER);
        String[] bottomLabels = {"▥\nАНАЛИТИКА  ?", "⚙\nНАСТРОЙКИ  ?", "ⓘ\nИНФО  ?", "⟳\nОБНОВЛЕНИЕ  ?"};
        for (int i = 0; i < bottomLabels.length; i++) {
            final int action = i;
            Button nav = makeNavButton(bottomLabels[i]);
            nav.setTextSize(8);
            nav.setPadding(dp(1), dp(1), dp(1), dp(1));
            LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
            if (i > 0) navParams.leftMargin = dp(4);
            bottomNav.addView(nav, navParams);
            nav.setOnClickListener(v -> {
                if (action == 0) showFeatureMenu(settings);
                else if (action == 1) showToolSettings(settings);
                else if (action == 2) new AlertDialog.Builder(this)
                        .setTitle("GOD OF TRADING")
                        .setMessage("Помощник для анализа рынка и тестирования стратегий. Приложение показывает аналитические сигналы и не подключается к торговому аккаунту и не совершает сделки.")
                        .setPositiveButton("Понятно", null).show();
                else checkForUpdate();
            });
        }
        LinearLayout.LayoutParams bottomNavParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        bottomNavParams.topMargin = dp(8);
        page.addView(bottomNav, bottomNavParams);

        TextView versionLabel = label("v" + BuildConfig.VERSION_NAME, 9, 0xFF777777, false);
        versionLabel.setGravity(android.view.Gravity.CENTER);
        versionLabel.setAlpha(0.48f);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(20));
        versionParams.topMargin = dp(2);
        page.addView(versionLabel, versionParams);

        updateButton = makeNavButton("⟳  ПРОВЕРИТЬ ОБНОВЛЕНИЯ");
        updateButton.setOnClickListener(v -> checkForUpdate());
        hamburger.setOnClickListener(v -> showFeatureMenu(settings));
        menuButton.setOnClickListener(v -> showFeatureMenu(settings));
        setContentView(root);
    }


    private void showToolSettings(LinearLayout settings) {
        android.view.ViewParent parent = settings.getParent();
        android.view.ViewGroup settingsParent = parent instanceof android.view.ViewGroup
                ? (android.view.ViewGroup) parent : null;
        int settingsIndex = settingsParent == null ? -1 : settingsParent.indexOfChild(settings);
        android.view.ViewGroup.LayoutParams settingsParams = settings.getLayoutParams();
        if (settingsParent != null) settingsParent.removeView(settings);

        AlertDialog settingsDialog = new AlertDialog.Builder(this)
                .setTitle("Настройки инструмента")
                .setView(settings)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Закрыть", null)
                .create();
        settingsDialog.setOnDismissListener(d -> {
            settings.setVisibility(View.GONE);
            if (settingsParent != null && settings.getParent() == null) {
                int insertAt = Math.max(0, Math.min(settingsIndex, settingsParent.getChildCount()));
                settingsParent.addView(settings, insertAt, settingsParams);
            }
        });
        settings.setVisibility(View.VISIBLE);
        settingsDialog.show();
    }

    private void showFeatureMenu(LinearLayout settings) {
        final String[] names = {
                "График и анализ", "Риск-менеджмент и блокировка", "Уведомления",
                "Журнал сделок и статистика", "Аналитика через трансляцию",
                "Индикатор силы сигнала", "Объяснение сигнала", "Калькулятор риска",
                "Часы торговых сессий", "Умный журнал"
        };
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(10), dp(8), dp(10), dp(8));
        container.setBackgroundColor(0xFF090909);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(container);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("GOD OF TRADING • МЕНЮ")
                .setView(scroll)
                .setNegativeButton("Закрыть", null)
                .create();

        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(5), dp(10), dp(5));
            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(0xFF151515);
            cardBg.setCornerRadius(dp(12));
            cardBg.setStroke(dp(1), 0xFF80652D);
            card.setBackground(cardBg);

            TextView heading = label("＋  " + names[i], 14, 0xFFFFD45A, true);
            heading.setPadding(dp(8), dp(10), dp(8), dp(10));
            card.addView(heading);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(8), dp(4), dp(8), dp(10));
            content.setVisibility(View.GONE);
            content.addView(featureContent(index));
            card.addView(content);
            heading.setOnClickListener(v -> {
                boolean opening = content.getVisibility() != View.VISIBLE;
                content.setVisibility(opening ? View.VISIBLE : View.GONE);
                heading.setText((opening ? "−  " : "＋  ") + names[index]);
            });
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(7);
            container.addView(card, cardParams);
        }

        Button collapse = makeNavButton("СВЕРНУТЬ ВСЕ РАЗДЕЛЫ");
        collapse.setOnClickListener(v -> {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof LinearLayout) {
                    LinearLayout card = (LinearLayout) child;
                    if (card.getChildCount() > 1) {
                        card.getChildAt(1).setVisibility(View.GONE);
                        if (card.getChildAt(0) instanceof TextView) {
                            TextView heading = (TextView) card.getChildAt(0);
                            heading.setText(heading.getText().toString().replace("−  ", "＋  "));
                        }
                    }
                }
            }
        });
        container.addView(collapse);
        Button update = makeNavButton("ПРОВЕРИТЬ ОБНОВЛЕНИЯ");
        update.setOnClickListener(v -> { dialog.dismiss(); checkForUpdate(); });
        container.addView(update);
        dialog.show();
    }

    private View featureContent(int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        TextView info;
        switch (index) {
            case 0:
                info = label("Текущий анализ и котировки. Источник данных: Twelve Data.", 12, 0xFFE0E0E0, false);
                box.addView(info);
                Button analyze = makeNavButton("ЗАПУСТИТЬ АНАЛИЗ");
                analyze.setOnClickListener(v -> loadAndAnalyze(false));
                box.addView(analyze);
                break;
            case 1:
                info = label("Контроль риска и лимитов. Пока это информационный раздел: автоматическая блокировка сделок не подключена.", 12, 0xFFE0E0E0, false);
                box.addView(info);
                Button risk = makeNavButton("ОТКРЫТЬ КАЛЬКУЛЯТОР РИСКА");
                risk.setOnClickListener(v -> openRiskCalculator());
                box.addView(risk);
                break;
            case 2:
                info = label("Раздел уведомлений подготовлен в интерфейсе. Push-уведомления о сигналах ещё не подключены.", 12, 0xFFE0E0E0, false);
                box.addView(info);
                break;
            case 3:
            case 9:
                info = label(paperTrading == null ? "Журнал пока пуст." : paperTrading.getSummary(), 12, 0xFFE0E0E0, false);
                box.addView(info);
                box.addView(label("Статистика относится к бумажной симуляции и не является результатом реальной торговли.", 11, 0xFFFFD45A, false));
                break;
            case 4:
                info = label("Анализ трансляции экрана пока в разработке. Для него потребуется отдельное разрешение Android на захват экрана; сейчас трансляция не записывается и не анализируется.", 12, 0xFFE0E0E0, false);
                box.addView(info);
                break;
            case 5:
                String strength = signalDisplayView == null ? "Ожидание данных" : signalDisplayView.getText().toString();
                info = label("Текущий сигнал: " + strength + "\nОценка силы будет рассчитываться после получения и проверки рыночных данных. Само направление не гарантирует результат.", 12, 0xFFE0E0E0, false);
                box.addView(info);
                break;
            case 6:
                info = label(waitSignalView == null ? "Сначала запустите анализ." : waitSignalView.getText().toString(), 12, 0xFFE0E0E0, false);
                box.addView(info);
                break;
            case 7:
                info = label("Рассчитайте ориентировочный размер риска для учебной сделки. Расчёт не отправляет ордера.", 12, 0xFFE0E0E0, false);
                box.addView(info);
                Button calculator = makeNavButton("РАССЧИТАТЬ РИСК");
                calculator.setOnClickListener(v -> openRiskCalculator());
                box.addView(calculator);
                break;
            case 8:
                java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                java.util.Date now = new java.util.Date();
                clock.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
                String asia = clock.format(now);
                clock.setTimeZone(java.util.TimeZone.getTimeZone("Europe/London"));
                String london = clock.format(now);
                clock.setTimeZone(java.util.TimeZone.getTimeZone("America/New_York"));
                String newYork = clock.format(now);
                info = label("АЗИЯ  •  Токио: " + asia + "\nЛОНДОН: " + london + "\nНЬЮ-ЙОРК: " + newYork + "\nВремя указано по часовым поясам городов.", 12, 0xFFE0E0E0, false);
                box.addView(info);
                break;
            default:
                info = label("Раздел готовится.", 12, 0xFFE0E0E0, false);
                box.addView(info);
        }
        return box;
    }

    private void openRiskCalculator() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(4));
        EditText balance = editField("Размер счёта, например 20000");
        balance.setInputType(8194);
        balance.setText("20000");
        EditText percent = editField("Риск в процентах, например 1");
        percent.setInputType(8194);
        percent.setText("1");
        form.addView(label("Размер счёта", 12, 0xFFFFD45A, true));
        form.addView(balance);
        form.addView(label("Допустимый риск (%)", 12, 0xFFFFD45A, true));
        form.addView(percent);
        new AlertDialog.Builder(this).setTitle("Калькулятор риска")
                .setView(form)
                .setPositiveButton("Рассчитать", (d, w) -> {
                    try {
                        double amount = Double.parseDouble(balance.getText().toString().replace(',', '.'));
                        double rate = Double.parseDouble(percent.getText().toString().replace(',', '.'));
                        if (amount <= 0 || rate <= 0 || rate > 100) throw new NumberFormatException();
                        double risk = amount * rate / 100.0;
                        new AlertDialog.Builder(this).setTitle("Расчёт риска")
                                .setMessage(String.format(java.util.Locale.US,
                                        "Ориентировочный лимит риска: %.2f\nЭто расчёт для планирования, не гарантия ограничения убытка.", risk))
                                .setPositiveButton("Понятно", null).show();
                    } catch (Exception e) {
                        new AlertDialog.Builder(this).setTitle("Проверьте значения")
                                .setMessage("Введите положительную сумму и риск от 0 до 100%.")
                                .setPositiveButton("Понятно", null).show();
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    private TextView waitSignalView;
    private TextView signalDisplayView;


    private Button makeModeButton(String title, String subtitle, int accent) {
        Button button = new Button(this);
        button.setText(title + "\n" + subtitle);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(0xFFFFFFFF);
        return button;
    }

    private void styleModeButton(Button button, boolean selected, int accent) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(selected ? (accent == 0xFF168BFF ? 0xFF09284B : 0xFF421010) : 0xFF101010);
        bg.setCornerRadius(22);
        bg.setStroke(selected ? 4 : 2, accent);
        button.setBackground(bg);
        button.setTextColor(selected ? 0xFFFFFFFF : 0xFFBFC5CE);
        button.setElevation(selected ? 8 : 2);
    }

    private Button makePlatformButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(11);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF101317);
        bg.setCornerRadius(16);
        bg.setStroke(2, 0xFFB88924);
        button.setBackground(bg);
        return button;
    }

    private Button makeTinyHelp(String title, String message) {
        Button help = new Button(this);
        help.setText("?");
        help.setAllCaps(false);
        help.setTextSize(12);
        help.setMinWidth(0);
        help.setPadding(dp(0), dp(0), dp(0), dp(0));
        help.setTextColor(0xFFFFD45A);
        help.setBackgroundTintList(ColorStateList.valueOf(0xFF252019));
        help.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(title)
                .setMessage(message).setPositiveButton("Понятно", null).show());
        return help;
    }

    private LinearLayout panel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE8111721);
        bg.setCornerRadius(18);
        bg.setStroke(2, 0xFF303B4A);
        box.setBackground(bg);
        return box;
    }

    private Button makeAccentButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(0xFF10151C);
        button.setBackgroundTintList(ColorStateList.valueOf(0xFFFFC72C));
        return button;
    }

    private void refreshPlatformTabs(int selected) {
        if (platformTabs == null) return;
        for (int i = 0; i < platformTabs.length; i++) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(i == selected ? 0xFF263548 : 0xFF111720);
            bg.setCornerRadius(12);
            bg.setStroke(i == selected ? 3 : 1, i == selected ? 0xFFFFC72C : 0xFF465366);
            platformTabs[i].setBackground(bg);
            platformTabs[i].setTextColor(i == selected ? 0xFFFFD45A : 0xFFE7EDF5);
        }
    }

    private LinearLayout selectorBox(String caption) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
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
        button.setTextSize(27);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(0xFFFFF3D6);
        android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
        border.setColor(0xFF050505);
        border.setCornerRadius(28);
        border.setStroke(4, color);
        button.setBackground(border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(88));
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
                    .setItems(new String[]{"Pocket Option", "Binarium"}, (dialog, which) ->
                            openPlatformUrl(which == 0
                                    ? "https://pocketoption.com/"
                                    : "https://binarium.com/"))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        openPlatformUrl(selected == 0 ? "https://pocketoption.com/" : "https://binarium.com/");
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
                    signalDisplayView.setText(signalLabel(result.signal) + (result.signal == MarketAnalyzer.Signal.CALL ? "  ▲" : result.signal == MarketAnalyzer.Signal.PUT ? "  ▼" : "  ◷"));
                    signalDisplayView.setTextColor(result.signal == MarketAnalyzer.Signal.WAIT ? 0xFFFFC857
                            : result.signal == MarketAnalyzer.Signal.CALL ? 0xFF00E676 : 0xFFFF5252);
                    waitSignalView.setText(result.explanation);
                    String analysisText = String.format(Locale.US,
                            "Режим: %s\nПлощадка в интерфейсе: %s\nИсточник котировок: Twelve Data\nПара: %s\nЗакрытых свечей: %d\nПоследняя свеча UTC: %tF %tR\nЦена закрытия: %.5f\n\nEMA 9: %.5f\nEMA 21: %.5f\nRSI 14: %.2f\n\n%s\n\nЭто аналитический сигнал, а не команда на сделку.",
                            mode, platform, symbol.toUpperCase(Locale.US), candles.size(), last.time, last.time,
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
        row.addView(help, new LinearLayout.LayoutParams(dp(44), dp(48)));
        parent.addView(row);
    }

    private void addHelpTitle(LinearLayout parent, String title, String explanation) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView heading = label(title, 15, 0xFFE8E0CF, true);
        row.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button help = new Button(this);
        help.setText("?");
        help.setAllCaps(false);
        help.setTextSize(13);
        help.setMinWidth(38);
        help.setMinimumWidth(38);
        help.setPadding(dp(0), dp(0), dp(0), dp(0));
        help.setTextColor(0xFFFFFFFF);
        help.setBackgroundTintList(ColorStateList.valueOf(0xFF64748B));
        help.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title).setMessage(explanation)
                .setPositiveButton("Понятно", null).show());
        row.addView(help, new LinearLayout.LayoutParams(dp(40), dp(40)));
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
