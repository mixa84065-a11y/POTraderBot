package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Separate Bybit terminal. First delivery intentionally uses public Bybit market
 * data and a local paper ledger only. No private credentials or real orders exist here.
 */
public class BybitTerminalActivity extends Activity {
    private static final int BG=Color.rgb(7,16,29), PANEL=Color.rgb(14,29,48);
    private static final int GOLD=Color.rgb(245,190,65), WHITE=Color.rgb(230,238,247);
    private static final int MUTED=Color.rgb(139,157,180), GREEN=Color.rgb(38,176,129), RED=Color.rgb(220,83,91);
    private static final String API="https://api.bybit.com";
    private static final double START_BALANCE=20000.0, STAKE=100.0, STOP_PCT=0.007, TAKE_PCT=0.014, MAX_DAILY_LOSS=200.0;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String accessToken="";
    private TextView serverReportText, connectionStatusText;
    private Button serverRefreshButton, connectionCheckButton;
    private Spinner symbolPicker, scanCategoryPicker;
    private TextView multiScanText;
    private Button multiScanButton;
    private TextView priceText, changeText, balanceText, modeText, signalText, riskText, historyText, statusText, liveText;
    private Button autoButton;
    private PriceChart priceChart;
    private String symbol="BTCUSDT";
    private boolean loading=false, autoEnabled=false;
    private JSONArray candles=new JSONArray();
    private double lastPrice=0;
    private final Runnable poll=new Runnable(){@Override public void run(){loadMarket();handler.postDelayed(this,15000);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        accessToken=getIntent().getStringExtra("access_token");
        if(accessToken==null)accessToken="";
        prefs=getSharedPreferences("bybit_paper_ledger",MODE_PRIVATE);
        if(!prefs.contains("cash"))prefs.edit().putFloat("cash",(float)START_BALANCE).apply();
        String today=new SimpleDateFormat("yyyyMMdd",Locale.US).format(new Date());
        if(!today.equals(prefs.getString("loss_day",""))){prefs.edit().putString("loss_day",today).putFloat("loss_today",0).apply();}
        autoEnabled=prefs.getBoolean("auto",false);
        if(prefs.getBoolean("open",false))symbol=prefs.getString("trade_symbol","BTCUSDT");
        buildScreen();
        refreshServerReport();
        handler.post(poll);
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,1);return t;
    }
    private Button button(String s,int bg,int fg){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setTypeface(null,1);
        b.setTextColor(fg);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));return b;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void buildScreen(){
        LinearLayout root=vertical();root.setPadding(dp(14),dp(12),dp(14),dp(18));root.setBackgroundColor(BG);
        LinearLayout top=horizontal();
        TextView title=text("BYBIT  /  TRADING TERMINAL",18,GOLD,true);
        top.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        Button back=button("НАЗАД",PANEL,WHITE);top.addView(back,new LinearLayout.LayoutParams(-2,dp(40)));
        back.setOnClickListener(v->finish());root.addView(top);
        TextView sub=text("SPOT • КОТИРОВКИ BYBIT • ЛОКАЛЬНАЯ СИМУЛЯЦИЯ",10,MUTED,true);sub.setPadding(0,dp(2),0,dp(10));root.addView(sub);
        LinearLayout select=horizontal();
        TextView pairLabel=text("ИНСТРУМЕНТ",10,MUTED,true);select.addView(pairLabel);
        symbolPicker=new Spinner(this);
        String[] symbols={"BTCUSDT","ETHUSDT","SOLUSDT","XRPUSDT","BNBUSDT","DOGEUSDT"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,symbols);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);symbolPicker.setAdapter(adapter);
        select.addView(symbolPicker,new LinearLayout.LayoutParams(0,dp(44),1));
        root.addView(select);
        LinearLayout multiScan=horizontal();
        scanCategoryPicker=new Spinner(this);
        String[] scanCategories={"Spot","USDT Futures","Inverse Futures"};
        ArrayAdapter<String> scanAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,scanCategories);
        scanAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scanCategoryPicker.setAdapter(scanAdapter);
        multiScan.addView(scanCategoryPicker,new LinearLayout.LayoutParams(0,dp(46),1));
        multiScanButton=button("СКАНИРОВАТЬ РЫНОК",GOLD,BG);
        multiScan.addView(multiScanButton,new LinearLayout.LayoutParams(0,dp(46),1));
        LinearLayout.LayoutParams msp=new LinearLayout.LayoutParams(-1,-2);msp.topMargin=dp(6);root.addView(multiScan,msp);
        multiScanText=text("Мультианализ: нажмите «Сканировать рынок». Будут проверены наиболее ликвидные инструменты Bybit.",11,MUTED,false);
        multiScanText.setPadding(dp(10),dp(8),dp(10),dp(8));multiScanText.setBackgroundColor(PANEL);root.addView(multiScanText);
        multiScanButton.setOnClickListener(v->runMultiScan());
        symbolPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                String next=symbols[pos];
                if(prefs.getBoolean("open",false) && !next.equals(prefs.getString("trade_symbol","BTCUSDT"))){
                    Toast.makeText(BybitTerminalActivity.this,"Сначала закройте открытую демо-позицию.",Toast.LENGTH_SHORT).show();
                    symbolPicker.setSelection(java.util.Arrays.asList(symbols).indexOf(prefs.getString("trade_symbol","BTCUSDT")));return;
                }
                if(!next.equals(symbol)){symbol=next;loadMarket();}
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        LinearLayout quote=horizontal();quote.setPadding(dp(12),dp(12),dp(12),dp(12));quote.setBackgroundColor(PANEL);
        LinearLayout qleft=vertical();
        priceText=text("Загрузка цены…",25,WHITE,true);qleft.addView(priceText);
        changeText=text("24ч: —",11,MUTED,false);qleft.addView(changeText);
        quote.addView(qleft,new LinearLayout.LayoutParams(0,-2,1));
        liveText=text("● ОЖИДАНИЕ",11,MUTED,true);quote.addView(liveText); 
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-1,-2);qp.topMargin=dp(8);root.addView(quote,qp);
        priceChart=new PriceChart();LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(220));cp.topMargin=dp(8);root.addView(priceChart,cp);
        TextView tf=text("ГРАФИК • 1 МИНУТА • ПОСЛЕДНИЕ 100 СВЕЧЕЙ",10,MUTED,true);tf.setPadding(dp(3),dp(5),0,dp(5));root.addView(tf);
        LinearLayout wallet=horizontal();wallet.setPadding(dp(12),dp(12),dp(12),dp(12));wallet.setBackgroundColor(PANEL);
        LinearLayout cashCol=vertical();cashCol.addView(text("ДЕМО-БАЛАНС",10,MUTED,true));
        balanceText=text("",20,WHITE,true);cashCol.addView(balanceText);
        wallet.addView(cashCol,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout modeCol=vertical();modeCol.setGravity(Gravity.RIGHT);
        modeCol.addView(text("РЕЖИМ",10,MUTED,true));modeText=text("ДЕМО",14,GOLD,true);modeCol.addView(modeText);
        wallet.addView(modeCol);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.topMargin=dp(8);root.addView(wallet,wp);
        signalText=text("АНАЛИЗ: ожидание данных…",14,WHITE,true);signalText.setPadding(dp(4),dp(14),0,dp(5));root.addView(signalText);
        statusText=text("Стратегия проверяет EMA 9/21, RSI(14) и импульс.",11,MUTED,false);root.addView(statusText);
        riskText=text("Риск на сделку: 100 USDT • стоп 0,7% • цель 1,4%",11,MUTED,false);
        riskText.setPadding(dp(4),dp(8),0,dp(8));root.addView(riskText);
        autoButton=button(autoEnabled?"АВТОДЕМО: ВКЛЮЧЕНО":"ЗАПУСТИТЬ АВТОДЕМО",autoEnabled?GREEN:GOLD,BG);
        LinearLayout.LayoutParams abp=new LinearLayout.LayoutParams(-1,dp(50));abp.topMargin=dp(4);root.addView(autoButton,abp);
        autoButton.setOnClickListener(v->{
            if(!autoEnabled && prefs.getFloat("loss_today",0)>=MAX_DAILY_LOSS){Toast.makeText(this,"Достигнут дневной лимит убытка. Повторный запуск завтра.",Toast.LENGTH_LONG).show();return;}
            autoEnabled=!autoEnabled;prefs.edit().putBoolean("auto",autoEnabled).apply();renderLedger();Toast.makeText(this,autoEnabled?"Автоторговля включена только в демо":"Автоторговля остановлена",Toast.LENGTH_SHORT).show();
        });
        TextView real=text("РЕАЛЬНЫЙ СЧЁТ: ЗАБЛОКИРОВАН ДО ПОДКЛЮЧЕНИЯ ЗАЩИЩЁННОГО API И ПРОВЕРКИ ДЕМО",10,RED,true);
        real.setPadding(dp(4),dp(12),dp(4),dp(8));root.addView(real);
        TextView historyTitle=text("ПОСЛЕДНЯЯ ДЕМО-СДЕЛКА",10,GOLD,true);historyTitle.setPadding(dp(4),dp(10),0,dp(5));root.addView(historyTitle);
        historyText=text("Сделок пока нет.",12,WHITE,false);historyText.setPadding(dp(12),dp(12),dp(12),dp(12));historyText.setBackgroundColor(PANEL);root.addView(historyText);
        TextView foot=text("Автостратегия — экспериментальная эвристика, не гарантия доходности. Это локальный симулятор, не Bybit Demo API.",10,MUTED,false);
        foot.setPadding(dp(4),dp(12),dp(4),0);root.addView(foot);
        buildServerReport(root);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);scroll.addView(root);setContentView(scroll);
        renderLedger();
    }

    private void buildServerReport(LinearLayout root){
        TextView heading=text("СЕРВЕРНЫЙ BYBIT DEMO • ОТЧЁТ 48 ЧАСОВ",12,GOLD,true);
        heading.setPadding(dp(4),dp(18),0,dp(8));root.addView(heading);
        TextView note=text("Серверная торговля будет работать независимо от телефона. API-ключи сохраняются в зашифрованном хранилище сервера; они не записываются в память приложения.",10,MUTED,false);
        note.setPadding(dp(4),0,dp(4),dp(8));root.addView(note);
        Button connect=button("ПОДКЛЮЧИТЬ BYBIT DEMO API",PANEL,WHITE);
        root.addView(connect,new LinearLayout.LayoutParams(-1,dp(46)));
        connect.setOnClickListener(v->showBybitCredentialsDialog());
        connectionCheckButton=button("ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ",PANEL,WHITE);
        LinearLayout.LayoutParams checkParams=new LinearLayout.LayoutParams(-1,dp(46));checkParams.topMargin=dp(6);root.addView(connectionCheckButton,checkParams);
        connectionCheckButton.setOnClickListener(v->checkBybitConnection());
        connectionStatusText=text("Авторизация Bybit ещё не проверена.",11,MUTED,false);
        connectionStatusText.setPadding(dp(10),dp(8),dp(10),dp(4));root.addView(connectionStatusText);
        serverRefreshButton=button("ОБНОВИТЬ ПОЛНЫЙ ОТЧЁТ",GOLD,BG);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(46));rp.topMargin=dp(6);root.addView(serverRefreshButton,rp);
        serverRefreshButton.setOnClickListener(v->refreshServerReport());
        serverReportText=text("Проверяем серверный отчёт…",11,WHITE,false);
        serverReportText.setPadding(dp(12),dp(12),dp(12),dp(12));
        serverReportText.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(8);root.addView(serverReportText,sp);
        TextView state=text("Серверный исполнитель Bybit Demo развернут. 48-часовой запуск пока не активирован: сначала нужно сохранить Demo API-ключи и проверить авторизацию, баланс и безопасную обработку заявок. Реальные ордера не используются.",10,MUTED,false);
        state.setPadding(dp(4),dp(8),dp(4),0);root.addView(state);
    }


    private void runMultiScan(){
        if(multiScanButton==null)return;
        final String category=new String[]{"spot","linear","inverse"}[scanCategoryPicker.getSelectedItemPosition()];
        multiScanButton.setEnabled(false);
        multiScanText.setText("Сканируем до 30 наиболее ликвидных инструментов Bybit ("+category+") параллельно. Только публичные данные, ордера не отправляются…");
        io.execute(()->{
            java.util.List<BybitMarketScanner.Signal> signals=null;String error=null;
            try{signals=BybitMarketScanner.scan(category,30);}catch(Exception e){error=e.getMessage();}
            final java.util.List<BybitMarketScanner.Signal> found=signals;final String problem=error;
            runOnUiThread(()->{
                if(isFinishing())return;
                multiScanButton.setEnabled(true);
                if(found==null){multiScanText.setText("Ошибка сканирования: "+problem);return;}
                if(found.isEmpty()){multiScanText.setText("Нет свежих сигналов. Новые входы не рекомендуются.");return;}
                StringBuilder out=new StringBuilder("РЕЙТИНГ ИНСТРУМЕНТОВ BYBIT • "+category.toUpperCase(java.util.Locale.US)+"\n");
                int shown=Math.min(15,found.size());
                for(int i=0;i<shown;i++){
                    BybitMarketScanner.Signal x=found.get(i);
                    out.append(i+1).append(". ").append(x.instrument.symbol).append(" • ").append(x.direction)
                       .append(" • score ").append(x.score).append(" • ").append(String.format(java.util.Locale.US,"%.8f",x.lastPrice)).append("\n");
                }
                out.append("\nПроверено сигналов: ").append(found.size())
                   .append(". Score — эвристика, не вероятность дохода. Это мультианализ, не открытие позиций.");
                multiScanText.setText(out.toString());
            });
        });
    }

    private void showBybitCredentialsDialog(){
        if(accessToken.isEmpty()){Toast.makeText(this,"Сначала войдите в аккаунт GOD OF TRADING.",Toast.LENGTH_LONG).show();return;}
        LinearLayout box=vertical();box.setPadding(dp(18),dp(8),dp(18),0);
        EditText keyInput=new EditText(this);keyInput.setSingleLine(true);keyInput.setHint("Bybit Demo API Key");
        keyInput.setTextColor(WHITE);keyInput.setHintTextColor(MUTED);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);box.addView(keyInput);
        EditText secretInput=new EditText(this);secretInput.setSingleLine(true);secretInput.setHint("Bybit Demo API Secret");
        secretInput.setTextColor(WHITE);secretInput.setHintTextColor(MUTED);
        secretInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(secretInput);
        TextView status=text("Создайте ключ именно в режиме Bybit Demo Trading. Разрешение на вывод средств не включайте.",10,MUTED,false);
        status.setPadding(0,dp(8),0,dp(4));box.addView(status);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Подключение Bybit Demo")
            .setView(box).setPositiveButton("СОХРАНИТЬ",null).setNegativeButton("ОТМЕНА",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String key=keyInput.getText().toString().trim(),secret=secretInput.getText().toString().trim();
            if(key.length()<12||secret.length()<20){status.setText("Проверьте API Key и API Secret.");return;}
            Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);save.setEnabled(false);status.setText("Сохраняем ключи в защищённое хранилище…");
            io.execute(()->{
                try{
                    SupabaseApi.saveBybitDemoCredentials(accessToken,key,secret);
                    runOnUiThread(()->{keyInput.setText("");secretInput.setText("");status.setText("Ключи сохранены. Сейчас проверяем авторизацию Bybit Demo без отправки ордеров…");save.setEnabled(true);Toast.makeText(this,"Ключи сохранены. Проверяем подключение…",Toast.LENGTH_LONG).show();refreshServerReport();checkBybitConnection();});
                }catch(Exception e){runOnUiThread(()->{status.setText("Не удалось сохранить ключи: "+e.getMessage());save.setEnabled(true);});}
            });
        }));
        dialog.show();
    }

    private void checkBybitConnection(){
        if(accessToken.isEmpty()){if(connectionStatusText!=null)connectionStatusText.setText("Нет активной сессии. Войдите в GOD OF TRADING.");return;}
        if(connectionCheckButton!=null)connectionCheckButton.setEnabled(false);
        if(connectionStatusText!=null)connectionStatusText.setText("Проверяем подпись API и доступ к Demo Unified Wallet. Ордеры не отправляются…");
        io.execute(()->{
            JSONObject result=null;String error=null;
            try{result=SupabaseApi.checkBybitDemoConnection(accessToken);}catch(Exception e){error=e.getMessage();}
            final JSONObject checked=result;final String problem=error;
            runOnUiThread(()->{
                if(isFinishing())return;
                if(connectionCheckButton!=null)connectionCheckButton.setEnabled(true);
                if(checked!=null&&checked.optBoolean("connected",false)){
                    connectionStatusText.setText("ПОДКЛЮЧЕНО • BYBIT DEMO\nБаланс: "+checked.optString("wallet_balance_usdt","—")+" USDT"
                        +"\nДоступно: "+checked.optString("available_usdt","—")+" USDT"
                        +"\nКапитал: "+checked.optString("equity_usdt","—")+" USDT"
                        +"\nПроверено: "+checked.optString("checked_at","—")
                        +"\nОрдеры не отправлялись. 48-часовой тест не запущен.");
                    connectionStatusText.setTextColor(GREEN);
                }else{
                    String reason=checked==null?problem:checked.optString("error","Не удалось проверить подключение.");
                    connectionStatusText.setText("BYBIT DEMO НЕ ПОДКЛЮЧЕН\n"+(reason==null?"Неизвестная ошибка":reason)+"\nОрдеры не отправлялись.");
                    connectionStatusText.setTextColor(RED);
                }
            });
        });
    }

    private void refreshServerReport(){
        if(serverReportText==null)return;
        if(accessToken.isEmpty()){serverReportText.setText("Нет активной сессии. Войдите в аккаунт, чтобы открыть серверный отчёт.");return;}
        if(serverRefreshButton!=null)serverRefreshButton.setEnabled(false);
        serverReportText.setText("Загружаем данные сервера…");
        io.execute(()->{
            JSONObject report=null;String error=null;
            try{report=SupabaseApi.getBybitDemoReport(accessToken);}catch(Exception e){error=e.getMessage();}
            final JSONObject result=report;final String problem=error;
            runOnUiThread(()->{
                if(isFinishing())return;
                if(serverRefreshButton!=null)serverRefreshButton.setEnabled(true);
                serverReportText.setText(result==null?"Отчёт недоступен: "+problem:formatServerReport(result));
            });
        });
    }

    private String formatServerReport(JSONObject report){
        StringBuilder b=new StringBuilder();
        b.append("КЛЮЧИ BYBIT DEMO: ").append(report.optBoolean("configured",false)?"СОХРАНЕНЫ В VAULT":"НЕ ПОДКЛЮЧЕНЫ");
        JSONObject run=report.optJSONObject("run");
        if(run==null){b.append("\n\n48-ЧАСОВЫЙ ТЕСТ ЕЩЁ НЕ ЗАПУСКАЛСЯ.");return b.toString();}
        b.append("\n\nСТАТУС: ").append(run.optString("status","—"));
        b.append("\nИнструменты: ").append(run.optString("symbols","—"));
        b.append("\nСтарт: ").append(run.optString("started_at","—"));
        b.append("\nПлановое завершение: ").append(run.optString("ends_at","—"));
        b.append("\nПоследний сигнал сервера: ").append(run.optString("last_heartbeat","—"));
        b.append("\nСтартовый капитал: ").append(run.optString("start_equity","—")).append(" USDT");
        b.append("\nТекущий капитал: ").append(run.optString("end_equity","—")).append(" USDT");
        b.append("\nРеализованный P/L: ").append(run.optString("realized_pnl","0")).append(" USDT");
        b.append("\nКомиссии: ").append(run.optString("fees_paid","0")).append(" USDT");
        b.append("\nМакс. просадка: ").append(run.optString("max_drawdown_pct","0")).append("%");
        if(!run.optString("last_error","").isEmpty())b.append("\nПоследняя ошибка: ").append(run.optString("last_error"));
        JSONObject summary=report.optJSONObject("summary");
        if(summary!=null){
            b.append("\n\nСТАТИСТИКА");
            b.append("\nСделок: ").append(summary.optInt("trades_total"));
            b.append("\nЗакрыто: ").append(summary.optInt("closed")).append(" • открыто: ").append(summary.optInt("open"));
            b.append("\nПлюсовых: ").append(summary.optInt("wins")).append(" • минусовых: ").append(summary.optInt("losses"));
            b.append("\nДоля плюсовых: ").append(summary.optString("win_rate_pct","0")).append("%");
            b.append("\nЧистый P/L: ").append(summary.optString("net_pnl","0")).append(" USDT");
            b.append("\nКомиссии по сделкам: ").append(summary.optString("fees","0")).append(" USDT");
        }
        JSONArray trades=report.optJSONArray("trades");
        b.append("\n\nВСЕ СДЕЛКИ (").append(trades==null?0:trades.length()).append(")");
        if(trades!=null)for(int i=0;i<trades.length();i++){
            JSONObject t=trades.optJSONObject(i);if(t==null)continue;
            b.append("\n").append(i+1).append(". ").append(t.optString("symbol","—")).append(" ").append(t.optString("side","—"))
             .append(" • ").append(t.optString("status","—"));
            b.append("\n   Вход: ").append(t.optString("entry_price","—")).append(" • выход: ").append(t.optString("exit_price","—"));
            b.append("\n   Объём: ").append(t.optString("qty","—")).append(" • P/L: ").append(t.optString("pnl","—"))
             .append(" • комиссия: ").append(t.optString("fees","—"));
            b.append("\n   Время: ").append(t.optString("opened_at","—")).append(" → ").append(t.optString("closed_at","—"));
            if(!t.optString("entry_reason","").isEmpty())b.append("\n   Причина входа: ").append(t.optString("entry_reason"));
            if(!t.optString("exit_reason","").isEmpty())b.append("\n   Причина выхода: ").append(t.optString("exit_reason"));
        }
        JSONArray positions=report.optJSONArray("positions");
        if(positions!=null&&positions.length()>0){
            b.append("\n\nОТКРЫТЫЕ ПОЗИЦИИ");
            for(int i=0;i<positions.length();i++){JSONObject p=positions.optJSONObject(i);if(p!=null)b.append("\n").append(p.optString("symbol")).append(" • qty ").append(p.optString("qty")).append(" • вход ").append(p.optString("entry_price")).append(" • SL ").append(p.optString("stop_price")).append(" • TP ").append(p.optString("target_price"));}
        }
        JSONArray events=report.optJSONArray("events");
        b.append("\n\nЖУРНАЛ СОБЫТИЙ (").append(events==null?0:events.length()).append(")");
        if(events!=null)for(int i=0;i<events.length();i++){
            JSONObject e=events.optJSONObject(i);if(e!=null)b.append("\n").append(e.optString("created_at","")).append(" [").append(e.optString("level","INFO")).append("] ").append(e.optString("event_type","")).append(": ").append(e.optString("message",""));
        }
        return b.toString();
    }

    private void loadMarket(){
        if(loading)return;loading=true;final String requested=symbol;
        io.execute(()->{
            JSONArray rows=null;JSONObject ticker=null;String error=null;
            try{
                ticker=getJson(API+"/v5/market/tickers?category=spot&symbol="+requested).getJSONObject("result").getJSONArray("list").getJSONObject(0);
                rows=getJson(API+"/v5/market/kline?category=spot&symbol="+requested+"&interval=1&limit=100").getJSONObject("result").getJSONArray("list");
            }catch(Exception e){error=e.getMessage();}
            final JSONArray rr=rows;final JSONObject tt=ticker;final String ee=error;
            runOnUiThread(()->{
                loading=false;if(isFinishing()||!requested.equals(symbol))return;
                if(rr==null||tt==null){liveText.setText("● OFFLINE");liveText.setTextColor(RED);statusText.setText("Нет котировок Bybit: "+ee);return;}
                try{
                    JSONArray ordered=new JSONArray();for(int i=rr.length()-1;i>=0;i--)ordered.put(rr.getJSONArray(i));
                    // Discard the still-forming candle; only closed bars feed the strategy.
                    if(ordered.length()>1)ordered.remove(ordered.length()-1);
                    candles=ordered;lastPrice=Double.parseDouble(tt.getString("lastPrice"));
                    long latestClosed=Long.parseLong(candles.getJSONArray(candles.length()-1).getString(0));
                    boolean marketFresh=System.currentTimeMillis()-latestClosed<=180000L;
                    liveText.setText(marketFresh?"● LIVE":"● STALE");
                    liveText.setTextColor(marketFresh?GREEN:RED);
                    priceText.setText(String.format(Locale.US,"%s  %,.4f",requested,lastPrice));
                    double change=Double.parseDouble(tt.optString("price24hPcnt","0"))*100.0;
                    changeText.setText(String.format(Locale.US,"24ч: %+.2f%%",change));
                    changeText.setTextColor(change>=0?GREEN:RED);
                    priceChart.setCandles(candles);
                    evaluateStrategy();
                }catch(Exception e){statusText.setText("Ошибка обработки данных: "+e.getMessage());}
            });
        });
    }
    private JSONObject getJson(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("GET");c.setConnectTimeout(10000);c.setReadTimeout(12000);
        c.setRequestProperty("Accept","application/json");
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();if(in!=null){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();}
        c.disconnect();if(code<200||code>=300)throw new Exception("HTTP "+code);
        return new JSONObject(out.toString("UTF-8"));
    }
    private static double ema(double[] v,int period){
        double k=2.0/(period+1.0),e=v[0];for(int i=1;i<v.length;i++)e=v[i]*k+e*(1-k);return e;
    }
    private static double rsi(double[] v,int period){
        double up=0,down=0;for(int i=1;i<=period;i++){double d=v[i]-v[i-1];if(d>0)up+=d;else down-=d;}
        up/=period;down/=period;for(int i=period+1;i<v.length;i++){double d=v[i]-v[i-1];up=(up*(period-1)+Math.max(0,d))/period;down=(down*(period-1)+Math.max(0,-d))/period;}
        return down==0?100:100-100/(1+up/down);
    }
    private int score(){
        if(candles.length()<30)return 0;
        try{
            double[] close=new double[candles.length()];
            for(int i=0;i<candles.length();i++)close[i]=Double.parseDouble(candles.getJSONArray(i).getString(4));
            double e9=ema(close,9),e21=ema(close,21),r=rsi(close,14);
            double mom=(close[close.length-1]-close[close.length-4])/close[close.length-4]*100;
            int s=0;if(e9>e21)s+=2;else if(e9<e21)s-=2;
            if(mom>0.03)s++;else if(mom< -0.03)s--;
            if(r>=52&&r<=68)s++;else if(r>=32&&r<=48)s--;
            return s;
        }catch(Exception e){return 0;}
    }
    private void resetDailyLossIfNeeded(){
        String today=new SimpleDateFormat("yyyyMMdd",Locale.US).format(new Date());
        if(!today.equals(prefs.getString("loss_day",""))){
            prefs.edit().putString("loss_day",today).putFloat("loss_today",0).apply();
        }
    }
    private void evaluateStrategy(){
        resetDailyLossIfNeeded();
        if(candles.length()<30){
            statusText.setText("Недостаточно закрытых минутных свечей. Новые входы заблокированы.");
            // Keep protective stop/target checks alive even if indicator history is incomplete.
            managePaperTrade(0,0L);renderLedger();return;
        }
        long candleTime;
        try{
            candleTime=Long.parseLong(candles.getJSONArray(candles.length()-1).getString(0));
            if(System.currentTimeMillis()-candleTime>180000L){
                statusText.setText("Последняя закрытая свеча устарела. Новые автосделки заблокированы.");
                // A stale signal must never open/reverse a position, but live-price
                // stop-loss and take-profit checks must continue to run.
                managePaperTrade(0,candleTime);renderLedger();return;
            }
        }catch(Exception e){
            statusText.setText("Не удалось проверить время свечи. Новые автосделки заблокированы.");
            managePaperTrade(0,0L);renderLedger();return;
        }
        int s=score();String dir=s>=3?"ПОКУПКА":s<=-3?"СЛАБЫЙ ТРЕНД / ВЫХОД":"НЕТ СИГНАЛА";
        signalText.setText("АНАЛИЗ: "+dir+"  •  SCORE "+s);
        signalText.setTextColor(s>=3?GREEN:s<=-3?RED:WHITE);
        statusText.setText("EMA 9/21 + RSI(14) + импульс. Балл — эвристика, не вероятность прибыли.");
        // Position exits are checked on every quote refresh, but a new entry is allowed
        // only once per completed candle. This prevents duplicate entries on 15s polling.
        managePaperTrade(s,candleTime);renderLedger();
    }
    private void managePaperTrade(int s,long candleTime){
        boolean open=prefs.getBoolean("open",false);
        if(open){
            // Remember the candle that was already occupied so a stop/exit cannot
            // immediately trigger a second entry from the same unchanged signal.
            if(candleTime>0)prefs.edit().putLong("last_entry_signal_candle",candleTime).apply();
            double entry=prefs.getFloat("entry",0),stake=prefs.getFloat("stake",0);
            if(entry<=0||stake<=0)return;
            double pnl=(lastPrice-entry)/entry*stake;
            boolean stop=lastPrice<=entry*(1-STOP_PCT),take=lastPrice>=entry*(1+TAKE_PCT),reverse=s<=-3;
            if(stop||take||reverse){
                double cash=prefs.getFloat("cash",(float)START_BALANCE);
                double lossToday=prefs.getFloat("loss_today",0)+(pnl<0?-pnl:0);
                boolean stopAuto=lossToday>=MAX_DAILY_LOSS;
                prefs.edit().putFloat("cash",(float)(cash+stake+pnl))
                    .putFloat("loss_today",(float)lossToday).putBoolean("auto",stopAuto?false:autoEnabled)
                    .putBoolean("open",false)
                    .putString("last",String.format(Locale.US,"%s • %s • вход %.4f • выход %.4f • P/L %+.2f USDT",symbol,stop?"STOP":take?"TAKE PROFIT":"СИГНАЛ ЗАКРЫТ",entry,lastPrice,pnl)).apply();
                if(stopAuto){autoEnabled=false;statusText.setText("Достигнут дневной лимит убытка 200 USDT. Автоторговля остановлена.");}
            }
        }else if(autoEnabled&&s>=3&&lastPrice>0
                &&candleTime!=prefs.getLong("last_entry_signal_candle",0)){
            double cash=prefs.getFloat("cash",(float)START_BALANCE);
            if(cash>=STAKE){
                prefs.edit().putFloat("cash",(float)(cash-STAKE)).putFloat("entry",(float)lastPrice)
                    .putFloat("stake",(float)STAKE).putString("trade_symbol",symbol).putBoolean("open",true)
                    .putLong("last_entry_signal_candle",candleTime)
                    .putString("last",String.format(Locale.US,"%s • BUY %.4f • объём 100 USDT • SL %.4f • TP %.4f",symbol,lastPrice,lastPrice*(1-STOP_PCT),lastPrice*(1+TAKE_PCT))).apply();
            }
        }
    }
    private void renderLedger(){
        if(balanceText==null)return;
        double cash=prefs.getFloat("cash",(float)START_BALANCE);
        boolean open=prefs.getBoolean("open",false);
        balanceText.setText(String.format(Locale.US,"%,.2f USDT",cash));
        modeText.setText(autoEnabled?"АВТО ДЕМО":"РУЧНОЙ ДЕМО");
        autoButton.setText(autoEnabled?"ОСТАНОВИТЬ АВТОДЕМО":"ЗАПУСТИТЬ АВТОДЕМО");
        autoButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(autoEnabled?GREEN:GOLD));
        String last=prefs.getString("last","Сделок пока нет.");
        if(open)last+="\n\nОТКРЫТАЯ ПОЗИЦИЯ: "+prefs.getString("trade_symbol",symbol)+" • вход "+String.format(Locale.US,"%.4f",prefs.getFloat("entry",0))+" • стоп 0,7% / цель 1,4%";
        historyText.setText(last);
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(poll);handler.post(poll);refreshServerReport();}
    @Override protected void onPause(){super.onPause();handler.removeCallbacks(poll);}
    @Override protected void onDestroy(){handler.removeCallbacks(poll);io.shutdownNow();super.onDestroy();}

    private final class PriceChart extends View{
        private final Paint p=new Paint(3);private JSONArray data=new JSONArray();
        PriceChart(){super(BybitTerminalActivity.this);setBackgroundColor(PANEL);}
        void setCandles(JSONArray d){data=d;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight();p.setStrokeWidth(1);p.setColor(0xFF29405B);
            for(int i=1;i<4;i++)c.drawLine(0,h*i/4,w,h*i/4,p);
            if(data.length()<2)return;
            double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
            for(int i=0;i<data.length();i++){try{JSONArray row=data.getJSONArray(i);min=Math.min(min,Double.parseDouble(row.getString(3)));max=Math.max(max,Double.parseDouble(row.getString(2)));}catch(Exception ignored){}}
            double range=Math.max(1e-9,max-min);float top=dp(18),bottom=h-dp(18),plotH=bottom-top;
            float slot=w/data.length(),bodyW=Math.max(dp(2),slot*0.58f);
            for(int i=0;i<data.length();i++){
                try{
                    JSONArray row=data.getJSONArray(i);
                    double open=Double.parseDouble(row.getString(1)),high=Double.parseDouble(row.getString(2));
                    double low=Double.parseDouble(row.getString(3)),close=Double.parseDouble(row.getString(4));
                    float x=(i+0.5f)*slot;
                    float yHigh=(float)(bottom-(high-min)/range*plotH),yLow=(float)(bottom-(low-min)/range*plotH);
                    float yOpen=(float)(bottom-(open-min)/range*plotH),yClose=(float)(bottom-(close-min)/range*plotH);
                    p.setColor(close>=open?GREEN:RED);p.setStrokeWidth(Math.max(1f,1.2f*getResources().getDisplayMetrics().density));
                    c.drawLine(x,yHigh,x,yLow,p);
                    float bodyTop=Math.min(yOpen,yClose),bodyBottom=Math.max(yOpen,yClose);
                    if(bodyBottom-bodyTop<dp(2))bodyBottom=bodyTop+dp(2);
                    c.drawRect(x-bodyW/2,bodyTop,x+bodyW/2,bodyBottom,p);
                }catch(Exception ignored){}
            }
            p.setColor(MUTED);p.setTextSize(dp(10));
            c.drawText(String.format(Locale.US,"%.4f",max),dp(7),dp(13),p);
            c.drawText(String.format(Locale.US,"%.4f",min),dp(7),h-dp(4),p);
        }
    }
}