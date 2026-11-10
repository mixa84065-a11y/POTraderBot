package com.potraderbot;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/** Encrypted per-device storage for Supabase session tokens. */
public final class SupabaseAuthStore {
    private static final String PREFS = "god_of_trading_auth";
    private final SharedPreferences prefs;

    public SupabaseAuthStore(Context context) {
        try {
            MasterKey key = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            prefs = EncryptedSharedPreferences.create(context.getApplicationContext(), PREFS,
                    key, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось открыть защищённое хранилище сессии", e);
        }
    }

    public void save(String accessToken, String refreshToken, String email, String userId) {
        prefs.edit().putString("access_token", clean(accessToken))
                .putString("refresh_token", clean(refreshToken))
                .putString("email", clean(email)).putString("user_id", clean(userId)).apply();
    }
    public String accessToken() { return prefs.getString("access_token", ""); }
    public String refreshToken() { return prefs.getString("refresh_token", ""); }
    public String email() { return prefs.getString("email", ""); }
    public String userId() { return prefs.getString("user_id", ""); }
    public boolean isSignedIn() { return !accessToken().isEmpty(); }
    public void clear() { prefs.edit().clear().apply(); }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
