package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.widget.*;
import android.view.View;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.*;

public final class SportActivity extends Activity {
    private static final int BG=0xff101014,GOLD=0xffdfb65a,PANEL=0xff222228;
    private static final String ALIAS="gos_api_sports_v1";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView status;
    private LinearLayout results;
    private Button load,settings;
    private boolean busy;
    private int remaining=-1;
    private JSONObject shownFixtures;
    private EditText search;
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(8),dp(8),dp(8),dp(8));return t;}
    private Button button(String value){Button b=new Button(this);b.setText(value);b.setTextColor(GOLD);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("gos_local",MODE_PRIVATE);
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(16),dp(16),dp(16),dp(16));page.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);setContentView(scroll);
        Button back=button("‹  GoT 2.0");back.setOnClickListener(v->finish());page.addView(back);
        page.addView(label("GoS  •  GOD OF SPORT AI",23,GOLD));
        page.addView(label("Футбол • календарь и результаты • API-SPORTS",14,Color.WHITE));
        Button stats=button("СТАТИСТИЧЕСКАЯ МОДЕЛЬ");stats.setOnClickListener(v->statistics());page.addView(stats);
        settings=button("НАСТРОЙКИ API-SPORTS");settings.setOnClickListener(v->settings());page.addView(settings);
        load=button("МАТЧИ НА СЕГОДНЯ");load.setOnClickListener(v->loadFixtures());page.addView(load);
        status=label("Лимит тарифа: 100 запросов в сутки. Запросы только по нажатию.",13,GOLD);page.addView(status);
        search=new EditText(this);search.setSingleLine(true);search.setHint("Поиск команды или чемпионата");search.setTextColor(Color.WHITE);search.setHintTextColor(GOLD);page.addView(search);
        results=new LinearLayout(this);results.setOrientation(1);page.addView(results);
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence t,int start,int count,int after){}
            public void onTextChanged(CharSequence t,int start,int before,int count){if(shownFixtures!=null)render(shownFixtures);}
            public void afterTextChanged(android.text.Editable e){}
        });
        String cached=prefs.getString("fixtures_cache","");
        if(!cached.isEmpty())try{render(new JSONObject(cached));status.setText("Сохранённые данные: "+prefs.getString("cache_date","")+"; не обновлены.");}catch(Exception ignored){}
    }
    private void statistics(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(12),dp(8),dp(12),dp(8));
        form.addView(label("Учебная модель Пуассона. Введите ожидаемые средние, рассчитанные по независимой исторической выборке. Без данных прогноз не строится. Не учитывает зависимость событий и не подтверждена на реальных матчах.",13,Color.WHITE));
        String[] names={"Голы хозяев за матч","Голы гостей за матч","Голы хозяев в первом тайме","Голы гостей в первом тайме","Средний суммарный счёт угловых","Средний суммарный счёт жёлтых карточек"};
        EditText[] fields=new EditText[names.length];
        for(int i=0;i<names.length;i++){form.addView(label(names[i],13,GOLD));EditText f=new EditText(this);f.setSingleLine(true);f.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);f.setHint("Нет данных");fields[i]=f;form.addView(f);}
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("GoS — статистическая модель").setView(scroll).setPositiveButton("Рассчитать",null).setNegativeButton("Закрыть",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                double[] x=new double[fields.length];for(int i=0;i<x.length;i++){String value=fields[i].getText().toString().trim().replace(',','.');if(value.isEmpty()){fields[i].setError("Введите среднее");return;}x[i]=Double.parseDouble(value);}
                if(x[2]>x[0]||x[3]>x[1])throw new IllegalArgumentException();
                StringBuilder out=new StringBuilder("Условные вероятности модели, не проверенные прогнозы.\n");
                out.append("\nПервый тайм: ожидаемые голы ").append(String.format(Locale.US,"%.2f",x[2]+x[3]));
                out.append("\nХотя бы один гол: ").append(percent(SportStatistics.above(x[2]+x[3],0)));
                out.append("\nБез голов: ").append(percent(1-SportStatistics.above(x[2]+x[3],0)));
                out.append("\nДва и более: ").append(percent(SportStatistics.above(x[2]+x[3],1)));
                out.append("\nТри и более: ").append(percent(SportStatistics.above(x[2]+x[3],2)));
                out.append("\nОбе забьют в первом тайме: ").append(percent(SportStatistics.bothScore(x[2],x[3])));
                out.append("\n\nОбе забьют за матч: ").append(percent(SportStatistics.bothScore(x[0],x[1])));
                out.append("\n\nУгловые: ожидаемое количество ").append(String.format(Locale.US,"%.2f",x[4]));
                out.append("\nЖёлтые карточки: ожидаемое количество ").append(String.format(Locale.US,"%.2f",x[5]));
                // Validate count means as well; do not treat raw values as measured statistics.
                SportStatistics.above(x[4],0);SportStatistics.above(x[5],0);
                new AlertDialog.Builder(this).setTitle("Результаты модели").setMessage(out.toString()).setPositiveButton("ОК",null).show();
            }catch(Exception e){new AlertDialog.Builder(this).setMessage("Проверьте средние: числа 0–50, значения первого тайма не выше полного матча.").setPositiveButton("ОК",null).show();}
        }));dialog.show();
    }
    private String percent(double value){return String.format(Locale.US,"%.1f%%",value*100);}
    private SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    private void saveKey(String token)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        String iv=Base64.encodeToString(c.getIV(),Base64.NO_WRAP),enc=Base64.encodeToString(c.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString("iv",iv).putString("cipher",enc).remove("fixtures_cache").remove("cache_date").commit())throw new Exception();
        remaining=-1;clearHistoryCache();shownFixtures=null;
    }
    private void clearHistoryCache(){SharedPreferences.Editor e=prefs.edit();for(String k:prefs.getAll().keySet())if(k.startsWith("history_")&&!k.startsWith("history_budget_"))e.remove(k);e.apply();}
    private String readKey()throws Exception{
        String enc=prefs.getString("cipher","");if(enc.isEmpty())return "";
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(enc,Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    private void settings(){
        EditText field=new EditText(this);field.setSingleLine(true);field.setHint("API-SPORTS key");field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("API-SPORTS").setMessage("Ключ хранится на этом телефоне в зашифрованном виде. Не отправляется на сервер GoT.").setView(field)
        .setPositiveButton("Сохранить",(d,w)->{String token=field.getText().toString().trim();if(!token.matches("[A-Za-z0-9_-]{16,128}")){status.setText("Проверьте формат ключа.");return;}try{saveKey(token);results.removeAllViews();status.setText("Ключ сохранён. Нажмите «Матчи на сегодня» для проверки.");}catch(Exception e){status.setText("Не удалось сохранить ключ.");}})
        .setNeutralButton("Удалить",(d,w)->{prefs.edit().remove("iv").remove("cipher").remove("fixtures_cache").remove("cache_date").apply();remaining=-1;clearHistoryCache();shownFixtures=null;results.removeAllViews();status.setText("Ключ удалён.");})
        .setNegativeButton("Отмена",null).show();
    }
    private String today(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private void loadFixtures(){
        if(busy)return;
        final String date=today();
        final long now=System.currentTimeMillis();
        if(date.equals(prefs.getString("cache_date",""))&&now-prefs.getLong("cache_time",0)>=0&&now-prefs.getLong("cache_time",0)<900000){
            try{render(new JSONObject(prefs.getString("fixtures_cache","")));status.setText("Кэш (15 минут). Новый запрос не израсходован.");return;}catch(Exception ignored){}
        }
        if(now-prefs.getLong("last_attempt",0)<60000){status.setText("Повторный запрос доступен через минуту.");return;}
        busy=true;load.setEnabled(false);settings.setEnabled(false);
        worker.execute(()->{
            String message;JSONObject data=null;
            HttpsURLConnection conn=null;
            try{
                String token=readKey();if(token.isEmpty())throw new IllegalStateException("Сначала сохраните API-ключ.");
                if(remaining==0)throw new IllegalStateException("Суточный лимит исчерпан. Проверьте лимит в кабинете API-SPORTS.");
                prefs.edit().putLong("last_attempt",System.currentTimeMillis()).commit();
                conn=(HttpsURLConnection)new URL("https://v3.football.api-sports.io/fixtures?date="+date+"&timezone=UTC").openConnection();
                conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(20000);conn.setRequestProperty("x-apisports-key",token);
                int code=conn.getResponseCode();String quota=conn.getHeaderField("x-ratelimit-requests-remaining");
                try{remaining=Integer.parseInt(quota);}catch(Exception ignored){remaining=-1;}
                if(code!=200)throw new IllegalStateException("API вернул HTTP "+code+". Проверьте ключ и тариф.");
                try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>8_000_000)throw new IllegalStateException("Слишком большой ответ API.");out.write(buf,0,n);}
                    data=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                }
                Object err=data.opt("errors");if((err instanceof JSONObject&&((JSONObject)err).length()>0)||(err instanceof JSONArray&&((JSONArray)err).length()>0))throw new IllegalStateException("API сообщил об ошибке. Проверьте доступные сезоны, ключ и лимит.");
                if(data.optJSONArray("response")==null)throw new IllegalStateException("Нет списка матчей в ответе.");
                prefs.edit().putString("fixtures_cache",data.toString()).putString("cache_date",date).putLong("cache_time",System.currentTimeMillis()).apply();
                message="Данные за "+date+" (UTC). Осталось запросов: "+(remaining>=0?String.valueOf(remaining):"неизвестно");
            }catch(IllegalStateException e){data=null;message=e.getMessage();}catch(Exception e){data=null;message="Не удалось получить данные. Проверьте интернет и ключ.";}finally{if(conn!=null)conn.disconnect();}
            final JSONObject result=data;final String msg=message;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);status.setText(msg);if(result!=null)render(result);});
        });
    }
    private void render(JSONObject data){
        shownFixtures=data;results.removeAllViews();JSONArray list=data.optJSONArray("response");if(list==null)return;
        results.addView(label("Матчей в ответе: "+list.length()+" • поиск по всему списку",14,GOLD));
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);int shown=0;
        for(int i=0;i<list.length()&&shown<200;i++){
            JSONObject item=list.optJSONObject(i);if(item==null)continue;
            JSONObject teams=item.optJSONObject("teams"),fixture=item.optJSONObject("fixture"),league=item.optJSONObject("league"),goals=item.optJSONObject("goals");
            if(teams==null||fixture==null)continue;
            JSONObject h=teams.optJSONObject("home"),a=teams.optJSONObject("away");if(h==null||a==null)continue;
            String title=h.optString("name")+" — "+a.optString("name");
            String leagueName=league==null?"":league.optString("name");
            if(!(title+" "+leagueName).toLowerCase(Locale.ROOT).contains(query))continue;
            shown++;
            JSONObject st=fixture.optJSONObject("status");
            String score=goals!=null&&!goals.isNull("home")&&!goals.isNull("away")?" • "+goals.optString("home")+" : "+goals.optString("away"):"";
            TextView row=label(h.optString("name")+" — "+a.optString("name")+score+"\n"+(league==null?"":league.optString("name"))+" • "+fixture.optString("date")+" • "+(st==null?"":st.optString("short")),14,Color.WHITE);
            row.setBackgroundColor(PANEL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(6);results.addView(row,lp);
            row.setOnClickListener(v->openMatch(item));
        }
    }
    private void openMatch(JSONObject item){
        JSONObject teams=item.optJSONObject("teams"),fixture=item.optJSONObject("fixture");
        if(teams==null||fixture==null)return;
        JSONObject h=teams.optJSONObject("home"),a=teams.optJSONObject("away");
        if(h==null||a==null)return;
        int home=h.optInt("id",-1),away=a.optInt("id",-1);long cutoff=fixture.optLong("timestamp",0);
        String title=h.optString("name")+" — "+a.optString("name");
        if(home<=0||away<=0||cutoff<=0){status.setText("Недостаточно данных о матче.");return;}
        new AlertDialog.Builder(this).setTitle(title).setMessage("Дата: "+fixture.optString("date")+"\nЗагрузить историю команд? До двух запросов API, кэш на 6 часов.").setNegativeButton("Закрыть",null).setPositiveButton("Статистика",(d,w)->loadHistory(title,home,away,cutoff)).show();
    }
    private JSONObject teamHistory(int team,String token)throws Exception{
        String cache="history_"+team;long now=System.currentTimeMillis(),age=now-prefs.getLong(cache+"_time",0);
        if(age>=0&&age<21600000){String saved=prefs.getString(cache,"");if(!saved.isEmpty())return new JSONObject(saved);}
        String day=today();
        if(!day.equals(prefs.getString("history_budget_day","")))prefs.edit().putString("history_budget_day",day).putInt("history_budget_count",0).commit();
        int used=prefs.getInt("history_budget_count",0);
        if(used>=80||remaining==0)throw new IllegalStateException("Лимит запросов истории исчерпан.");
        if(!prefs.edit().putInt("history_budget_count",used+1).commit())throw new IllegalStateException("Не удалось сохранить счётчик.");
        HttpsURLConnection conn=(HttpsURLConnection)new URL("https://v3.football.api-sports.io/fixtures?team="+team+"&last=20&status=FT").openConnection();
        try{
            conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(20000);conn.setRequestProperty("x-apisports-key",token);
            int code=conn.getResponseCode();try{remaining=Integer.parseInt(conn.getHeaderField("x-ratelimit-requests-remaining"));}catch(Exception ignored){remaining=-1;}
            if(code!=200)throw new IllegalStateException("API HTTP "+code);
            JSONObject data;
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>4000000)throw new IllegalStateException("Ответ слишком большой.");out.write(buf,0,n);}
                data=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
            Object err=data.opt("errors");
            if((err instanceof JSONObject&&((JSONObject)err).length()>0)||(err instanceof JSONArray&&((JSONArray)err).length()>0)||data.optJSONArray("response")==null)throw new IllegalStateException("История недоступна на текущем тарифе или API вернул ошибку.");
            prefs.edit().putString(cache,data.toString()).putLong(cache+"_time",System.currentTimeMillis()).apply();return data;
        }finally{conn.disconnect();}
    }
    private void loadHistory(String title,int home,int away,long cutoff){
        if(busy)return;busy=true;load.setEnabled(false);settings.setEnabled(false);status.setText("Загрузка истории команд…");
        worker.execute(()->{
            String output;
            try{
                String token=readKey();if(token.isEmpty())throw new IllegalStateException("Сначала сохраните API-ключ.");
                SportHistory.Summary h=SportHistory.summarize(teamHistory(home,token).getJSONArray("response"),home,cutoff);
                SportHistory.Summary a=SportHistory.summarize(teamHistory(away,token).getJSONArray("response"),away,cutoff);
                output="Историческая статистика до начала матча. Не прогноз.\n\nХозяева\n"+describe(h)+"\n\nГости\n"+describe(a)+"\n\nУгловые и ЖК: нужны отдельные данные статистики матчей.";
            }catch(IllegalStateException e){output=e.getMessage();}catch(Exception e){output="Не удалось получить историю. Проверьте интернет и тариф.";}
            final String msg=output;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);status.setText("Осталось запросов по API: "+(remaining<0?"неизвестно":remaining));new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("ОК",null).show();});
        });
    }
    private String describe(SportHistory.Summary s){
        if(s.n==0)return "Недостаточно завершённых матчей.";
        return "Матчей: "+s.n+"\nПобеды / ничьи / поражения: "+s.w+" / "+s.d+" / "+s.l+"\nСредние голы забито / пропущено: "+String.format(Locale.US,"%.2f / %.2f",s.gf/(double)s.n,s.ga/(double)s.n)+"\nОЗ в истории: "+s.both+"/"+s.n+" ("+percent(s.both/(double)s.n)+")\nГол в первом тайме: "+(s.htN==0?"нет данных":s.htGoal+"/"+s.htN+" ("+percent(s.htGoal/(double)s.htN)+")");
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
