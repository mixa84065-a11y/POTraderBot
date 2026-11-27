package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Read-only view of the existing GoS fixture cache and official publisher links. */
final class NewsCenter {
    private static final int GOLD=0xfff5c65a, BG=0xff090e15, PANEL=0xff17212e, WHITE=0xfff2f4f7, MUTED=0xffb6c0cf;
    private final Activity a;
    private final Runnable openSport;
    private AlertDialog dialog;
    private LinearLayout content;
    private TextView footballTab,economyTab;
    NewsCenter(Activity a,Runnable openSport){this.a=a;this.openSport=openSport;}
    private int dp(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView label(String title,int size,int color,boolean bold){
        TextView t=new TextView(a);t.setText(title);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private GradientDrawable surface(int color,int border){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(12));g.setStroke(dp(1),border);return g;
    }
    private void add(LinearLayout p,TextView t){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);p.addView(t,lp);
    }
    private void card(String title,String subtitle,Runnable click){
        TextView t=label(title+"\n"+subtitle,14,WHITE,false);
        t.setPadding(dp(12),dp(12),dp(12),dp(12));t.setBackground(surface(PANEL,0xff455063));
        if(click!=null){t.setFocusable(true);t.setOnClickListener(v->click.run());}
        add(content,t);
    }
    void show(){
        LinearLayout root=col();root.setBackgroundColor(BG);root.setPadding(dp(14),dp(14),dp(14),dp(8));
        add(root,label("♛  НОВОСТИ",22,GOLD,true));
        LinearLayout tabs=new LinearLayout(a);
        footballTab=label("Футбол",15,GOLD,true);economyTab=label("Экономика",15,MUTED,true);
        for(TextView t:new TextView[]{footballTab,economyTab}){
            t.setGravity(Gravity.CENTER);t.setPadding(dp(8),dp(12),dp(8),dp(12));t.setFocusable(true);
            tabs.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        }
        root.addView(tabs);
        ScrollView scroll=new ScrollView(a);content=col();content.setPadding(0,dp(10),0,0);scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        footballTab.setOnClickListener(v->{football();scroll.scrollTo(0,0);});
        economyTab.setOnClickListener(v->{economy();scroll.scrollTo(0,0);});
        dialog=new AlertDialog.Builder(a).setView(root).setPositiveButton("Закрыть",null).create();
        football();dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*.85));
    }
    private void select(boolean sport){
        footballTab.setTextColor(sport?BG:MUTED);economyTab.setTextColor(sport?MUTED:BG);
        footballTab.setBackground(surface(sport?GOLD:PANEL,0xff765d31));
        economyTab.setBackground(surface(sport?PANEL:GOLD,0xff765d31));
        content.removeAllViews();
    }
    private void football(){
        select(true);
        add(content,label("События GoS · API-SPORTS",16,GOLD,true));
        SharedPreferences prefs=a.getSharedPreferences("gos_local",Activity.MODE_PRIVATE);
        long cached=prefs.getLong("cache_time",0);
        long now=System.currentTimeMillis();
        boolean fresh=cached>0&&now>=cached&&now-cached<900000;
        String cacheLabel=cached>0?format(cached):"не загружен";
        add(content,label("Кэш: "+cacheLabel+" · "+(fresh?"обновлён менее 15 минут назад":"требует обновления")+".\nДанные матчей, а не редакционные статьи. Время МСК.",12,MUTED,false));
        card("Открыть GoS","Загрузить матчи, составы и травмы через существующий модуль.",()->{dialog.dismiss();openSport.run();});
        try{
            JSONArray fixtures=new JSONObject(prefs.getString("fixtures_cache","{}")).optJSONArray("response");
            int shown=0;
            if(fixtures!=null)for(int i=0;i<fixtures.length()&&shown<80;i++){
                JSONObject item=fixtures.optJSONObject(i);if(item==null)continue;
                JSONObject f=item.optJSONObject("fixture"),teams=item.optJSONObject("teams"),league=item.optJSONObject("league"),goals=item.optJSONObject("goals");
                if(f==null||teams==null)continue;
                JSONObject h=teams.optJSONObject("home"),away=teams.optJSONObject("away");
                if(h==null||away==null)continue;
                JSONObject st=f.optJSONObject("status");String status=st==null?"":st.optString("short");
                String name=h.optString("name","—")+" — "+away.optString("name","—");
                if(goals!=null&&!goals.isNull("home")&&!goals.isNull("away"))name+="  "+goals.optInt("home")+":"+goals.optInt("away");
                long ts=f.optLong("timestamp",0);String date=ts>0?format(ts*1000):"Время неизвестно";
                final String title=name, meta=(league==null?"":league.optString("name")+" · ")+date+" · "+statusName(status);
                final long id=f.optLong("id",0);
                card(title,meta+"\nПодробнее ›",()->details(title,meta,id,prefs));shown++;
            }
            if(shown==0)add(content,label("В GoS пока нет сохранённых матчей. Откройте GoS и загрузите календарь.",14,WHITE,false));
            else if(fixtures.length()>shown&&shown==80)add(content,label("Показано до 80 событий. Полный календарь доступен в GoS.",12,MUTED,false));
        }catch(Exception e){add(content,label("Кэш GoS недоступен. Откройте спортивный раздел.",14,WHITE,false));}
        card("Спортивные новости · Sportbox","Источник из списка GoS · открыть оригинал",()->link("https://news.sportbox.ru/"));
        card("Soccer365","Новости и статистика · открыть оригинал",()->link("https://soccer365.ru/"));
    }
    private void details(String title,String meta,long id,SharedPreferences prefs){
        StringBuilder out=new StringBuilder(meta);
        out.append("\n\nИсточник: сохранённые данные GoS. Не прямой эфир.\n");
        out.append("\nСОСТАВЫ\n").append(cachedSquads(prefs,"history_lineups_"+id,600000L,true,id));
        out.append("\n\nТРАВМЫ И ОТСУТСТВУЮЩИЕ\n").append(cachedSquads(prefs,"history_injuries_"+id,1800000L,false,id));
        new AlertDialog.Builder(a).setTitle(title).setMessage(out.toString()).setNegativeButton("Закрыть",null)
            .setPositiveButton("Открыть GoS",(d,w)->{dialog.dismiss();openSport.run();}).show();
    }
    private String cachedSquads(SharedPreferences prefs,String key,long ttl,boolean lineups,long id){
        if(id<=0)return "Данные недоступны.";
        try{
            long saved=prefs.getLong(key+"_time",0),now=System.currentTimeMillis();
            if(saved<=0||now<saved)return "Ещё не загружено. Откройте матч в GoS.";
            JSONArray rows=new JSONObject(prefs.getString(key,"{}")).optJSONArray("response");
            if(rows==null)return "Данные недоступны.";
            String age="Кэш: "+format(saved)+(now-saved>=ttl?" · устарел, требуется обновление":"")+"\n";
            return age+(lineups?SportSquads.lineups(rows):SportSquads.injuries(rows));
        }catch(Exception e){return "Не удалось прочитать кэш GoS.";}
    }
    private void economy(){
        select(false);
        add(content,label("Investing.com · официальные страницы",16,GOLD,true));
        add(content,label("Материалы и актуальные значения открываются у источника. Автоматический импорт и перепубликация не подключены.",13,MUTED,false));
        card("Экономические новости","Макроэкономика, центральные банки, рынки",()->link("https://ru.investing.com/news/economy"));
        card("Экономический календарь","Время выхода, важность, факт, прогноз, предыдущее значение",()->link("https://ru.investing.com/economic-calendar"));
        card("Экономические показатели","Инфляция, занятость и другие публикации",()->link("https://ru.investing.com/news/economic-indicators"));
        card("Все финансовые новости","Открыть ленту Investing.com на русском",()->link("https://ru.investing.com/news"));
        card("RSS-ленты и информеры","Официальные инструменты Investing.com; условия использования у источника",()->link("https://ru.investing.com/webmaster-tools/rss"));
    }
    private void link(String url){
        try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(android.content.ActivityNotFoundException e){Toast.makeText(a,"Браузер недоступен",Toast.LENGTH_SHORT).show();}
    }
    private static String format(long time){
        SimpleDateFormat f=new SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.forLanguageTag("ru-RU"));
        f.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));return f.format(new Date(time))+" МСК";
    }
    private static String statusName(String s){
        switch(s){
            case "NS":return "Не начался";
            case "TBD":return "Время уточняется";
            case "1H":return "Первый тайм";
            case "HT":return "Перерыв";
            case "2H":return "Второй тайм";
            case "ET":return "Дополнительное время";
            case "BT":return "Перерыв в дополнительное время";
            case "P":return "Серия пенальти";
            case "FT":case "AET":case "PEN":return "Завершён";
            case "PST":return "Перенесён";
            case "CANC":return "Отменён";
            case "SUSP":case "INT":return "Приостановлен";
            case "ABD":return "Прерван";
            case "AWD":case "WO":return "Технический результат";
            case "LIVE":return "Идёт матч";
            default:return "Статус не уточнён";
        }
    }
}
