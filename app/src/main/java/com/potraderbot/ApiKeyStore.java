package com.potraderbot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/** Encrypted per-install API credential storage backed by Android Keystore. */
public final class ApiKeyStore {
    private static final String PREFS = "god_of_trading_secure_keys";
    private static final String TWELVE = "twelve_data";
    private static final String API_SH = "api_sh";
    private final SharedPreferences preferences;

    public ApiKeyStore(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            preferences = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось открыть защищённое хранилище ключей", error);
        }
    }

    public String getTwelveDataKey() {
        return preferences.getString(TWELVE, "");
    }

    public String getApiShKey() {
        return preferences.getString(API_SH, "");
    }

    public void save(String twelveDataKey, String apiShKey) {
        preferences.edit()
                .putString(TWELVE, clean(twelveDataKey))
                .putString(API_SH, clean(apiShKey))
                .apply();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
