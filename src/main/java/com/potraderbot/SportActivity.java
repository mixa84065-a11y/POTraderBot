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
    private volatile int remaining=-1;
    static final Object QUOTA_LOCK=new Object();
    private JSONObject shownFixtures;
    private EditText search;
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(8),dp(8),dp(8),dp(8));return t;}
    private Button button(String value){Button b=new Button(this);b.setText(value);b.setTextColor(GOLD);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("gos_local",MODE_PRIVATE);
        remaining=storedRemaining();
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(16),dp(16),dp(16),dp(16));page.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);setContentView(scroll);
        Button back=button("‹  GoT 2.0");back.setOnClickListener(v->finish());page.addView(back);
        page.addView(label("GoS  •  GOD OF SPORT AI",23,GOLD));
        page.addView(label("Футбол • календарь и результаты • API-SPORTS",14,Color.WHITE));
        Button stats=button("СТАТИСТИЧЕСКАЯ МОДЕЛЬ");stats.setOnClickListener(v->statistics());page.addView(stats);
        Button journal=button("ЖУРНАЛ ПРОГНОЗОВ");journal.setOnClickListener(v->showJournal());page.addView(journal);
        Button sync=button(prefs.getBoolean("gos_auto_sync",false)?"ФОНОВАЯ СВЕРКА: ВКЛ":"ФОНОВАЯ СВЕРКА: ВЫКЛ");page.addView(sync);
        sync.setOnClickListener(v->{
            if(prefs.getBoolean("gos_auto_sync",false)){
                prefs.edit().putBoolean("gos_auto_sync",false).commit();SportSyncJob.cancel(this);sync.setText("ФОНОВАЯ СВЕРКА: ВЫКЛ");return;
            }
            new AlertDialog.Builder(this).setTitle("Фоновая сверка").setMessage("До двух матчей за запуск, не более восьми запросов в сутки. Только при подключении к интернету. Android выбирает время запуска. Ключ остаётся на телефоне.").setNegativeButton("Отмена",null).setPositiveButton("Включить",(d,w)->{
                if(prefs.edit().putBoolean("gos_auto_sync",true).commit()&&SportSyncJob.schedule(this)){sync.setText("ФОНОВАЯ СВЕРКА: ВКЛ");}
                else{prefs.edit().putBoolean("gos_auto_sync",false).commit();status.setText("Не удалось запланировать сверку.");}
            }).show();
        });
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
        Button source=button("SSTATS • ПРОВЕРКА ИСТОЧНИКА");source.setOnClickListener(v->probeSStats());page.addView(source,Math.max(0,page.indexOfChild(results)));
        Button local=button("ЛОКАЛЬНАЯ БАЗА GoS");local.setOnClickListener(v->localInfo());page.addView(local,Math.max(0,page.indexOfChild(results)));
        String cached=prefs.getString("fixtures_cache","");
        if(!cached.isEmpty())try{JSONObject initial=new JSONObject(cached);render(initial);worker.execute(()->storeArchive(initial.optJSONArray("response")));status.setText("Сохранённые данные: "+prefs.getString("cache_date","")+"; не обновлены.");}catch(Exception ignored){}
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
        remaining=storedRemaining();clearHistoryCache();shownFixtures=null;
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
        .setNeutralButton("Удалить",(d,w)->{prefs.edit().remove("iv").remove("cipher").remove("fixtures_cache").remove("cache_date").apply();remaining=storedRemaining();clearHistoryCache();shownFixtures=null;results.removeAllViews();status.setText("Ключ удалён.");})
        .setNegativeButton("Отмена",null).show();
    }
    private String today(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private int storedRemaining(){return SportSyncJob.remaining(prefs);}
    private void reserveRequest()throws Exception{SportSyncJob.reserve(prefs,false);remaining=storedRemaining();}
    private void updateQuota(String header){SportSyncJob.updateQuota(prefs,header);remaining=storedRemaining();}
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
                reserveRequest();
                prefs.edit().putLong("last_attempt",System.currentTimeMillis()).commit();
                conn=(HttpsURLConnection)new URL("https://v3.football.api-sports.io/fixtures?date="+date+"&timezone=UTC").openConnection();
                conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(20000);conn.setRequestProperty("x-apisports-key",token);
                int code=conn.getResponseCode();String quota=conn.getHeaderField("x-ratelimit-requests-remaining");
                updateQuota(quota);
                if(code!=200)throw new IllegalStateException("API вернул HTTP "+code+". Проверьте ключ и тариф.");
                try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>8_000_000)throw new IllegalStateException("Слишком большой ответ API.");out.write(buf,0,n);}
                    data=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                }
                Object err=data.opt("errors");if((err instanceof JSONObject&&((JSONObject)err).length()>0)||(err instanceof JSONArray&&((JSONArray)err).length()>0))throw new IllegalStateException("API сообщил об ошибке. Проверьте доступные сезоны, ключ и лимит.");
                if(data.optJSONArray("response")==null)throw new IllegalStateException("Нет списка матчей в ответе.");
                prefs.edit().putString("fixtures_cache",data.toString()).putString("cache_date",date).putLong("cache_time",System.currentTimeMillis()).apply();
                int stored=storeArchive(data.optJSONArray("response"));
                message="Данные за "+date+" (UTC). Осталось запросов: "+(remaining>=0?String.valueOf(remaining):"неизвестно")+"\nЛокальная база: "+(stored<0?"ошибка записи":stored+" завершённых матчей");
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
        long fixtureId=fixture.optLong("id",0);
        String[] options={"Анализ из локальной базы (0 запросов)","Анализ и личные встречи (до 3 запросов)","История по датам • Free (до 14 запросов)","Угловые и ЖК (до 8 запросов)","Составы и травмы (до 2 запросов)"};
        new AlertDialog.Builder(this).setTitle(title+"\n"+fixture.optString("date")).setItems(options,(d,w)->{
            if(w==0)localAnalysis(title,home,away,cutoff,fixtureId);
            else if(w==1)loadHistory(title,home,away,cutoff,fixtureId);
            else if(w==2)archiveHistory(title,home,away,cutoff,fixtureId);
            else if(w==3)loadDetails(title,home,away,cutoff);
            else if(fixtureId>0)loadSquads(title,fixtureId);
            else status.setText("Нет идентификатора матча.");
        }).setNegativeButton("Закрыть",null).show();
    }
    private JSONObject teamHistory(int team,String token)throws Exception{
        return historicalRequest("history_"+team,"fixtures?team="+team+"&last=20&status=FT",token);
    }
    private JSONObject headToHead(int home,int away,String token)throws Exception{
        return historicalRequest("history_h2h_"+Math.min(home,away)+"_"+Math.max(home,away),"fixtures/headtohead?h2h="+home+"-"+away+"&last=10&status=FT",token);
    }
    private JSONObject historicalRequest(String cache,String endpoint,String token)throws Exception{
        return cachedRequest(cache,endpoint,token,21600000);
    }
    private JSONObject cachedRequest(String cache,String endpoint,String token,long ttl)throws Exception{
        long now=System.currentTimeMillis(),age=now-prefs.getLong(cache+"_time",0);
        if(age>=0&&age<ttl){String saved=prefs.getString(cache,"");if(!saved.isEmpty())return new JSONObject(saved);}
        reserveRequest();
        HttpsURLConnection conn=(HttpsURLConnection)new URL("https://v3.football.api-sports.io/"+endpoint).openConnection();
        try{
            conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(20000);conn.setRequestProperty("x-apisports-key",token);
            int code=conn.getResponseCode();updateQuota(conn.getHeaderField("x-ratelimit-requests-remaining"));
            if(code!=200)throw new IllegalStateException("API HTTP "+code);
            JSONObject data;
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>4000000)throw new IllegalStateException("Ответ слишком большой.");out.write(buf,0,n);}
                data=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
            Object err=data.opt("errors");
            if((err instanceof JSONObject&&((JSONObject)err).length()>0)||(err instanceof JSONArray&&((JSONArray)err).length()>0))throw new IllegalStateException(SportArchive.errorCategory(err));
            if(data.optJSONArray("response")==null)throw new IllegalStateException("API не вернул список данных.");
            prefs.edit().putString(cache,data.toString()).putLong(cache+"_time",System.currentTimeMillis()).apply();return data;
        }finally{conn.disconnect();}
    }
    private void loadSquads(String title,long id){
        if(busy)return;busy=true;load.setEnabled(false);settings.setEnabled(false);status.setText("Загрузка составов и травм…");
        worker.execute(()->{
            StringBuilder out=new StringBuilder("Данные API на момент загрузки. Составы могут публиковаться незадолго до матча. Пустой ответ не означает отсутствие травм.\n");
            try{
                String token=readKey();if(token.isEmpty())throw new IllegalStateException("Сначала сохраните API-ключ.");
                try{out.append("\nСОСТАВЫ\n").append(SportSquads.lineups(cachedRequest("history_lineups_"+id,"fixtures/lineups?fixture="+id,token,600000).getJSONArray("response")));}catch(Exception e){out.append("\nСоставы: данные недоступны.");}
                try{out.append("\n\nТРАВМЫ И ОТСУТСТВУЮЩИЕ\n").append(SportSquads.injuries(cachedRequest("history_injuries_"+id,"injuries?fixture="+id,token,1800000).getJSONArray("response")));}catch(Exception e){out.append("\nТравмы: данные недоступны.");}
            }catch(Exception e){out.append("\nСначала проверьте API-ключ.");}
            String msg=out.toString();runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);status.setText("Осталось запросов по API: "+(remaining<0?"неизвестно":remaining));new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("ОК",null).show();});
        });
    }
    private JSONObject fixtureDetails(long id,String token)throws Exception{
        String cache="history_detail_"+id;String saved=prefs.getString(cache,"");
        if(!saved.isEmpty())return new JSONObject(saved);
        reserveRequest();
        HttpsURLConnection conn=(HttpsURLConnection)new URL("https://v3.football.api-sports.io/fixtures/statistics?fixture="+id).openConnection();
        try{
            conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(20000);conn.setRequestProperty("x-apisports-key",token);
            int code=conn.getResponseCode();updateQuota(conn.getHeaderField("x-ratelimit-requests-remaining"));
            if(code!=200)throw new IllegalStateException("API HTTP "+code);
            JSONObject data;
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>1000000)throw new IllegalStateException("Ответ слишком большой.");out.write(buf,0,n);}
                data=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
            Object err=data.opt("errors");
            if((err instanceof JSONObject&&((JSONObject)err).length()>0)||(err instanceof JSONArray&&((JSONArray)err).length()>0)||data.optJSONArray("response")==null)throw new IllegalStateException("Подробная статистика недоступна на текущем тарифе.");
            if(data.getJSONArray("response").length()>0)prefs.edit().putString(cache,data.toString()).apply();
            return data;
        }finally{conn.disconnect();}
    }
    private void loadDetails(String title,int home,int away,long cutoff){
        if(busy)return;
        new AlertDialog.Builder(this).setTitle("Подробная история").setMessage("До 8 запросов: история двух команд и статистика до трёх завершённых матчей каждой. Кэш сокращает расход.").setNegativeButton("Отмена",null).setPositiveButton("Загрузить",(d,w)->{
            if(busy)return;busy=true;load.setEnabled(false);settings.setEnabled(false);status.setText("Загрузка угловых и карточек…");
            worker.execute(()->{
                String output;
                try{
                    String token=readKey();if(token.isEmpty())throw new IllegalStateException("Сначала сохраните API-ключ.");
                    SportDetailHistory.Summary h=new SportDetailHistory.Summary(),a=new SportDetailHistory.Summary();
                    for(long id:SportDetailHistory.recentIds(teamHistory(home,token).getJSONArray("response"),home,cutoff))h.add(fixtureDetails(id,token).getJSONArray("response"),home);
                    for(long id:SportDetailHistory.recentIds(teamHistory(away,token).getJSONArray("response"),away,cutoff))a.add(fixtureDetails(id,token).getJSONArray("response"),away);
                    output="Исторические средние, не прогноз. До трёх матчей на команду; выборка мала.\n\nХозяева\n"+describeDetails(h)+"\n\nГости\n"+describeDetails(a);
                }catch(IllegalStateException e){output=e.getMessage();}catch(Exception e){output="Не удалось получить подробные данные. Проверьте тариф и интернет.";}
                final String msg=output;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);status.setText("Осталось запросов по API: "+(remaining<0?"неизвестно":remaining));new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("ОК",null).show();});
            });
        }).show();
    }
    private String describeDetails(SportDetailHistory.Summary s){
        return "Угловые: "+(s.cornersN==0?"нет данных":String.format(Locale.US,"%.2f своих / %.2f соперника (%d матчей)",s.cornersFor/(double)s.cornersN,s.cornersAgainst/(double)s.cornersN,s.cornersN))+
        "\nЖК: "+(s.cardsN==0?"нет данных":String.format(Locale.US,"%.2f своих / %.2f соперника (%d матчей)",s.cardsFor/(double)s.cardsN,s.cardsAgainst/(double)s.cardsN,s.cardsN));
    }
    private void probeSStats(){
        if(busy)return;
        new AlertDialog.Builder(this).setTitle("SStats.net — проверка доступа")
          .setMessage("Два отдельных тестовых запроса без ключа: список лиг и архив сезона из официального примера (лига 41, 2023). Лимит API-SPORTS не расходуется. Данные разных источников пока не объединяются.")
          .setNegativeButton("Отмена",null).setPositiveButton("Проверить",(d,w)->{
            if(busy)return;
            long now=System.currentTimeMillis();
            if(now-prefs.getLong("sstats_last_probe",0)<60000){status.setText("Повторная проверка SStats доступна через минуту.");return;}
            if(!prefs.edit().putLong("sstats_last_probe",now).commit()){status.setText("Не удалось сохранить ограничение запросов.");return;}
            busy=true;load.setEnabled(false);settings.setEnabled(false);status.setText("Проверка SStats…");
            worker.execute(()->{
                String msg=SportSStatsProbe.check();
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);status.setText("Проверка SStats завершена.");new AlertDialog.Builder(this).setTitle("SStats.net").setMessage(msg).setPositiveButton("ОК",null).show();});
            });
          }).show();
    }
    private int storeArchive(JSONArray rows){
        if(rows==null)return -1;
        try(SportLocalArchive db=new SportLocalArchive(getApplicationContext())){db.ingest(rows);return db.count();}catch(Exception e){return -1;}
    }
    private void localInfo(){
        if(busy)return;
        busy=true;load.setEnabled(false);settings.setEnabled(false);
        worker.execute(()->{
            String msg;
            try(SportLocalArchive db=new SportLocalArchive(getApplicationContext())){msg="Завершённых матчей: "+db.count()+"\nИсточник: API-SPORTS.IO.\nАрхив автоматически пополняется при успешной загрузке календаря и истории по датам. Сохраняются только завершённые матчи и счёт. Данные хранятся на телефоне, не отправляются на сервер.\nДругие поставщики ещё не подключены.";}catch(Exception e){msg="Не удалось открыть локальную базу.";}
            final String out=msg;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);new AlertDialog.Builder(this).setTitle("Локальная база GoS").setMessage(out).setPositiveButton("ОК",null).show();});
        });
    }
    private void localAnalysis(String title,int home,int away,long cutoff,long id){
        if(busy)return;busy=true;load.setEnabled(false);settings.setEnabled(false);
        worker.execute(()->{
            String msg;
            try(SportLocalArchive db=new SportLocalArchive(getApplicationContext())){
                SportHistory.Summary h=SportHistory.summarize(db.history(home,cutoff),home,cutoff),a=SportHistory.summarize(db.history(away,cutoff),away,cutoff);
                msg="Локальный архив API-SPORTS.IO • без новых запросов. До 20 наблюдений на команду. История может быть неполной.\n\nХозяева\n"+describe(h)+"\n\nГости\n"+describe(a)+"\n\n"+model(h,a)+"\n\nЛичные встречи в архиве\n"+describe(SportHistory.summarize(db.pair(home,away,cutoff),home,cutoff))+"\n\nЛокальный расчёт не записывается в основной журнал: источник выборки отличается.";
            }catch(Exception e){msg="Не удалось прочитать локальную историю.";}
            final String out=msg;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);new AlertDialog.Builder(this).setTitle(title).setMessage(out).setPositiveButton("ОК",null).show();});
        });
    }
    private void archiveHistory(String title,int home,int away,long cutoff,long fixtureId){
        if(busy)return;
        new AlertDialog.Builder(this).setTitle("История по датам — Free").setMessage("Загрузить завершённые матчи за 14 предыдущих дней? До 14 запросов; кэш общий для всех команд. Если данных мало, прогноз не сохраняется. Доступ зависит от тарифа.").setNegativeButton("Отмена",null).setPositiveButton("Загрузить",(d,w)->{
            if(busy)return;busy=true;load.setEnabled(false);settings.setEnabled(false);status.setText("Загрузка календарей предыдущих дней…");
            worker.execute(()->{
                String output;
                try{
                    String token=readKey();if(token.isEmpty())throw new IllegalStateException("Сначала сохраните API-ключ.");
                    JSONArray all=new JSONArray();int days=0;String problem="";
                    java.util.Calendar cal=java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                    cal.setTimeInMillis(Math.min(cutoff*1000,System.currentTimeMillis()));
                    SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                    for(int i=0;i<14;i++){
                        cal.add(java.util.Calendar.DATE,-1);String date=fmt.format(cal.getTime());
                        try{
                            JSONArray rows=cachedRequest("history_archive_"+date,"fixtures?date="+date+"&timezone=UTC",token,21600000).getJSONArray("response");
                            storeArchive(rows);
                            JSONArray pair=SportArchive.forTeams(rows,home,away,cutoff);
                            for(int k=0;k<pair.length();k++)all.put(pair.getJSONObject(k));
                            days++;
                        }catch(IllegalStateException e){problem=e.getMessage();break;}
                        catch(Exception e){problem="Ошибка соединения или ответа API.";break;}
                    }
                    SportHistory.Summary h=SportHistory.summarize(all,home,cutoff),a=SportHistory.summarize(all,away,cutoff);
                    output="История по календарям UTC: "+days+"/14 дней.\nХозяева\n"+describe(h)+"\n\nГости\n"+describe(a)+"\n\n"+model(h,a)+"\n\nЛичные встречи за этот период\n"+describe(SportHistory.summarize(onlyPair(all,home,away),home,cutoff));
                    if(!problem.isEmpty())output+="\n\nЗагрузка прервана: "+problem;
                    if(days==14)output+="\n\n"+recordForecast(fixtureId,title,home,away,cutoff,h,a);
                    else output+="\n\nПрогноз не сохранён: неполная загрузка архива.";
                }catch(IllegalStateException e){output=e.getMessage();}catch(Exception e){output="Не удалось получить историю по датам.";}
                final String msg=output;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);status.setText("Осталось запросов по API: "+(remaining<0?"неизвестно":remaining));new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("ОК",null).show();});
            });
        }).show();
    }
    private void loadHistory(String title,int home,int away,long cutoff,long fixtureId){
        if(busy)return;busy=true;load.setEnabled(false);settings.setEnabled(false);status.setText("Загрузка истории команд…");
        worker.execute(()->{
            String output;
            try{
                String token=readKey();if(token.isEmpty())throw new IllegalStateException("Сначала сохраните API-ключ.");
                SportHistory.Summary h=SportHistory.summarize(teamHistory(home,token).getJSONArray("response"),home,cutoff);
                SportHistory.Summary a=SportHistory.summarize(teamHistory(away,token).getJSONArray("response"),away,cutoff);
                output="Историческая статистика до начала матча. Не прогноз.\n\nХозяева\n"+describe(h)+"\n\nГости\n"+describe(a)+"\n\n"+model(h,a)+"\n\nУгловые и ЖК: нужны отдельные данные статистики матчей.";
                try{output+="\n\n"+recordForecast(fixtureId,title,home,away,cutoff,h,a);}catch(Exception e){output+="\n\nНе удалось сохранить прогноз.";}
                try{output+="\n\nЛичные встречи (до 10)\n"+describe(SportHistory.summarize(onlyPair(headToHead(home,away,token).getJSONArray("response"),home,away),home,cutoff));}catch(Exception e){output+="\n\nЛичные встречи: данные недоступны."; }
            }catch(IllegalStateException e){output=e.getMessage()+"\n\nПопробуйте «История по датам • Free» в карточке матча.";}catch(Exception e){output="Не удалось получить историю. Проверьте интернет и тариф.";}
            final String msg=output;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);status.setText("Осталось запросов по API: "+(remaining<0?"неизвестно":remaining));new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("ОК",null).show();});
        });
    }
    static JSONArray onlyPair(JSONArray rows,int home,int away){
        JSONArray out=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i),t=r==null?null:r.optJSONObject("teams");
            if(t==null)continue;JSONObject h=t.optJSONObject("home"),a=t.optJSONObject("away");
            if(h==null||a==null)continue;
            int x=h.optInt("id",-1),y=a.optInt("id",-1);
            if((x==home&&y==away)||(x==away&&y==home))out.put(r);
        }return out;
    }
    static final Object JOURNAL_LOCK=new Object();
    private JSONObject journal()throws JSONException{return new JSONObject(prefs.getString("forecast_journal_v1","{}"));}
    private String recordForecast(long id,String title,int home,int away,long cutoff,SportHistory.Summary h,SportHistory.Summary a)throws Exception{
        synchronized(JOURNAL_LOCK){
            JSONObject j=journal();String k=Long.toString(id);
            if(j.has(k))return "Первоначальный прогноз уже сохранён.";
            if(id<=0||System.currentTimeMillis()/1000>=cutoff||h.n<5||a.n<5)return "Прогноз не сохранён: матч начался или недостаточно истории.";
            if(j.length()>=500)return "Журнал заполнен (500 матчей).";
            JSONObject p=SportForecastJournal.create(title,home,away,cutoff,h,a);
            if(p==null)return "Прогноз не сохранён: несогласованные данные.";
            j.put(k,p);if(!prefs.edit().putString("forecast_journal_v1",j.toString()).commit())throw new Exception();
            return "Первоначальный прогноз сохранён в журнале.";
        }
    }
    private void showJournal(){
        if(busy){status.setText("Дождитесь завершения текущего запроса.");return;}
        try{JSONObject j=journal();new AlertDialog.Builder(this).setTitle("Журнал прогнозов GoS").setMessage(SportForecastJournal.report(j)).setNegativeButton("Закрыть",null).setPositiveButton("Сверить результаты",(d,w)->reconcileJournal()).show();}
        catch(Exception e){status.setText("Не удалось прочитать журнал.");}
    }
    private void reconcileJournal(){
        if(busy)return;busy=true;load.setEnabled(false);settings.setEnabled(false);status.setText("Сверка результатов…");
        worker.execute(()->{
            int checked=0,settled=0;String error="";
            try{
                String token=readKey();if(token.isEmpty())throw new IllegalStateException("Сначала сохраните API-ключ.");
                JSONObject snapshot=journal();java.util.ArrayList<String> eligible=new java.util.ArrayList<>();
                java.util.Iterator<String> keys=snapshot.keys();
                while(keys.hasNext()){String k=keys.next();JSONObject p=snapshot.optJSONObject(k);if(p!=null&&!SportForecastJournal.closed(p)&&System.currentTimeMillis()/1000>=p.optLong("cutoff")+7200)eligible.add(k);}
                java.util.Collections.sort(eligible,(a,b)->Long.compare(prefs.getLong("gos_bg_attempt_"+a,0),prefs.getLong("gos_bg_attempt_"+b,0)));
                for(String k:eligible){
                    if(checked>=5)break;
                    long id=Long.parseLong(k);checked++;
                    prefs.edit().putLong("gos_bg_attempt_"+k,System.currentTimeMillis()).apply();
                    JSONArray rows=cachedRequest("history_result_"+id,"fixtures?id="+id,token,600000).getJSONArray("response");
                    synchronized(JOURNAL_LOCK){
                        JSONObject current=journal(),entry=current.getJSONObject(k);
                        for(int i=0;i<rows.length();i++){
                            JSONObject row=rows.optJSONObject(i);
                            if(!SportForecastJournal.closed(entry)&&SportForecastJournal.settle(entry,id,row)){
                                if(!prefs.edit().putString("forecast_journal_v1",current.toString()).commit())throw new IllegalStateException("Ошибка сохранения.");
                                settled++;
                            }
                        }
                    }
                }
            }catch(Exception e){error="\nСверка прервана: проверьте лимит, тариф или интернет.";}
            final String msg="Проверено: "+checked+"; обработано (включая исключения): "+settled+error;
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;load.setEnabled(true);settings.setEnabled(true);status.setText(msg);showJournal();});
        });
    }
    private String model(SportHistory.Summary h,SportHistory.Summary a){
        if(h.n<5||a.n<5)return "Модель: недостаточно данных (нужно минимум 5 завершённых матчей каждой команды).";
        double x=(h.gf/(double)h.n+a.ga/(double)a.n)/2,y=(a.gf/(double)a.n+h.ga/(double)h.n)/2;
        if(x+y>50)return "Модель: некорректный диапазон средних.";
        double[] p=SportAutoModel.outcomes(x,y);
        String out="Учебная модель Пуассона — не проверена на реальных прогнозах. Средние атаки и пропущенных голов соперника имеют равный вес. Не учитываются составы, сила лиг и домашнее преимущество.\n"+
            "Ожидаемые голы: "+String.format(Locale.US,"%.2f / %.2f",x,y)+
            "\nП1 / X / П2: "+percent(p[0])+" / "+percent(p[1])+" / "+percent(p[2])+
            "\nОЗ да / нет: "+percent(SportStatistics.bothScore(x,y))+" / "+percent(1-SportStatistics.bothScore(x,y))+
            "\nГолов 3 и более: "+percent(SportStatistics.above(x+y,2));
        if(h.htN<5||a.htN<5)return out+"\nПервый тайм: недостаточно данных.";
        double u=(h.htFor/(double)h.htN+a.htAgainst/(double)a.htN)/2,v=(a.htFor/(double)a.htN+h.htAgainst/(double)h.htN)/2;
        if(u+v>50||u>x||v>y)return out+"\nПервый тайм: несогласованные средние.";
        return out+"\n\nПервый тайм (выборки "+h.htN+" / "+a.htN+"):"+
            "\nБез голов: "+percent(1-SportStatistics.above(u+v,0))+
            "\n1+ гол: "+percent(SportStatistics.above(u+v,0))+
            "\n2+ гола: "+percent(SportStatistics.above(u+v,1))+
            "\n3+ гола: "+percent(SportStatistics.above(u+v,2))+
            "\nОЗ: "+percent(SportStatistics.bothScore(u,v));
    }
    private String describe(SportHistory.Summary s){
        if(s.n==0)return "Недостаточно завершённых матчей.";
        return "Матчей: "+s.n+"\nПобеды / ничьи / поражения: "+s.w+" / "+s.d+" / "+s.l+"\nСредние голы забито / пропущено: "+String.format(Locale.US,"%.2f / %.2f",s.gf/(double)s.n,s.ga/(double)s.n)+"\nОЗ в истории: "+s.both+"/"+s.n+" ("+percent(s.both/(double)s.n)+")\nГол в первом тайме: "+(s.htN==0?"нет данных":s.htGoal+"/"+s.htN+" ("+percent(s.htGoal/(double)s.htN)+")");
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
