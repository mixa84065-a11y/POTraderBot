package com.potraderbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SessionStore {
    private static final String PREF = "got_session";
    private static final String VALUE = "encrypted";
    private static final String ALIAS = "god_of_trading_2_session_key";
    private SessionStore() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    public static synchronized void save(Context context, String access, String refresh) throws Exception {
        String raw = access + "\n" + refresh;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(VALUE, Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
    }

    public static synchronized String[] load(Context context) {
        try {
            String packedText = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(VALUE, null);
            if (packedText == null) return null;
            byte[] packed = Base64.decode(packedText, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[packed.length - 12];
            System.arraycopy(packed, 0, iv, 0, 12);
            System.arraycopy(packed, 12, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            String raw = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\n", 2);
            return parts.length == 2 ? parts : null;
        } catch (Exception e) {
            clear(context);
            return null;
        }
    }

    private static org.json.JSONObject claims(String token){
        try{return new org.json.JSONObject(new String(Base64.decode(token.split("\\.")[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8));}
        catch(Exception e){return new org.json.JSONObject();}
    }
    static boolean sameSession(String a,String b){
        if(a==null||b==null||a.isEmpty()||b.isEmpty())return false;
        if(a.equals(b))return true;
        org.json.JSONObject x=claims(a),y=claims(b);String id=x.optString("session_id","");
        return !id.isEmpty()&&id.equals(y.optString("session_id",""))&&!x.optString("sub","").isEmpty()&&x.optString("sub","").equals(y.optString("sub",""));
    }
    static boolean isExpired(String error){
        String e=error==null?"":error.toLowerCase(java.util.Locale.ROOT);
        return e.contains("jwt expired")||e.contains("token expired")||e.contains("pgrst301")||e.contains("invalid jwt");
    }
    static synchronized String[] fresh(Context context,String expected,boolean force)throws Exception{
        String[] current=load(context);
        if(current==null||!sameSession(expected,current[0]))throw new Exception("Сессия истекла. Войдите снова.");
        long exp=claims(current[0]).optLong("exp",0);
        if(!current[0].equals(expected)&&exp>System.currentTimeMillis()/1000+60)return current;
        if(!force&&exp>System.currentTimeMillis()/1000+60)return current;
        if(current[1].isEmpty())throw new Exception("Сессия истекла. Войдите снова.");
        org.json.JSONObject refreshed=SupabaseApi.refresh(current[1]);
        String a=refreshed.optString("access_token",""),r=refreshed.optString("refresh_token","");
        if(a.isEmpty()||r.isEmpty()||!sameSession(current[0],a))throw new Exception("Не удалось обновить сессию.");
        save(context,a,r);return new String[]{a,r};
    }
    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
