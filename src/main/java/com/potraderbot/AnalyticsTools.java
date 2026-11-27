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
    void replay(){
        LinearLayout p=panel();
        note(p,"Исторический тест Android-спот-стратегии, без реальных ордеров. Загрузите историю Bybit либо вставьте CSV минутных свечей UTC с заголовком timestamp_ms,open,close,turnover24h. Нужны достоверные исторические значения оборота за 24 часа.");
        EditText market=input(p,"Спот-пара USDT","BTCUSDT",true);market.setSingleLine(true);market.setMinLines(1);
        EditText minutes=input(p,"Количество свечей (500–5000)","2880",false);
        EditText data=input(p,"CSV минутных свечей (до 5000 строк)","",true);
        TextView source=note(p,"Источник: ручной CSV или публичная история Bybit. Загрузка требует интернет.");
        Button download=new Button(a);download.setText("Загрузить историю Bybit");download.setAllCaps(false);
        download.setTextColor(BG);download.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));p.addView(download);
        download.setOnClickListener(v->{
            final String symbol=market.getText().toString().trim().toUpperCase(Locale.US);
            final int count;
            try{count=Integer.parseInt(minutes.getText().toString().trim());
                if(count<500||count>5000||!symbol.matches("[A-Z0-9]{1,20}USDT"))throw new IllegalArgumentException();
            }catch(Exception e){source.setText("Введите спот-пару USDT и 500–5000 свечей.");return;}
            download.setEnabled(false);source.setText("Загружаем завершённые свечи… Старый CSV не изменён.");
            new Thread(()->{
                String csv=null,problem=null;
                try{csv=BybitReplayHistory.download(symbol,count);}
                catch(Exception e){problem=e instanceof IllegalArgumentException||e instanceof java.io.IOException?e.getMessage():"не удалось получить историю";}
                final String loaded=csv,error=problem;
                a.runOnUiThread(()->{
                    if(a.isFinishing()||a.isDestroyed()||!source.isAttachedToWindow())return;
                    download.setEnabled(true);
                    if(loaded!=null){data.setText(loaded);source.setText("Bybit Spot · "+symbol+" · "+count+" завершённых свечей M1. CSV заполнен. Нажмите «Запустить исторический тест».");}
                    else source.setText("Ошибка: "+error+". Прежний CSV сохранён.");
                });
            },"bybit-replay-history").start();
        });
        EditText lookback=input(p,"История для сигнала, свечей","240",false);
        EditText horizon=input(p,"Удержание, минут","5",false);
        EditText spread=input(p,"Спред, базисные пункты","5",false);
        EditText fee=input(p,"Комиссия за одну сторону, б.п.","10",false);
        EditText slip=input(p,"Проскальзывание, б.п.","5",false);
        note(p,"1 б.п. = 0,01%. Значения расходов — примеры, замените их условиями своего рынка. Просадка считается по закрытым сделкам. Стоп-лосс и тейк-профит не моделируются.");
        TextView result=note(p,"Тест ещё не выполнен.");
        Button run=new Button(a);run.setText("Запустить исторический тест");run.setAllCaps(false);
        run.setTextColor(BG);run.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));p.addView(run);
        run.setOnClickListener(v->{
            final String csv=data.getText().toString();
            final int l,h;
            final double s,f,x;
            try{
                l=Integer.parseInt(lookback.getText().toString().trim());h=Integer.parseInt(horizon.getText().toString().trim());
                s=number(spread);f=number(fee);x=number(slip);
                if(l<55||l>1000||h<1||h>120||csv.length()>1000000)throw new IllegalArgumentException();
            }catch(Exception e){result.setText("Проверьте параметры: история 55–1000, удержание 1–120 минут.");return;}
            run.setEnabled(false);result.setText("Выполняется локальный расчёт…");
            new Thread(()->{
                String output;
                try{
                    java.util.List<HistoricalSpotReplay.Bar> bars=ReplayCsv.parse(csv);
                    if(bars.size()<l+h+1)throw new IllegalArgumentException("Недостаточно свечей для выбранного окна");
                    HistoricalSpotReplay.Report r=HistoricalSpotReplay.replay(bars,l,h,s,f,x);
                    output="ОБРАБОТАНО СВЕЧЕЙ: "+bars.size()+"\nДопустимых окон: "+r.eligibleWindows+" · Пропуски: "+r.skippedGaps;
                    if(r.eligibleWindows==0)output+="\nНет пригодных непрерывных окон. Проверьте историю.";
                    else{
                        output+="\n\n"+replayMetrics("Спот-стратегия",r.strategy)+"\n\n"+replayMetrics("Базовая покупка",r.baseline);
                        if(r.comparisonWindows>0)output+="\n\nСопоставимых окон: "+r.comparisonWindows+"\n"+replayMetrics("Исходная стратегия",r.matchedOriginal)+"\n"+replayMetrics("Фильтр 5/15 минут",r.filtered);
                        else output+="\n\nНедостаточно сопоставимых окон для фильтра 5/15 минут.";
                    }
                    output+="\n\nИсторический эксперимент. Не прогноз доходности и не статистика серверного робота.";
                }catch(Exception e){output="Ошибка теста: "+(e instanceof IllegalArgumentException?e.getMessage():"не удалось обработать CSV");}
                final String message=output;
                a.runOnUiThread(()->{if(!a.isFinishing()&&!a.isDestroyed()&&result.isAttachedToWindow()){result.setText(message);run.setEnabled(true);}});
            },"offline-spot-replay").start();
        });show("Исторический бэктест",p);
    }
    private String replayMetrics(String title,HistoricalSpotReplay.Metrics m){
        return String.format(Locale.forLanguageTag("ru-RU"),"%s\nСделок: %d · Плюс: %d · Минус: %d · Ноль: %d\nДоходность: %+.2f%% · Просадка: %.2f%%",title,m.trades,m.wins,m.losses,m.breakeven,m.returnPct(),m.maxDrawdownPct);
    }
    void openAssistant(){
        new AlertDialog.Builder(a).setTitle("GoD AI")
            .setMessage("Встроенный чат подключается к существующему серверу GoD AI. Вход выполняется отдельно. Данные торгового и спортивного аккаунтов не передаются автоматически.")
            .setPositiveButton("Открыть чат",(d,w)->a.startActivity(new Intent(a,AssistantActivity.class)))
            .setNeutralButton("Отдельное приложение",(d,w)->openExternalAssistant())
            .setNegativeButton("Отмена",null).show();
    }
    private void openExternalAssistant(){
        for(String pkg:new String[]{"ai.god.companion","ai.god.companion.preview"}){
            Intent i=a.getPackageManager().getLaunchIntentForPackage(pkg);
            if(i!=null)try{a.startActivity(i);return;}catch(android.content.ActivityNotFoundException ignored){}
        }
        new AlertDialog.Builder(a).setTitle("GoD AI").setMessage("Приложение GoD AI не найдено. Установите его отдельно. Данные аккаунта и ключи не передаются.")
            .setPositiveButton("Открыть проект",(d,w)->link("https://github.com/mixa84065-a11y/GoD-AI")).setNegativeButton("Закрыть",null).show();
    }
}
