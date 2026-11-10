package com.potraderbot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.InputType;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class MainActivity extends Activity {
    private final TradeEngine engine = new TradeEngine();
    private ApiKeyStore apiKeyStore;
    private SupabaseAuthStore authStore;
    private SupabaseAuthClient authClient;
    private SupabaseSettingsClient settingsClient;
    private SupabaseSharingClient sharingClient;
    private TradingSettings currentSettings = TradingSettings.defaults();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView status, stats, log;
    private Button analyzeButton;
    private volatile boolean running = false;
    private Future<?> analysisTask;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            apiKeyStore = new ApiKeyStore(this);
        } catch (RuntimeException error) {
            android.util.Log.e("GOD_OF_TRADING", "Secure key storage unavailable", error);
        }
        try {
            authStore = new SupabaseAuthStore(this);
            authClient = new SupabaseAuthClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY);
            settingsClient = new SupabaseSettingsClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY);
            sharingClient = new SupabaseSharingClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY);
        } catch (RuntimeException error) {
            android.util.Log.e("GOD_OF_TRADING", "Secure auth storage unavailable", error);
        }
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

        TextView config = text("EUR/USD • 1-minute candles • 3-minute expiry\nMulti-factor analysis • max 10 manual records/day\nPause after 2 consecutive recorded losses", 15, 0xFFE5EDF7, false);
        config.setPadding(0, 12, 0, 24);
        page.addView(config);

        Button accountButton = button("АККАУНТ: ВХОД / РЕГИСТРАЦИЯ");
        accountButton.setOnClickListener(v -> showAuthDialog());
        page.addView(accountButton);

        Button settingsButton = button("НАСТРОЙКИ АНАЛИЗА");
        settingsButton.setOnClickListener(v -> loadSettingsAndShowDialog());
        page.addView(settingsButton);

        Button sharingButton = button("ОБМЕН СИГНАЛАМИ");
        sharingButton.setOnClickListener(v -> showSharingDialog());
        page.addView(sharingButton);

        Button apiSettings = button("НАСТРОИТЬ API-КЛЮЧИ");
        apiSettings.setOnClickListener(v -> showApiKeyDialog());
        page.addView(apiSettings);

        analyzeButton = button("АНАЛИЗИРОВАТЬ");
        analyzeButton.setOnClickListener(v -> runLiveAnalysis());
        page.addView(analyzeButton);

        Button stop = button("ОСТАНОВИТЬ АНАЛИЗ");
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

        log = text("Нажми «АНАЛИЗИРОВАТЬ», чтобы выполнить многофакторный анализ рынка и проверить экономический календарь.\nСделки автоматически не открываются.", 14, 0xFFB8C6D9, false);
        page.addView(log);
        page.addView(text("\nIMPORTANT: Market data comes from Twelve Data and may differ from Binarium quotes. Scenario scores are heuristic, not calibrated probabilities. Signals are informational, not a guarantee. Trades must be checked and confirmed manually in Binarium.", 13, 0xFFFFC857, true));
        setContentView(scroll);
    }

    private void runLiveAnalysis() {
        if (running) return;
        String twelveKey = apiKeyStore == null ? "" : apiKeyStore.getTwelveDataKey();
        String apiShKey = apiKeyStore == null ? "" : apiKeyStore.getApiShKey();
        if (twelveKey.trim().isEmpty() || apiShKey.trim().isEmpty()) {
            status.setText("ТРЕБУЮТСЯ API-КЛЮЧИ");
            status.setTextColor(0xFFFFC857);
            log.setText("Для анализа нужны оба API-ключа. Нажми «НАСТРОИТЬ API-КЛЮЧИ» и сохрани их в защищённом хранилище телефона. Ключи не отправляются в чат и не записываются в исходный код.");
            return;
        }

        running = true;
        analyzeButton.setEnabled(false);
        status.setText("ЗАГРУЗКА ДАННЫХ РЫНКА...");
        status.setTextColor(0xFFFFC857);
        log.setText("Запрашиваю историю минутных свечей и экономический календарь. Сделки не открываются.");

        analysisTask = executor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                BinariumDataEngine dataEngine = new BinariumDataEngine(twelveKey, apiShKey);
                java.util.ArrayList<String> reports = new java.util.ArrayList<>();
                int up = 0, down = 0;
                java.util.List<SupabaseSharingClient.Connection> shareContacts = new java.util.ArrayList<>();
                if (currentSettings.sharingEnabled && authStore != null && authStore.isSignedIn()) {
                    try {
                        for (SupabaseSharingClient.Connection c : sharingClient.connections(authStore.userId(), authStore.accessToken()))
                            if ("accepted".equals(c.status)) shareContacts.add(c);
                    } catch (Exception ignored) { }
                }
                for (String pair : currentSettings.selectedPairs) {
                    if (Thread.currentThread().isInterrupted()) return;
                    BinariumDataEngine.Result result = dataEngine.analyze(
                            pair, calendarDate(-1), calendarDate(2), System.currentTimeMillis());
                    if (result.signal == MarketAnalyzer.Signal.CALL) up++;
                    else if (result.signal == MarketAnalyzer.Signal.PUT) down++;
                    if (result.signal != MarketAnalyzer.Signal.WAIT && currentSettings.sharingEnabled) {
                        for (SupabaseSharingClient.Connection contact : shareContacts) {
                            try {
                                sharingClient.sendSignal(authStore.userId(), contact.otherUser(authStore.userId()),
                                        contact.id, pair, result.signal.name(), currentSettings.expiryMinutes,
                                        result.reason, authStore.accessToken());
                            } catch (Exception ignored) { }
                        }
                    }
                    String direction = result.signal == MarketAnalyzer.Signal.CALL ? "ВВЕРХ"
                            : result.signal == MarketAnalyzer.Signal.PUT ? "ВНИЗ" : "ОЖИДАНИЕ";
                    reports.add(pair + " — " + direction + "\n" + result.reason
                            + "\nСвечей: " + result.candleCount + "\n" + result.technicalReport);
                }
                final int upCount = up, downCount = down;
                final String allReports = android.text.TextUtils.join("\n\n----------------\n\n", reports);
                mainHandler.post(() -> {
                    if (!running || isFinishing()) return;
                    status.setText("ВВЕРХ: " + upCount + " • ВНИЗ: " + downCount
                            + " • ОЖИДАНИЕ: " + (currentSettings.selectedPairs.size()-upCount-downCount));
                    status.setTextColor(upCount + downCount == 0 ? 0xFFFFC857 : 0xFF70D6A5);
                    log.setText("Экспирация: " + currentSettings.expiryMinutes + " мин"
                            + "\n" + allReports + "\n\nИсточник котировок: Twelve Data"
                            + "\nЭкономический календарь: API.SH"
                            + "\nСделки автоматически не открываются.");
                    running = false;
                    analyzeButton.setEnabled(true);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!running || isFinishing()) return;
                    status.setText("ОШИБКА ДАННЫХ");
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



    private static final String[] AVAILABLE_PAIRS = {
            "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "USD/CAD",
            "AUD/USD", "NZD/USD", "EUR/JPY", "GBP/JPY", "EUR/GBP"
    };

    private void loadSettingsAndShowDialog() {
        if (authStore == null || !authStore.isSignedIn()) {
            log.setText("Сначала войди в аккаунт, чтобы сохранять индивидуальные настройки между устройствами.");
            return;
        }
        status.setText("ЗАГРУЗКА НАСТРОЕК...");
        executor.submit(() -> {
            try {
                TradingSettings loaded = settingsClient.load(authStore.userId(), authStore.accessToken());
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        currentSettings = loaded;
                        showSettingsDialog();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        status.setText("НАСТРОЙКИ НЕ ЗАГРУЖЕНЫ");
                        log.setText("Не удалось получить настройки из Supabase: " + safeMessage(e));
                    }
                });
            }
        });
    }

    private void showSettingsDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, 0, pad, 0);
        TextView pairsTitle = text("ВАЛЮТНЫЕ ПАРЫ (1–10)", 15, 0xFFE5EDF7, true);
        layout.addView(pairsTitle);
        CheckBox[] pairChecks = new CheckBox[AVAILABLE_PAIRS.length];
        for (int i = 0; i < AVAILABLE_PAIRS.length; i++) {
            pairChecks[i] = new CheckBox(this);
            pairChecks[i].setText(AVAILABLE_PAIRS[i]);
            pairChecks[i].setTextColor(0xFFE5EDF7);
            pairChecks[i].setChecked(currentSettings.selectedPairs.contains(AVAILABLE_PAIRS[i]));
            layout.addView(pairChecks[i]);
        }
        TextView expiryTitle = text("ЭКСПИРАЦИЯ", 15, 0xFFE5EDF7, true);
        expiryTitle.setPadding(0, pad, 0, 0);
        layout.addView(expiryTitle);
        RadioGroup expiryGroup = new RadioGroup(this);
        expiryGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton three = new RadioButton(this);
        three.setText("3 минуты"); three.setTextColor(0xFFE5EDF7); three.setId(3003);
        RadioButton five = new RadioButton(this);
        five.setText("5 минут"); five.setTextColor(0xFFE5EDF7); five.setId(3005);
        expiryGroup.addView(three); expiryGroup.addView(five);
        expiryGroup.check(currentSettings.expiryMinutes == 5 ? 3005 : 3003);
        layout.addView(expiryGroup);
        CheckBox sharing = new CheckBox(this);
        sharing.setText("Разрешить двусторонний обмен сигналами");
        sharing.setTextColor(0xFFE5EDF7);
        sharing.setChecked(currentSettings.sharingEnabled);
        layout.addView(sharing);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Настройки анализа")
                .setView(layout).setPositiveButton("СОХРАНИТЬ", null)
                .setNegativeButton("ОТМЕНА", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            java.util.ArrayList<String> selected = new java.util.ArrayList<>();
            for (int i=0;i<pairChecks.length;i++) if (pairChecks[i].isChecked()) selected.add(AVAILABLE_PAIRS[i]);
            if (selected.isEmpty()) {
                log.setText("Нужно выбрать хотя бы одну валютную пару.");
                return;
            }
            int expiry = expiryGroup.getCheckedRadioButtonId() == 3005 ? 5 : 3;
            TradingSettings next = new TradingSettings(selected, expiry, sharing.isChecked());
            dialog.dismiss();
            executor.submit(() -> {
                try {
                    settingsClient.save(authStore.userId(), authStore.accessToken(), next);
                    currentSettings = next;
                    mainHandler.post(() -> {
                        if (!isFinishing()) {
                            status.setText("НАСТРОЙКИ СОХРАНЕНЫ");
                            log.setText("Сохранено для аккаунта: " + authStore.email()
                                    + "\nПары: " + selected + "\nЭкспирация: " + expiry + " мин"
                                    + "\nОбмен сигналами: " + (next.sharingEnabled ? "разрешён" : "выключен"));
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (!isFinishing()) {
                            status.setText("ОШИБКА СОХРАНЕНИЯ");
                            log.setText("Не удалось сохранить настройки: " + safeMessage(e));
                        }
                    });
                }
            });
        }));
        dialog.show();
    }

    private void loadSettingsAfterLogin() {
        if (authStore == null || !authStore.isSignedIn()) return;
        executor.submit(() -> {
            try {
                TradingSettings loaded = settingsClient.load(authStore.userId(), authStore.accessToken());
                currentSettings = loaded;
            } catch (Exception ignored) { currentSettings = TradingSettings.defaults(); }
        });
    }


    private void showSharingDialog() {
        if (authStore == null || !authStore.isSignedIn()) {
            log.setText("Для обмена сигналами сначала войди в аккаунт GOD OF TRADING.");
            return;
        }
        status.setText("ЗАГРУЗКА КОНТАКТОВ...");
        executor.submit(() -> {
            try {
                java.util.List<SupabaseSharingClient.Connection> contacts =
                        sharingClient.connections(authStore.userId(), authStore.accessToken());
                String signals = sharingClient.recentSignals(authStore.userId(), authStore.accessToken());
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    LinearLayout layout = new LinearLayout(this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    int pad = (int)(14 * getResources().getDisplayMetrics().density);
                    layout.setPadding(pad, 0, pad, 0);
                    TextView idLabel = text("ТВОЙ ID ДЛЯ ПРИГЛАШЕНИЯ ДРУГА:\n" + authStore.userId(), 12, 0xFFE5EDF7, false);
                    layout.addView(idLabel);
                    EditText friendId = new EditText(this);
                    friendId.setSingleLine(true);
                    friendId.setHint("UUID аккаунта друга");
                    layout.addView(friendId);
                    TextView contactLabel = text("ПОДКЛЮЧЕНИЯ", 14, 0xFFE5EDF7, true);
                    contactLabel.setPadding(0, pad, 0, 4);
                    layout.addView(contactLabel);
                    for (SupabaseSharingClient.Connection c : contacts) {
                        String state = c.status.equals("accepted") ? "подключён"
                                : c.status.equals("pending") ? (c.incoming(authStore.userId()) ? "входящее приглашение" : "ожидает ответа")
                                : c.status;
                        TextView row = text(c.otherUser(authStore.userId()) + " — " + state, 12, 0xFFB8C6D9, false);
                        layout.addView(row);
                        if (c.status.equals("pending") && c.incoming(authStore.userId())) {
                            Button accept = button("ПРИНЯТЬ ПРИГЛАШЕНИЕ");
                            accept.setOnClickListener(v -> respondToConnection(c.id, "accepted"));
                            layout.addView(accept);
                            Button reject = button("ОТКЛОНИТЬ");
                            reject.setOnClickListener(v -> respondToConnection(c.id, "rejected"));
                            layout.addView(reject);
                        }
                    }
                    TextView historyTitle = text("ПОСЛЕДНИЕ СИГНАЛЫ", 14, 0xFFE5EDF7, true);
                    historyTitle.setPadding(0, pad, 0, 4);
                    layout.addView(historyTitle);
                    TextView history = text(signals.isEmpty() ? "Пока нет переданных сигналов." : signals, 11, 0xFFB8C6D9, false);
                    layout.addView(history);
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Двусторонний обмен")
                            .setView(layout).setPositiveButton("ОТПРАВИТЬ ПРИГЛАШЕНИЕ", null)
                            .setNegativeButton("ЗАКРЫТЬ", null).create();
                    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        String target = friendId.getText().toString().trim();
                        if (target.isEmpty()) {
                            log.setText("Вставь UUID аккаунта друга.");
                            return;
                        }
                        dialog.dismiss();
                        executor.submit(() -> {
                            try {
                                sharingClient.requestConnection(authStore.userId(), target, authStore.accessToken());
                                mainHandler.post(() -> {
                                    if (!isFinishing()) {
                                        status.setText("ПРИГЛАШЕНИЕ ОТПРАВЛЕНО");
                                        log.setText("Запрос отправлен. Друг должен войти в свой аккаунт и принять приглашение.");
                                    }
                                });
                            } catch (Exception e) {
                                mainHandler.post(() -> {
                                    if (!isFinishing()) log.setText("Не удалось отправить приглашение: " + safeMessage(e));
                                });
                            }
                        });
                    }));
                    dialog.show();
                    status.setText("ОБМЕН СИГНАЛАМИ");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        status.setText("ОШИБКА ОБМЕНА");
                        log.setText("Не удалось загрузить подключения: " + safeMessage(e));
                    }
                });
            }
        });
    }

    private void respondToConnection(String connectionId, String response) {
        executor.submit(() -> {
            try {
                sharingClient.respond(connectionId, response, authStore.accessToken());
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        status.setText(response.equals("accepted") ? "ДРУГ ПОДКЛЮЧЁН" : "ПРИГЛАШЕНИЕ ОТКЛОНЕНО");
                        log.setText("Статус подключения обновлён. Открой «ОБМЕН СИГНАЛАМИ», чтобы обновить список.");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing()) log.setText("Не удалось обновить подключение: " + safeMessage(e));
                });
            }
        });
    }

    private void showAuthDialog() {
        if (authStore == null || authClient == null || !authClient.isConfigured()) {
            log.setText("Авторизация пока не настроена: проверь SUPABASE_URL и SUPABASE_ANON_KEY в GitHub Actions Secrets и сборке.");
            return;
        }
        if (authStore.isSignedIn()) {
            new AlertDialog.Builder(this).setTitle("Аккаунт")
                    .setMessage("Вход выполнен: " + authStore.email())
                    .setPositiveButton("ВЫЙТИ", (d, w) -> signOut())
                    .setNegativeButton("ЗАКРЫТЬ", null).show();
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, 0);
        EditText email = new EditText(this);
        email.setSingleLine(true);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setHint("Email");
        fields.addView(email);
        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("Пароль");
        fields.addView(password);
        Button resetPassword = button("ВОССТАНОВИТЬ ПАРОЛЬ");
        resetPassword.setOnClickListener(v -> sendPasswordReset(email.getText().toString()));
        fields.addView(resetPassword);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("GOD OF TRADING — аккаунт")
                .setMessage("Личный аккаунт приложения. Данные брокерских аккаунтов не нужны.")
                .setView(fields).setPositiveButton("ВОЙТИ", null)
                .setNeutralButton("РЕГИСТРАЦИЯ", null)
                .setNegativeButton("GOOGLE", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    runEmailAuth(email.getText().toString(), password.getText().toString(), false, dialog));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    runEmailAuth(email.getText().toString(), password.getText().toString(), true, dialog));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                try {
                    String verifier = createCodeVerifier();
                    String challenge = Base64.encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8")),
                            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                    authStore.saveCodeVerifier(verifier);
                    String url = authClient.googleAuthUrl("com.potraderbot://auth/callback", challenge);
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    dialog.dismiss();
                } catch (Exception e) {
                    log.setText("Не удалось начать вход Google: " + safeMessage(e));
                }
            });
        });
        dialog.show();
    }

    private void runEmailAuth(String emailValue, String passwordValue, boolean register, AlertDialog dialog) {
        String email = emailValue == null ? "" : emailValue.trim();
        String password = passwordValue == null ? "" : passwordValue;
        if (email.isEmpty() || password.length() < 6) {
            log.setText("Укажи корректный Email и пароль не короче 6 символов.");
            return;
        }
        dialog.dismiss();
        status.setText(register ? "РЕГИСТРАЦИЯ..." : "ВХОД...");
        executor.submit(() -> {
            try {
                org.json.JSONObject response = register
                        ? authClient.signUp(email, password) : authClient.signIn(email, password);
                if (response.has("access_token")) { saveAuthResponse(response); loadSettingsAfterLogin(); }
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    if (response.has("access_token")) {
                        status.setText("АККАУНТ ПОДКЛЮЧЁН");
                        log.setText("Вход выполнен: " + authStore.email()
                                + "\nПрофиль приложения создан/проверен через Supabase.");
                    } else {
                        status.setText("ПРОВЕРЬ EMAIL");
                        log.setText("Регистрация принята. Если включено подтверждение Email, открой письмо от Supabase и подтверди адрес.");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        status.setText("ОШИБКА АВТОРИЗАЦИИ");
                        log.setText("Не удалось выполнить запрос: " + safeMessage(e));
                    }
                });
            }
        });
    }


    private void sendPasswordReset(String emailValue) {
        String email = emailValue == null ? "" : emailValue.trim();
        if (email.isEmpty()) {
            log.setText("Сначала введи Email, на который зарегистрирован аккаунт.");
            return;
        }
        status.setText("ОТПРАВКА ПИСЬМА...");
        executor.submit(() -> {
            try {
                authClient.sendPasswordReset(email, "com.potraderbot://auth/callback");
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        status.setText("ПИСЬМО ОТПРАВЛЕНО");
                        log.setText("Если адрес зарегистрирован, Supabase отправит письмо для восстановления пароля. Проверь также папку Спам.");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        status.setText("ОШИБКА ВОССТАНОВЛЕНИЯ");
                        log.setText("Не удалось отправить письмо: " + safeMessage(e));
                    }
                });
            }
        });
    }

    private void signOut() {
        final String token = authStore.accessToken();
        status.setText("ВЫХОД...");
        executor.submit(() -> {
            try { if (!token.isEmpty()) authClient.signOut(token); } catch (Exception ignored) { }
            authStore.clear();
            mainHandler.post(() -> {
                if (!isFinishing()) {
                    status.setText("АККАУНТ ОТКЛЮЧЁН");
                    log.setText("Локальная сессия удалена с этого телефона.");
                }
            });
        });
    }

    private void saveAuthResponse(org.json.JSONObject response) {
        org.json.JSONObject user = response.optJSONObject("user");
        String email = user == null ? "" : user.optString("email", "");
        String userId = user == null ? "" : user.optString("id", "");
        authStore.save(response.optString("access_token", ""),
                response.optString("refresh_token", ""), email, userId);
    }

    private String createCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"com.potraderbot".equals(data.getScheme())
                || !"auth".equals(data.getHost())) return;
        String code = data.getQueryParameter("code");
        if (code == null || code.isEmpty()) {
            log.setText("Google не вернул код авторизации. Проверь Redirect URL в Supabase.");
            return;
        }
        String verifier = authStore == null ? "" : authStore.codeVerifier();
        status.setText("ЗАВЕРШЕНИЕ ВХОДА GOOGLE...");
        executor.submit(() -> {
            try {
                org.json.JSONObject response = authClient.exchangeCode(code, verifier);
                saveAuthResponse(response);
                loadSettingsAfterLogin();
                authStore.saveCodeVerifier("");
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        status.setText("АККАУНТ ПОДКЛЮЧЁН");
                        log.setText("Вход через Google выполнен: " + authStore.email());
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        status.setText("ОШИБКА GOOGLE");
                        log.setText("Не удалось завершить вход Google: " + safeMessage(e)
                                + "\nПроверь callback URL com.potraderbot://auth/callback в Supabase.");
                    }
                });
            }
        });
    }

    private void showApiKeyDialog() {
        if (apiKeyStore == null) {
            log.setText("Защищённое хранилище недоступно на этом устройстве. Обнови Android и попробуй снова.");
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, 0);

        TextView twelveLabel = text("Twelve Data API key", 14, 0xFFE5EDF7, true);
        fields.addView(twelveLabel);
        EditText twelveInput = new EditText(this);
        twelveInput.setSingleLine(true);
        twelveInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        twelveInput.setHint("Вставь ключ Twelve Data");
        twelveInput.setText(apiKeyStore.getTwelveDataKey());
        fields.addView(twelveInput);

        TextView apiShLabel = text("API.SH key", 14, 0xFFE5EDF7, true);
        apiShLabel.setPadding(0, pad, 0, 0);
        fields.addView(apiShLabel);
        EditText apiShInput = new EditText(this);
        apiShInput.setSingleLine(true);
        apiShInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiShInput.setHint("Вставь ключ API.SH");
        apiShInput.setText(apiKeyStore.getApiShKey());
        fields.addView(apiShInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Защищённые API-ключи")
                .setMessage("Ключи сохраняются на этом телефоне в зашифрованном виде и не сбрасываются при закрытии приложения.")
                .setView(fields)
                .setPositiveButton("СОХРАНИТЬ", null)
                .setNeutralButton("УДАЛИТЬ КЛЮЧИ", null)
                .setNegativeButton("ОТМЕНА", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String twelve = twelveInput.getText().toString().trim();
                String apiSh = apiShInput.getText().toString().trim();
                if (twelve.isEmpty() || apiSh.isEmpty()) {
                    log.setText("Введи оба ключа. Они нужны для котировок и экономического календаря.");
                    return;
                }
                apiKeyStore.save(twelve, apiSh);
                log.setText("API-ключи сохранены в зашифрованном хранилище телефона.");
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                apiKeyStore.clear();
                log.setText("API-ключи удалены с этого телефона.");
                dialog.dismiss();
            });
        });
        dialog.show();
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
