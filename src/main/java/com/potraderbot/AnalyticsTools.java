package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

final class AnalyticsTools {
    private final Activity a;
    private static final int GOLD=0xfff5c65a, WHITE=0xfff2f4f7, BG=0xff090e15;
    AnalyticsTools(Activity a){this.a=a;}
    private int dp(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private LinearLayout panel(){
        LinearLayout p=new LinearLayout(a);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(16),dp(12),dp(16),dp(16));p.setBackgroundColor(BG);return p;
    }
    private TextView note(LinearLayout p,String s){
        TextView t=new TextView(a);t.setText(s);t.setTextSize(14);t.setTextColor(WHITE);t.setPadding(0,dp(8),0,dp(8));p.addView(t);return t;
    }
    private EditText input(LinearLayout p,String hint,String initial,boolean multi){
        EditText e=new EditText(a);e.setHint(hint);e.setHintTextColor(0xffaab5c2);e.setTextColor(WHITE);e.setText(initial);
        if(multi){e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);e.setMinLines(4);}
        else{e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setSingleLine(true);}
        p.addView(e);return e;
    }
    private void button(LinearLayout p,String s,Runnable r){
        Button b=new Button(a);b.setText(s);b.setAllCaps(false);b.setTextColor(BG);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));
        b.setOnClickListener(v->r.run());p.addView(b);
    }
    private void show(String title,LinearLayout p){ScrollView scroll=new ScrollView(a);scroll.addView(p);new AlertDialog.Builder(a).setTitle(title).setView(scroll).setPositiveButton("Закрыть",null).show();}
    private double number(EditText e){return Double.parseDouble(e.getText().toString().trim().replace(',','.'));}
    private void link(String url){
        try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(android.content.ActivityNotFoundException ex){Toast.makeText(a,"Браузер недоступен",Toast.LENGTH_SHORT).show();}
    }
    void investments(){
        LinearLayout p=panel();note(p,"Открывайте актуальные котировки на Investing.com. Калькулятор ниже использует введённые вами цены в одной валюте, без автоматической торговли.");
        button(p,"Акции — котировки",()->link("https://ru.investing.com/equities/"));
        button(p,"ETF — котировки",()->link("https://ru.investing.com/etfs/"));
        button(p,"Фонды — котировки",()->link("https://ru.investing.com/funds/"));
        button(p,"Облигации и другие рынки",()->link("https://ru.investing.com/markets/"));
        note(p,"КАЛЬКУЛЯТОР ДОХОДНОСТИ");
        EditText q=input(p,"Количество бумаг","",false),entry=input(p,"Цена покупки за единицу","",false),current=input(p,"Текущая цена за единицу","",false),fees=input(p,"Сумма комиссий","0",false),income=input(p,"Полученные дивиденды / купоны","0",false);
        TextView result=note(p,"Введите данные для расчёта.");
        button(p,"Рассчитать",()->{
            try{AnalyticsMath.Investment x=AnalyticsMath.investment(number(q),number(entry),number(current),number(fees),number(income));
                result.setText(String.format(Locale.forLanguageTag("ru-RU"),"Затраты: %,.2f\nСтоимость с доходами: %,.2f\nРезультат: %,.2f (%+.2f%%)\nБез учёта налогов и будущих расходов.",x.cost,x.value,x.profit,x.percent));
            }catch(Exception e){result.setText("Проверьте числа: количество и цена покупки положительные, остальные значения неотрицательные.");}
        });show("Инвестиции",p);
    }
    void laboratory(){
        LinearLayout p=panel();note(p,"Анализ завершённых сделок в хронологическом порядке. Укажите чистую прибыль или убыток каждой сделки после комиссий. Это не автоматический бэктест по котировкам.");
        EditText start=input(p,"Начальный демо-баланс","",false),values=input(p,"По одному результату на строку, например: +100; -50","",true);
        TextView result=note(p,"Результат пока не рассчитан.");
        button(p,"Проверить серию",()->{
            try{String raw=values.getText().toString().trim();if(raw.isEmpty()||raw.length()>30000)throw new IllegalArgumentException();
                String[] parts=raw.split("[;\\s]+");if(parts.length>1000)throw new IllegalArgumentException();
                double[] changes=new double[parts.length];for(int i=0;i<parts.length;i++)changes[i]=Double.parseDouble(parts[i].replace(',','.'));
                AnalyticsMath.Series s=AnalyticsMath.series(number(start),changes);
                result.setText(String.format(Locale.forLanguageTag("ru-RU"),"Сделок: %d\nПрибыльных: %d · убыточных: %d · нулевых: %d\nИтоговый баланс: %,.2f\nЧистый результат: %,.2f\nСреднее на сделку: %,.2f\nМаксимальная просадка: %.2f%%",parts.length,s.wins,s.losses,s.flat,s.ending,s.total,s.average,s.maxDrawdown));
            }catch(Exception ex){result.setText("Проверьте формат и баланс. Серия не должна опускать баланс ниже нуля. Максимум 1000 сделок.");}
        });show("Лаборатория стратегий",p);
    }
    void openAssistant(){
        for(String pkg:new String[]{"ai.god.companion","ai.god.companion.preview"}){
            Intent i=a.getPackageManager().getLaunchIntentForPackage(pkg);
            if(i!=null)try{a.startActivity(i);return;}catch(android.content.ActivityNotFoundException ignored){}
        }
        new AlertDialog.Builder(a).setTitle("GoD AI").setMessage("Приложение GoD AI не найдено. Установите его отдельно. Данные аккаунта и ключи не передаются.")
            .setPositiveButton("Открыть проект",(d,w)->link("https://github.com/mixa84065-a11y/GoD-AI")).setNegativeButton("Закрыть",null).show();
    }
}
