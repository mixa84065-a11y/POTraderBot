package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
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
    private static final int BG=Color.rgb(5,7,11), PANEL=Color.rgb(15,17,22);
    private static final int GOLD=Color.rgb(247,190,55), WHITE=Color.rgb(242,242,245);
    private static final int MUTED=Color.rgb(151,154,163), GREEN=Color.rgb(0,190,85), RED=Color.rgb(224,43,43);
    private static final String API="https://api.bybit.com";
    private static final double START_BALANCE=20000.0, STAKE=100.0, STOP_PCT=0.007, TAKE_PCT=0.014, MAX_DAILY_LOSS=200.0;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String accessToken="", refreshToken="";
    private TextView serverReportText, connectionStatusText, paperAutoReportText, paperStatsHeader, paperStatsText;
    private Button paperStartButton, paperStopButton, paperPrepareButton, bybitPrepareButton;
    private boolean preparePaperRequested=false, prepareBybitRequested=false;
    private boolean paperStopSent=false, bybitStopSent=false;
    private String preparePaperId="", prepareBybitId="", activeBybitRunId="";
    // A preparation is only a display draft; never mutate/delete server history.
    private String paperDraftPreviousId=null, bybitDraftPreviousId=null;
    private String activePaperRunId="";
    private boolean paperReportLoading=false, serverReportLoading=false;
    private boolean paperStarting=false, serverStarting=false;
    private int paperReportGeneration=0, serverReportGeneration=0;
    private String expectedPaperRunId="", expectedBybitRunId="";
    private Button serverRefreshButton, connectionCheckButton, startDemoRunButton;
    private Spinner symbolPicker, scanCategoryPicker;
    private TextView multiScanText;
    private Button multiScanButton;
    private TextView priceText, changeText, balanceText, modeText, signalText, riskText, historyText, statusText, liveText;
    private Button autoButton;
    private PriceChart priceChart;
    private double chartEntryPrice=0;
    private ScrollView terminalScroll;
    private String symbol="BTCUSDT";
    private boolean loading=false, autoEnabled=false;
    private JSONArray candles=new JSONArray();
    private double lastPrice=0;
    private final Runnable poll=new Runnable(){@Override public void run(){loadMarket();handler.postDelayed(this,15000);}};
    private final Runnable paperStatusPoll=new Runnable(){@Override public void run(){refreshPaperAutoReport();refreshServerReport();handler.postDelayed(this,60000);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        applyImmersiveMode();
        accessToken=getIntent().getStringExtra("access_token");
        if(accessToken==null)accessToken="";
        refreshToken=getIntent().getStringExtra("refresh_token");
        if(refreshToken==null)refreshToken="";
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
    private void applyImmersiveMode(){
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus)applyImmersiveMode();
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,1);return t;
    }
    private GradientDrawable surface(int color,int radiusDp,int strokeColor){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(radiusDp));
        if(strokeColor!=0)d.setStroke(dp(1),strokeColor);
        return d;
    }
    private void styleCard(View view,int paddingDp){
        view.setBackground(surface(PANEL,14,0xFF51401D));
        view.setElevation(dp(2));
        if(view instanceof TextView)((TextView)view).setPadding(dp(paddingDp),dp(paddingDp),dp(paddingDp),dp(paddingDp));
    }
    private Button button(String s,int bg,int fg){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);b.setTypeface(null,1);
        b.setTextColor(fg);b.setMinHeight(dp(44));b.setPadding(dp(12),dp(8),dp(12),dp(8));
        b.setBackground(surface(bg,12,bg==GOLD?0:0xFF51401D));b.setElevation(dp(2));return b;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void buildScreen(){
        LinearLayout root=vertical();root.setPadding(dp(16),dp(16),dp(16),dp(24));root.setBackgroundColor(BG);
        LinearLayout top=horizontal();
        TextView title=text("♛  GOD OF TRADING 2.0",19,GOLD,true);
        top.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        Button back=button("НАЗАД",PANEL,WHITE);top.addView(back,new LinearLayout.LayoutParams(-2,dp(40)));
        back.setOnClickListener(v->finish());root.addView(top);
        TextView sub=text("BYBIT  /  ПРОФЕССИОНАЛЬНЫЙ ТЕРМИНАЛ",10,MUTED,true);sub.setPadding(0,dp(2),0,dp(10));root.addView(sub);
        LinearLayout select=horizontal();
        TextView pairLabel=text("ИНСТРУМЕНТ",10,MUTED,true);select.addView(pairLabel);
        symbolPicker=new Spinner(this);
        symbolPicker.setBackground(surface(PANEL,10,0xFF51401D));symbolPicker.setPadding(dp(8),0,dp(8),0);
        String[] symbols={"BTCUSDT","ETHUSDT","SOLUSDT","XRPUSDT","BNBUSDT","DOGEUSDT"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,symbols);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);symbolPicker.setAdapter(adapter);
        select.addView(symbolPicker,new LinearLayout.LayoutParams(0,dp(44),1));
        root.addView(select);
        LinearLayout multiScan=horizontal();
        scanCategoryPicker=new Spinner(this);
        scanCategoryPicker.setBackground(surface(PANEL,10,0xFF51401D));scanCategoryPicker.setPadding(dp(8),0,dp(8),0);
        String[] scanCategories={"ВСЕ РЫНКИ • ОБЩИЙ АНАЛИЗ","Спот","USDT фьючерсы","Inverse фьючерсы","Опционы","TradFi: акции","TradFi: ETF","TradFi: Forex","TradFi: сырьё","CFD (MT5 — подключение отдельно)"};
        ArrayAdapter<String> scanAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,scanCategories);
        scanAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scanCategoryPicker.setAdapter(scanAdapter);
        multiScan.addView(scanCategoryPicker,new LinearLayout.LayoutParams(0,dp(46),1));
        multiScanButton=button("СКАНИРОВАТЬ РЫНОК",GOLD,BG);
        multiScan.addView(multiScanButton,new LinearLayout.LayoutParams(0,dp(46),1));
        LinearLayout.LayoutParams msp=new LinearLayout.LayoutParams(-1,-2);msp.topMargin=dp(6);root.addView(multiScan,msp);
        multiScanText=text("Мультианализ: нажмите «Сканировать рынок». Будут проверены наиболее ликвидные инструменты Bybit.",11,MUTED,false);
        styleCard(multiScanText,12);root.addView(multiScanText);
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
        LinearLayout quote=horizontal();quote.setPadding(dp(14),dp(14),dp(14),dp(14));quote.setBackground(surface(PANEL,16,0xFF51401D));quote.setElevation(dp(2));
        LinearLayout qleft=vertical();
        priceText=text("Загрузка цены…",25,WHITE,true);qleft.addView(priceText);
        changeText=text("24ч: —",11,MUTED,false);qleft.addView(changeText);
        quote.addView(qleft,new LinearLayout.LayoutParams(0,-2,1));
        liveText=text("● ОЖИДАНИЕ",11,MUTED,true);quote.addView(liveText); 
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-1,-2);qp.topMargin=dp(8);root.addView(quote,qp);
        priceChart=new PriceChart();LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(250));cp.topMargin=dp(8);root.addView(priceChart,cp);
        TextView tf=text("ГРАФИК  /  M1  /  100 ЗАКРЫТЫХ СВЕЧЕЙ",10,MUTED,true);tf.setPadding(dp(3),dp(5),0,dp(5));root.addView(tf);
        LinearLayout wallet=horizontal();wallet.setPadding(dp(14),dp(14),dp(14),dp(14));wallet.setBackground(surface(PANEL,16,0xFF51401D));wallet.setElevation(dp(2));
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
        historyText=text("Сделок пока нет.",12,WHITE,false);styleCard(historyText,12);root.addView(historyText);
        TextView foot=text("Автостратегия — экспериментальная эвристика, не гарантия доходности. Это локальный симулятор, не Bybit Demo API.",10,MUTED,false);
        foot.setPadding(dp(4),dp(12),dp(4),0);root.addView(foot);
        // Keep the autonomous multi-market demo immediately after the local terminal,
        // before the longer 48-hour Bybit Demo section, so it is easy to find.
        buildMultiMarketPaperSection(root);        buildServerReport(root);
        terminalScroll=new ScrollView(this);terminalScroll.setFillViewport(true);terminalScroll.setBackgroundColor(BG);terminalScroll.addView(root);
        LinearLayout shell=vertical();shell.setBackgroundColor(BG);
        shell.addView(terminalScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=horizontal();nav.setPadding(dp(7),dp(7),dp(7),dp(7));nav.setBackground(surface(PANEL,15,0xFF3B321E));
        String[] tabs={"ОБЗОР","РЫНКИ","АНАЛИЗ","АВТОДЕМО"};
        for(int i=0;i<tabs.length;i++){
            final int tab=i;Button item=button(tabs[i],i==1?GOLD:PANEL,i==1?BG:WHITE);
            item.setTextSize(9);item.setMinHeight(dp(46));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.leftMargin=dp(2);lp.rightMargin=dp(2);nav.addView(item,lp);
            item.setOnClickListener(v->{
                if(tab==0)finish();
                else if(tab==1)terminalScroll.smoothScrollTo(0,Math.max(0,multiScanText.getTop()));
                else if(tab==2)terminalScroll.smoothScrollTo(0,Math.max(0,signalText.getTop()));
                else terminalScroll.post(()->terminalScroll.smoothScrollTo(0,Math.max(0,paperStartButton.getTop())));
            });
        }
        LinearLayout.LayoutParams navLp=new LinearLayout.LayoutParams(-1,-2);navLp.leftMargin=dp(8);navLp.rightMargin=dp(8);navLp.bottomMargin=dp(8);shell.addView(nav,navLp);
        setContentView(shell);
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
        startDemoRunButton=button("ЗАПУСТИТЬ ДЕМО НА 48 ЧАСОВ",GOLD,BG);
        LinearLayout.LayoutParams startParams=new LinearLayout.LayoutParams(-1,dp(52));startParams.topMargin=dp(8);root.addView(startDemoRunButton,startParams);
        startDemoRunButton.setOnClickListener(v->confirmStartBybitDemoRun());
        startDemoRunButton.setEnabled(false);
        bybitPrepareButton=button("ОБНУЛИТЬ СТАТИСТИКУ / НОВЫЙ ТЕСТ",PANEL,WHITE);
        root.addView(bybitPrepareButton,new LinearLayout.LayoutParams(-1,dp(48)));
        bybitPrepareButton.setEnabled(false);
        bybitPrepareButton.setOnClickListener(v->confirmPrepareDemo(false));
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




    private void buildMultiMarketPaperSection(LinearLayout root){
        TextView heading=text("АВТОНОМНОЕ МУЛЬТИРЫНОЧНОЕ ДЕМО",13,GOLD,true);
        heading.setPadding(dp(4),dp(20),0,dp(8));root.addView(heading);
        TextView note=text("Отдельный серверный бумажный портфель: 200 USDT виртуально. Spot, USDT Futures, Inverse, Options, акции, ETF, Forex и сырьё Bybit TradFi. Цикл каждую минуту, работает при закрытом приложении. Только виртуальные сделки — ключи Bybit не используются.",10,MUTED,false);
        note.setPadding(dp(4),0,dp(4),dp(8));root.addView(note);
        paperStartButton=button("ЗАПУСТИТЬ АВТОТОРГОВЛЮ ПО ВСЕМ РЫНКАМ",GOLD,BG);
        root.addView(paperStartButton,new LinearLayout.LayoutParams(-1,dp(50)));
        paperStartButton.setOnClickListener(v->confirmStartMultiMarketPaperRun());
        paperStartButton.setEnabled(false);
        paperPrepareButton=button("ОБНУЛИТЬ СТАТИСТИКУ / НОВЫЙ ТЕСТ",PANEL,WHITE);
        root.addView(paperPrepareButton,new LinearLayout.LayoutParams(-1,dp(48)));
        paperPrepareButton.setEnabled(false);
        paperPrepareButton.setOnClickListener(v->confirmPrepareDemo(true));
        paperStopButton=button("ОСТАНОВИТЬ АВТОТОРГОВЛЮ",PANEL,WHITE);
        LinearLayout.LayoutParams stopLp=new LinearLayout.LayoutParams(-1,dp(46));stopLp.topMargin=dp(6);root.addView(paperStopButton,stopLp);
        paperStopButton.setOnClickListener(v->stopMultiMarketPaperRun());
        paperStopButton.setEnabled(false);
        Button refresh=button("ОБНОВИТЬ ОТЧЁТ МУЛЬТИРЫНКА",PANEL,WHITE);
        LinearLayout.LayoutParams refreshLp=new LinearLayout.LayoutParams(-1,dp(44));refreshLp.topMargin=dp(6);root.addView(refresh,refreshLp);
        refresh.setOnClickListener(v->refreshPaperAutoReport());
        paperStatsHeader=text("СТАТИСТИКА МУЛЬТИТОРГОВЛИ",13,GOLD,true);
        paperStatsHeader.setPadding(dp(4),dp(14),0,dp(5));root.addView(paperStatsHeader);
        paperStatsText=text("Статистика появится после первых закрытых сделок.",11,WHITE,false);
        styleCard(paperStatsText,12);
        root.addView(paperStatsText,new LinearLayout.LayoutParams(-1,-2));
        paperStatsText.setVisibility(View.VISIBLE);
        paperAutoReportText=text("Проверяем состояние автономного автодемо…",11,WHITE,false);
        styleCard(paperAutoReportText,12);
        LinearLayout.LayoutParams reportLp=new LinearLayout.LayoutParams(-1,-2);reportLp.topMargin=dp(8);root.addView(paperAutoReportText,reportLp);
        refreshPaperAutoReport();
    }

    private void confirmPrepareDemo(boolean paper){
        if(accessToken.isEmpty())return;
        if(paper ? (paperStarting||preparePaperRequested) : (serverStarting||prepareBybitRequested))return;
        if(paper ? paperDraftPreviousId!=null : bybitDraftPreviousId!=null){
            if(paper){paperDraftPreviousId=null;preparePaperRequested=false;paperReportGeneration++;refreshPaperAutoReport();}
            else{bybitDraftPreviousId=null;prepareBybitRequested=false;serverReportGeneration++;refreshServerReport();}
            return;
        }
        final String confirmedId=paper?activePaperRunId:activeBybitRunId;
        new AlertDialog.Builder(this).setTitle("Завершить демотест и подготовить новый?")
            .setMessage("Если тест активен, сервер запретит новые входы и закроет его открытые демопозиции по доступным рыночным ценам. Дождёмся сверки сделок, заявок и комиссий, затем покажем нулевую статистику будущего теста. История сохраняется, демо-баланс Bybit не сбрасывается. Новый тест запускается отдельно.")
            .setNegativeButton("ОТМЕНА",null).setPositiveButton("ПОДГОТОВИТЬ",(d,w)->{
                if(paper){preparePaperRequested=true;preparePaperId=confirmedId;paperStopSent=false;paperReportGeneration++;paperPrepareButton.setEnabled(false);refreshPaperAutoReport();}
                else{prepareBybitRequested=true;prepareBybitId=confirmedId;bybitStopSent=false;serverReportGeneration++;bybitPrepareButton.setEnabled(false);refreshServerReport();}
            }).show();
    }

    private void requestStopForPreparation(boolean paper,String runId){
        if(paper)paperStopSent=true;else bybitStopSent=true;
        io.execute(()->{
            String error=null;
            try{
                String token=accessToken;
                JSONObject response;
                try{response=paper?SupabaseApi.stopMultiMarketPaperRun(token,runId):SupabaseApi.stopBybitDemoRun(token,runId);}
                catch(Exception first){
                    if(!isExpiredJwt(first))throw first;
                    token=refreshExpiredSession(token);
                    response=paper?SupabaseApi.stopMultiMarketPaperRun(token,runId):SupabaseApi.stopBybitDemoRun(token,runId);
                }
                if(response==null||!runId.equals(response.optString("run_id"))||!"STOPPING".equals(response.optString("status")))
                    throw new Exception("Сервер не подтвердил остановку");
            }catch(Exception e){error=e.getMessage()==null?"Ошибка остановки":e.getMessage();}
            final String problem=error;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                if(problem!=null){
                    if(paper){preparePaperRequested=false;paperStopSent=false;}
                    else{prepareBybitRequested=false;bybitStopSent=false;}
                    Toast.makeText(this,"Остановка не подтверждена: "+problem+". Проверьте отчёт.",Toast.LENGTH_LONG).show();
                }
                if(paper)refreshPaperAutoReport();else refreshServerReport();
            });
        });
    }

    private void renderPaperDraft(){
        paperPrepareButton.setText("ПОКАЗАТЬ ПРЕДЫДУЩИЙ ОТЧЁТ");
        paperStatsText.setText(DemoReportPreparation.zeroStatistics());
        paperAutoReportText.setText("НОВЫЙ МУЛЬТИРЫНОЧНЫЙ ТЕСТ • НЕ ЗАПУЩЕН\nВиртуальный старт после запуска: 200 USDT\nПредыдущий отчёт сохранён на сервере.\nДля запуска нажмите кнопку запуска и подтвердите действие.");
    }

    private void renderBybitDraft(){
        bybitPrepareButton.setText("ПОКАЗАТЬ ПРЕДЫДУЩИЙ ОТЧЁТ");
        serverReportText.setText("НОВЫЙ BYBIT DEMO ТЕСТ • НЕ ЗАПУЩЕН\n"+DemoReportPreparation.zeroStatistics()+"\nДемо-баланс Bybit не обнуляется. Предыдущий отчёт сохранён на сервере.\nДля запуска нажмите кнопку запуска и подтвердите действие.");
    }

    private void confirmStartMultiMarketPaperRun(){
        if(accessToken.isEmpty()){Toast.makeText(this,"Сначала войдите в GOD OF TRADING.",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("Запуск автономного автодемо")
            .setMessage("Запустится отдельный виртуальный портфель 200 USDT. Сервер будет анализировать все поддерживаемые рынки Bybit каждую минуту, открывать несколько виртуальных позиций и закрывать их по стопу/цели. Реальные ордера и средства не используются. Продолжить?")
            .setNegativeButton("ОТМЕНА",null).setPositiveButton("ЗАПУСТИТЬ",(d,w)->startMultiMarketPaperRun()).show();
    }

    private void startMultiMarketPaperRun(){
        if(paperStarting)return;
        paperStarting=true;preparePaperRequested=false;paperReportGeneration++;
        paperPrepareButton.setEnabled(false);
        if(paperStartButton!=null)paperStartButton.setEnabled(false);
        if(paperStopButton!=null)paperStopButton.setEnabled(false);
        if(paperStatsText!=null)paperStatsText.setText("Ожидаем подтверждение нового запуска…");
        if(paperAutoReportText!=null)paperAutoReportText.setText("Создаём автономный виртуальный портфель…");
        io.execute(()->{
            JSONObject result=null;String error=null;
            try{result=SupabaseApi.startMultiMarketPaperRun(accessToken);}catch(Exception e){error=e.getMessage();}
            final JSONObject started=result;final String problem=error;
            runOnUiThread(()->{
                if(isFinishing())return;
                paperStarting=false;
                if(started!=null&&!started.optString("run_id","").isEmpty()){
                    paperDraftPreviousId=null;
                    expectedPaperRunId=started.optString("run_id");activePaperRunId=expectedPaperRunId;
                    Toast.makeText(this,"Новый серверный тест подтверждён",Toast.LENGTH_LONG).show();
                }else{
                    if(paperStatsText!=null)paperStatsText.setText("Новый тест не подтверждён. Старые данные не являются результатом нового теста.");
                    Toast.makeText(this,"Не удалось запустить: "+problem,Toast.LENGTH_LONG).show();
                }
                refreshPaperAutoReport();
            });
        });
    }

    private void stopMultiMarketPaperRun(){
        if(accessToken.isEmpty()||activePaperRunId.isEmpty()){
            Toast.makeText(this,"Нет активного запуска для остановки.",Toast.LENGTH_SHORT).show();return;
        }
        new AlertDialog.Builder(this).setTitle("Остановить автодемо?")
            .setMessage("Новые позиции больше открываться не будут. Сервер запросит закрытие открытых демопозиций по доступным рыночным ценам.")
            .setNegativeButton("НЕТ",null).setPositiveButton("ОСТАНОВИТЬ",(d,w)->{
                if(paperStopButton!=null)paperStopButton.setEnabled(false);
                io.execute(()->{
                    String error=null;try{SupabaseApi.stopMultiMarketPaperRun(accessToken,activePaperRunId);}catch(Exception e){error=e.getMessage();}
                    final String problem=error;
                    runOnUiThread(()->{if(paperStopButton!=null)paperStopButton.setEnabled(true);if(problem!=null)Toast.makeText(this,"Ошибка остановки: "+problem,Toast.LENGTH_LONG).show();refreshPaperAutoReport();});
                });
            }).show();
    }

    private boolean isExpiredJwt(Exception error){
        String message=error==null?"":String.valueOf(error.getMessage()).toLowerCase(Locale.US);
        return message.contains("jwt expired")||message.contains("token expired")||message.contains("invalid jwt")||message.contains("pgrst301");
    }

    private synchronized String refreshExpiredSession(String failedToken) throws Exception {
        if(!accessToken.isEmpty()&&!accessToken.equals(failedToken))return accessToken;
        if(refreshToken.isEmpty())throw new Exception("Сессия истекла. Войдите в аккаунт заново.");
        JSONObject refreshed=SupabaseApi.refresh(refreshToken);
        String newAccess=refreshed.optString("access_token","");
        String newRefresh=refreshed.optString("refresh_token",refreshToken);
        if(newAccess.isEmpty())throw new Exception("Не удалось обновить сессию. Войдите в аккаунт заново.");
        SessionStore.save(this,newAccess,newRefresh);
        accessToken=newAccess;refreshToken=newRefresh;
        return accessToken;
    }

    private JSONObject getMultiMarketReportWithRefresh(String expectedId) throws Exception {
        String token=accessToken;
        try{return SupabaseApi.getMultiMarketPaperReport(token, expectedId);}
        catch(Exception first){
            if(!isExpiredJwt(first))throw first;
            token=refreshExpiredSession(token);
            return SupabaseApi.getMultiMarketPaperReport(token, expectedId);
        }
    }

    private JSONObject getBybitReportWithRefresh(String expectedId) throws Exception {
        String token=accessToken;
        try{return SupabaseApi.getBybitDemoReport(token, expectedId);}
        catch(Exception first){
            if(!isExpiredJwt(first))throw first;
            token=refreshExpiredSession(token);
            return SupabaseApi.getBybitDemoReport(token, expectedId);
        }
    }

    private void refreshPaperAutoReport(){
        if(accessToken.isEmpty()||paperReportLoading||paperStarting||paperAutoReportText==null)return;
        paperReportLoading=true;
        paperPrepareButton.setEnabled(false);
        paperStartButton.setEnabled(false);
        final int generation=paperReportGeneration;
        final String expectedId=expectedPaperRunId;
        io.execute(()->{
            JSONObject report=null;String error=null;
            try{report=getMultiMarketReportWithRefresh(expectedId);}catch(Exception e){error=e.getMessage();}
            final JSONObject data=report;final String problem=error;
            runOnUiThread(()->{
                paperReportLoading=false;if(isFinishing()||paperAutoReportText==null)return;
                if(generation!=paperReportGeneration){refreshPaperAutoReport();return;}
                if(data==null){paperAutoReportText.setText("Не удалось получить актуальный отчёт: "+problem+(preparePaperRequested?"\nЗавершение ещё не подтверждено; статистика не сброшена.":""));paperStatsText.setText("Статистика недоступна: нет подтверждения сервера.");return;}
                if(!data.optBoolean("has_run",false)){
                    if(!expectedId.isEmpty()){paperStatsText.setText("Сервер ещё не вернул новый тест: "+expectedId);paperAutoReportText.setText("Ожидаем отчёт нового запуска.");return;}
                    activePaperRunId="";
                    paperStartButton.setEnabled(true);paperStopButton.setEnabled(false);paperPrepareButton.setEnabled(true);
                    if(preparePaperRequested&&preparePaperId.isEmpty())paperDraftPreviousId="";
                    else if(preparePaperRequested)Toast.makeText(this,"Прежний запуск не найден. Сброс отменён.",Toast.LENGTH_LONG).show();
                    preparePaperRequested=false;
                    if(DemoReportPreparation.keepDraft(paperDraftPreviousId,"",true)){renderPaperDraft();return;}
                    paperDraftPreviousId=null;paperPrepareButton.setText("ОБНУЛИТЬ СТАТИСТИКУ / НОВЫЙ ТЕСТ");
                    paperAutoReportText.setText("Автодемо ещё не запускалось. После запуска сервер начнёт собирать сделки и статистику.");paperStatsText.setText("Нет серверного теста.");return;
                }
                JSONObject run=data.optJSONObject("run");
                if(run==null||run.optString("id","").isEmpty()||(!expectedId.isEmpty()&&!expectedId.equals(run.optString("id")))){
                    paperStatsText.setText("Отчёт не соответствует запрошенному запуску.");paperAutoReportText.setText("Ожидаем подтверждённый отчёт сервера.");return;
                }
                expectedPaperRunId="";activePaperRunId=run.optString("id");
                String status=run.optString("status","UNKNOWN");
                JSONArray serverPositions=data.optJSONArray("positions");
                boolean canPrepare=DemoReportPreparation.canPrepare(status,serverPositions==null?-1:serverPositions.length());
                paperStartButton.setEnabled(canPrepare);
                paperPrepareButton.setEnabled(!preparePaperRequested&&(canPrepare||DemoReportPreparation.canRequestStop(status)||"STOPPING".equals(status)));
                paperStopButton.setEnabled(!preparePaperRequested&&"RUNNING".equals(status));
                paperStopButton.setText("STOPPING".equals(status)?"ЗАКРЫВАЕМ ОТКРЫТЫЕ ПОЗИЦИИ…":"ОСТАНОВИТЬ АВТОТОРГОВЛЮ");
                if(preparePaperRequested){
                    if(!preparePaperId.equals(activePaperRunId)){
                        preparePaperRequested=false;
                        Toast.makeText(this,"Серверный тест изменился. Сброс отменён.",Toast.LENGTH_LONG).show();
                    }else if(canPrepare){
                        paperDraftPreviousId=activePaperRunId;preparePaperRequested=false;
                    }else if(DemoReportPreparation.canRequestStop(status)){
                        if(!paperStopSent)requestStopForPreparation(true,activePaperRunId);
                    }else if(!"STOPPING".equals(status)){
                        preparePaperRequested=false;
                        Toast.makeText(this,"Безопасное завершение не подтверждено.",Toast.LENGTH_LONG).show();
                    }
                }
                paperStartButton.setEnabled(canPrepare&&!preparePaperRequested);
                paperPrepareButton.setEnabled(!preparePaperRequested&&(canPrepare||DemoReportPreparation.canRequestStop(status)||"STOPPING".equals(status)));
                if(DemoReportPreparation.keepDraft(paperDraftPreviousId,activePaperRunId,canPrepare)){renderPaperDraft();return;}
                paperDraftPreviousId=null;paperPrepareButton.setText("ОБНУЛИТЬ СТАТИСТИКУ / НОВЫЙ ТЕСТ");
                StringBuilder b=new StringBuilder("МУЛЬТИРЫНОЧНЫЙ СЕРВЕРНЫЙ ТЕСТ\nСОСТОЯНИЕ: ").append(status)
                    .append("\nRun ID: ").append(activePaperRunId)
                    .append("\nСтарт: ").append(run.optString("started_at","—"))
                    .append("\nОтчёт получен: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new java.util.Date()))
                    .append("\nВиртуальный старт: ").append(run.optString("starting_balance_usdt","—")).append(" USDT")
                    .append("\nРеализованный P/L: ").append(run.optString("realized_pnl_usdt","0")).append(" USDT")
                    .append("\nСвободные средства: ").append(run.optString("cash_usdt","—")).append(" USDT")
                    .append("\nПоследний серверный цикл: ").append(run.optString("last_cycle_at","ещё не выполнялся"));
                JSONObject summary=data.optJSONObject("summary");
                if(summary!=null&&paperStatsText!=null){
                    StringBuilder overview=new StringBuilder("СОСТОЯНИЕ: ").append(status)
                        .append("\nСтартовый капитал: ").append(run.optString("starting_balance_usdt","—")).append(" USDT")
                        .append("\nСвободные средства: ").append(run.optString("cash_usdt","—")).append(" USDT")
                        .append("\nОткрыто: ").append(summary.optInt("open_count")).append(" • Закрыто: ").append(summary.optInt("trades_total"))
                        .append("\nПлюсовых: ").append(summary.optInt("wins")).append(" • Минусовых: ").append(summary.optInt("losses"))
                        .append("\nБезубыточных: ").append(summary.optInt("breakeven"))
                        .append("\nДоля прибыльных: ").append(summary.optInt("trades_total")==0?"—":summary.optString("win_rate_pct")+"%")
                        .append("\nЧистый P/L закрытых: ").append(summary.optString("net_pnl_usdt","—")).append(" USDT")
                        .append("\nТекущий P/L открытых: ").append(summary.optString("unrealized_pnl_usdt","—")).append(" USDT")
                        .append("\nКомиссии закрытых: ").append(summary.optString("fees_usdt","—")).append(" USDT")
                        .append("\nМакс. просадка: ").append(run.optString("max_drawdown_pct","—")).append("%")
                        .append("\nДневной лимит убытка: ").append(run.optString("max_daily_loss_usdt","—")).append(" USDT (0 — отключён)")
                        .append("\nПоследний цикл: ").append(run.optString("last_cycle_at","—"));
                    JSONObject grouped=summary.optJSONObject("markets");
                    JSONArray categories=run.optJSONArray("market_categories");
                    overview.append("\n\nРЕЗУЛЬТАТЫ ПО РЫНКАМ • закрытые сделки");
                    if(categories!=null)for(int j=0;j<categories.length();j++){
                        String market=categories.optString(j);
                        JSONObject group=grouped==null?null:grouped.optJSONObject(market);
                        overview.append("\n").append(market).append(": ");
                        if(group==null)overview.append("0 закрытых");
                        else overview.append(group.optInt("trades_total")).append(" • +").append(group.optInt("wins"))
                            .append(" / −").append(group.optInt("losses")).append(" • P/L ").append(group.optString("net_pnl_usdt","—")).append(" USDT");
                    }
                    paperStatsText.setText(overview.toString());
                }else if(paperStatsText!=null)paperStatsText.setText("Сервер не вернул статистику этого теста.");
                if(!run.isNull("last_error")&&!run.optString("last_error","").isEmpty())b.append("\nПримечание: ").append(run.optString("last_error"));
                JSONArray positions=data.optJSONArray("positions");
                b.append("\n\nОТКРЫТЫЕ ПОЗИЦИИ: ").append(positions==null?0:positions.length());
                if(positions!=null)for(int i=0;i<positions.length();i++){
                    JSONObject p=positions.optJSONObject(i);if(p==null)continue;
                    b.append("\n").append(p.optString("market_category")).append(" • ").append(p.optString("symbol"))
                     .append(" • ").append(p.optString("side")).append(" • вход ").append(p.optString("entry_price"))
                     .append(" • сейчас ").append(p.optString("mark_price")).append(" • P/L ").append(p.optString("unrealized_pnl_usdt"))
                      .append("\n  SL ").append(p.optString("stop_price","—")).append(" • TP ").append(p.optString("target_price","—"))
                      .append(" • объём ").append(p.optString("notional_usdt","—")).append(" USDT");
                }
                JSONArray trades=data.optJSONArray("trades");
                b.append("\n\nПОСЛЕДНИЕ ЗАКРЫТЫЕ СДЕЛКИ (показано ").append(trades==null?0:trades.length()).append(" из ").append(summary==null?"—":summary.optString("trades_total","—")).append(")");
                if(trades!=null)for(int i=0;i<trades.length();i++){
                    JSONObject t=trades.optJSONObject(i);if(t==null)continue;
                    b.append("\n").append(t.optString("market_category")).append(" • ").append(t.optString("symbol"))
                     .append(" • ").append(t.optString("side")).append(" • P/L ").append(t.optString("net_pnl_usdt"))
                     .append(" • ").append(t.optString("exit_reason"));
                }
                JSONArray events=data.optJSONArray("events");
                if(events!=null&&events.length()>0){b.append("\n\nЖУРНАЛ");for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e!=null)b.append("\n").append(e.optString("created_at")).append(" ").append(e.optString("message"));}}
                if(preparePaperRequested)b.insert(0,"ЗАВЕРШАЕМ ДЕМО • ожидаем закрытия позиций и сверки сервера. Статистика пока не сброшена.\n\n");
                paperAutoReportText.setText(b.toString());
            });
        });
    }

    private void confirmStartBybitDemoRun(){
        if(accessToken.isEmpty()){Toast.makeText(this,"Сначала войдите в аккаунт GOD OF TRADING.",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this)
            .setTitle("Запуск Bybit Demo на 48 часов")
            .setMessage("Будут использоваться только демо-средства Bybit. Инструменты: BTCUSDT, ETHUSDT, SOLUSDT. Сервер будет работать независимо от телефона. Продолжить?")
            .setNegativeButton("ОТМЕНА",null)
            .setPositiveButton("ЗАПУСТИТЬ", (dialog,which)->startBybitDemoRun())
            .show();
    }

    private void startBybitDemoRun(){
        if(serverStarting)return;
        serverStarting=true;prepareBybitRequested=false;serverReportGeneration++;
        bybitPrepareButton.setEnabled(false);
        if(startDemoRunButton!=null)startDemoRunButton.setEnabled(false);
        if(serverReportText!=null)serverReportText.setText("Ожидаем подтверждение нового теста…");
        if(connectionStatusText!=null)connectionStatusText.setText("Проверяем Bybit Demo API перед запуском…");
        io.execute(()->{
            JSONObject check=null,started=null;String error=null;
            try{
                check=SupabaseApi.checkBybitDemoConnection(accessToken);
                if(check==null||!check.optBoolean("connected",false))
                    throw new Exception(check==null?"Не удалось проверить Bybit Demo API":check.optString("error","Bybit Demo API не подключён"));
                JSONArray selected=new JSONArray();selected.put("BTCUSDT");selected.put("ETHUSDT");selected.put("SOLUSDT");
                started=SupabaseApi.startBybitDemoRun(accessToken,selected);
            }catch(Exception e){error=e.getMessage();}
            final JSONObject verified=check,run=started;final String problem=error;
            runOnUiThread(()->{
                if(isFinishing())return;
                serverStarting=false;
                if(run!=null&&!run.optString("run_id","").isEmpty()){
                    String id=run.optString("run_id");bybitDraftPreviousId=null;expectedBybitRunId=id;
                    connectionStatusText.setText("48-ЧАСОВЫЙ BYBIT DEMO ТЕСТ ЗАПУЩЕН\nRun ID: "+id+
                        "\nСтарт: "+run.optString("started_at","—")+"\nОкончание: "+run.optString("ends_at","—")+
                        "\nИнструменты: BTCUSDT, ETHUSDT, SOLUSDT\nБаланс при проверке: "+                        (verified==null?"—":verified.optString("wallet_balance_usdt","—"))+" USDT");
                    connectionStatusText.setTextColor(GREEN);
                    Toast.makeText(this,"48-часовой демо-тест запущен",Toast.LENGTH_LONG).show();
                    refreshServerReport();
                }else{
                    connectionStatusText.setText("ЗАПУСК НЕ ВЫПОЛНЕН\n"+(problem==null?"Неизвестная ошибка":problem));
                    connectionStatusText.setTextColor(RED);
                    refreshServerReport();
                }
            });
        });
    }

    private void runMultiScan(){
        if(multiScanButton==null)return;
        final int selected=scanCategoryPicker.getSelectedItemPosition();
        final String category=new String[]{"all","spot","linear","inverse","option","stock","ETF","forex","commodity","cfd"}[selected];
        multiScanButton.setEnabled(false);
        if("all".equals(category)){
            multiScanText.setText("ОБЩИЙ АНАЛИЗ: проверяем Spot, оба типа фьючерсов, опционы и все категории TradFi. Только анализ, без ордеров…");
            io.execute(()->{
                StringBuilder out=new StringBuilder("GOD OF TRADING • ОБЩИЙ ОБЗОР РЫНКОВ\n");
                appendSignalGroup(out,"SPOT",safeScan("spot",3));
                appendSignalGroup(out,"USDT / LINEAR FUTURES",safeScan("linear",3));
                appendSignalGroup(out,"INVERSE FUTURES",safeScan("inverse",3));
                appendSignalGroup(out,"OPTIONS • GREEKS / IV",safeOptionScan(3));
                appendSignalGroup(out,"TRADFI STOCKS",safeTradFiScan("stock",3));
                appendSignalGroup(out,"TRADFI ETF",safeTradFiScan("ETF",3));
                appendSignalGroup(out,"TRADFI FOREX",safeTradFiScan("forex",3));
                appendSignalGroup(out,"TRADFI COMMODITIES",safeTradFiScan("commodity",3));
                out.append("\nCFD: отдельное MT5-подключение; в Bybit V5 не сканируется.\n");
                out.append("\nЭто исследовательские сигналы. Score не является вероятностью выигрыша; автоматические ордера не отправляются.");
                runOnUiThread(()->{if(!isFinishing()){multiScanButton.setEnabled(true);multiScanText.setText(out.toString());}});
            });
            return;
        }
        if("cfd".equals(category)){
            multiScanButton.setEnabled(true);
            multiScanText.setText("CFD НА BYBIT: отдельный MT5 CFD-счёт и отдельный API/интеграционный контур. V5 API для спота/деривативов не предоставляет CFD-каталог. Этот раздел зарезервирован; сначала нужно проверить доступный официальный способ подключения MT5 CFD для вашего аккаунта и региона. Торговля не запускается.");
            return;
        }
        multiScanText.setText("Получаем каталог Bybit и проверяем котировки ("+category+"). Только публичные данные, ордера не отправляются…");
        io.execute(()->{
            java.util.List<BybitMarketScanner.Instrument> catalog=null;
            java.util.List<BybitMarketScanner.Signal> signals=null;String error=null;
            try{
                if("stock".equals(category)||"ETF".equals(category)||"forex".equals(category)||"commodity".equals(category)){
                    catalog=BybitMarketScanner.loadTradFiCatalog(category,30);
                    signals=BybitMarketScanner.scanTradFi(category,30);
                 }else if("option".equals(category)){
                    catalog=BybitMarketScanner.loadInstruments("option");
                    if(catalog.size()>30)catalog=new java.util.ArrayList<>(catalog.subList(0,30));
                    signals=BybitMarketScanner.scanOptions(15);
                }else signals=BybitMarketScanner.scan(category,30);
            }catch(Exception e){error=e.getMessage();}
            final java.util.List<BybitMarketScanner.Instrument> listed=catalog;
            final java.util.List<BybitMarketScanner.Signal> found=signals;final String problem=error;
            runOnUiThread(()->{
                if(isFinishing())return;
                multiScanButton.setEnabled(true);
                if(found==null&&listed==null){multiScanText.setText("Ошибка загрузки Bybit: "+problem);return;}
                String categoryLabel=("stock".equals(category)?"АКЦИИ TRADFI":("ETF".equals(category)?"ETF TRADFI":("forex".equals(category)?"FOREX TRADFI":("commodity".equals(category)?"СЫРЬЁ TRADFI":("option".equals(category)?"ОПЦИОНЫ":category.toUpperCase(java.util.Locale.US))))));
                StringBuilder out=new StringBuilder("BYBIT • "+categoryLabel+"\n");
                if(listed!=null){
                    out.append("КАТАЛОГ ДОСТУПНЫХ ИНСТРУМЕНТОВ (").append(listed.size()).append(")\n");
                    int catalogShown=Math.min(15,listed.size());
                    for(int i=0;i<catalogShown;i++){
                        BybitMarketScanner.Instrument x=listed.get(i);
                        out.append(i+1).append(". ").append(x.displayName).append(" (").append(x.symbol).append(")")
                           .append(x.marketRegion.isEmpty()?"":" • "+x.marketRegion)
                           .append(" • оборот 24ч ").append(String.format(java.util.Locale.US,"%,.0f",x.turnover24h)).append("\n");
                    }
                    if(listed.isEmpty())out.append("Bybit не вернул активные инструменты этой категории.\n");
                    out.append("\nКаталог — публичный список Bybit; возможность торговли зависит от региона, KYC и допуска аккаунта.\n");
                }
                if(found==null){
                    out.append("\nКаталог загружен, но анализ завершился ошибкой: ").append(problem==null?"неизвестная ошибка":problem);
                }else if(found.isEmpty()){
                    out.append("\nСвежих сигналов сейчас нет. Возможная причина — закрытая торговая сессия или устаревшие свечи. Новые входы не рекомендуются.");
                }else{
                    out.append("\nСИГНАЛЫ (").append(found.size()).append(")\n");
                    int shown=Math.min(15,found.size());
                    for(int i=0;i<shown;i++){
                        BybitMarketScanner.Signal x=found.get(i);
                        out.append(i+1).append(". ").append(x.instrument.displayName).append(" (").append(x.instrument.symbol).append(")")
                           .append(x.instrument.marketRegion.isEmpty()?"":" • "+x.instrument.marketRegion).append(" • ").append(x.direction)
                           .append(" • score ").append(x.score).append(" • ").append(String.format(java.util.Locale.US,"%.8f",x.lastPrice)).append("\n");
                    }
                    out.append("\nScore — эвристика, не вероятность дохода.");
                }
                out.append("\nМультианализ не открывает позиции и не отправляет ордера.");
                multiScanText.setText(out.toString());
            });
        });
    }

    private java.util.List<BybitMarketScanner.Signal> safeScan(String category,int limit){
        try{return BybitMarketScanner.scan(category,limit);}catch(Exception ignored){return java.util.Collections.emptyList();}
    }
    private java.util.List<BybitMarketScanner.Signal> safeTradFiScan(String category,int limit){
        try{return BybitMarketScanner.scanTradFi(category,limit);}catch(Exception ignored){return java.util.Collections.emptyList();}
    }
    private java.util.List<BybitMarketScanner.Signal> safeOptionScan(int limit){
        try{return BybitMarketScanner.scanOptions(limit);}catch(Exception ignored){return java.util.Collections.emptyList();}
    }
    private void appendSignalGroup(StringBuilder out,String title,java.util.List<BybitMarketScanner.Signal> signals){
        out.append("\n").append(title).append(" (").append(signals.size()).append(")\n");
        if(signals.isEmpty()){out.append("Нет подтверждённых свежих сигналов.\n");return;}
        for(BybitMarketScanner.Signal signal:signals){
            out.append("• ").append(signal.instrument.symbol).append(" — ").append(signal.direction)
                .append(" | риск: ").append(signal.risk).append(" | score: ").append(signal.score)
                .append(" | цена: ").append(String.format(java.util.Locale.US,"%.8f",signal.lastPrice)).append("\n");
            out.append("  ").append(signal.explanation).append("\n");
        }
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
        if(serverReportText==null||serverReportLoading||serverStarting)return;
        if(accessToken.isEmpty()){serverReportText.setText("Нет активной сессии. Войдите в аккаунт, чтобы открыть серверный отчёт.");return;}
        serverReportLoading=true;
        bybitPrepareButton.setEnabled(false);
        startDemoRunButton.setEnabled(false);
        final int generation=serverReportGeneration;
        final String expectedId=expectedBybitRunId;
        if(serverRefreshButton!=null)serverRefreshButton.setEnabled(false);
        serverReportText.setText("Загружаем данные сервера…");
        io.execute(()->{
            JSONObject report=null;String error=null;
            try{report=getBybitReportWithRefresh(expectedId);}catch(Exception e){error=e.getMessage();}
            final JSONObject result=report;final String problem=error;
            runOnUiThread(()->{
                serverReportLoading=false;
                if(isFinishing())return;
                if(serverRefreshButton!=null)serverRefreshButton.setEnabled(true);
                if(generation!=serverReportGeneration){refreshServerReport();return;}
                if(result==null){serverReportText.setText("Актуальный отчёт недоступен: "+problem+(prepareBybitRequested?"\nЗавершение ещё не подтверждено; статистика не сброшена.":""));return;}
                JSONObject reported=result.optJSONObject("run");
                if(!expectedId.isEmpty()&&(reported==null||!expectedId.equals(reported.optString("id","")))){
                    serverReportText.setText("Ожидаем отчёт именно нового теста: "+expectedId);return;
                }
                expectedBybitRunId="";
                String state=reported==null?"":reported.optString("status","");
                String previousId=reported==null?"":reported.optString("id","");
                activeBybitRunId=previousId;
                JSONObject serverSummary=result.optJSONObject("summary");
                JSONArray serverPositions=result.optJSONArray("positions");
                boolean canPrepare=result.has("run") && (reported==null ? result.isNull("run") :
                    !previousId.isEmpty() && serverSummary!=null && serverSummary.has("open") && serverSummary.optInt("open",-1)==0 &&
                    DemoReportPreparation.canPrepare(state,DemoReportPreparation.openBybitPositions(serverPositions)));
                if(prepareBybitRequested){
                    if(!prepareBybitId.equals(previousId)){
                        prepareBybitRequested=false;
                        Toast.makeText(this,"Серверный тест изменился. Сброс отменён.",Toast.LENGTH_LONG).show();
                    }else if(canPrepare){
                        bybitDraftPreviousId=previousId;prepareBybitRequested=false;
                    }else if(DemoReportPreparation.canRequestStop(state)&&!previousId.isEmpty()){
                        if(!bybitStopSent)requestStopForPreparation(false,previousId);
                    }else if(!"STOPPING".equals(state)){
                        prepareBybitRequested=false;
                        Toast.makeText(this,"Безопасное завершение не подтверждено.",Toast.LENGTH_LONG).show();
                    }
                }
                startDemoRunButton.setEnabled(canPrepare&&!prepareBybitRequested);
                bybitPrepareButton.setEnabled(!prepareBybitRequested&&(canPrepare||DemoReportPreparation.canRequestStop(state)||"STOPPING".equals(state)));
                if(DemoReportPreparation.keepDraft(bybitDraftPreviousId,previousId,canPrepare)){renderBybitDraft();return;}
                bybitDraftPreviousId=null;bybitPrepareButton.setText("ОБНУЛИТЬ СТАТИСТИКУ / НОВЫЙ ТЕСТ");
                serverReportText.setText((prepareBybitRequested?"ЗАВЕРШАЕМ BYBIT DEMO • ждём закрытия позиций и подтверждения всех заявок. Статистика пока не сброшена.\n\n":"")+formatServerReport(result));
            });
        });
    }

    private String formatServerReport(JSONObject report){
        StringBuilder b=new StringBuilder();
        b.append("КЛЮЧИ BYBIT DEMO: ").append(report.optBoolean("configured",false)?"СОХРАНЕНЫ В VAULT":"НЕ ПОДКЛЮЧЕНЫ");
        JSONObject run=report.optJSONObject("run");
        if(run==null){b.append("\n\n48-ЧАСОВЫЙ ТЕСТ ЕЩЁ НЕ ЗАПУСКАЛСЯ.");return b.toString();}
        b.append("\n\nСТАТУС: ").append(run.optString("status","—"));
        b.append("\nRun ID: ").append(run.optString("id","—"));
        b.append("\nОтчёт получен: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new java.util.Date()));
        if("STOPPING".equals(run.optString("status"))){
            b.append("\nПредыдущий тест ещё завершает работу. Это не новый запуск.");
            JSONArray journal=report.optJSONArray("events");
            if(journal!=null)for(int i=0;i<journal.length();i++){
                JSONObject event=journal.optJSONObject(i);
                if(event==null||!"STOPPING_WAITING_FOR_RECONCILIATION".equals(event.optString("event_type")))continue;
                JSONObject details=event.optJSONObject("details");
                if(details!=null)b.append("\nПоследняя серверная сверка: открытых позиций ")
                    .append(details.optString("open_positions","—")).append(", необработанных заявок ")
                    .append(details.optString("pending_orders","—"));
                b.append("\nВремя сверки: ").append(event.optString("created_at","—"));
                break;
            }
            if(journal!=null)for(int i=0;i<journal.length();i++){
                JSONObject event=journal.optJSONObject(i);
                if(event==null||!"ORDER_RECONCILIATION_ERROR".equals(event.optString("event_type")))continue;
                JSONObject details=event.optJSONObject("details");
                b.append("\nПоследняя зарегистрированная ошибка сверки: ").append(event.optString("message","—"));
                if(details!=null)b.append("\nИнструмент: ").append(details.optString("symbol","—"))
                    .append(" • статус заявки: ").append(details.optString("status","—"))
                    .append("\nID заявки: ").append(details.optString("order_id","—"));
                b.append("\nВремя ошибки: ").append(event.optString("created_at","—"));
                break;
            }
            b.append("\nСброс заблокирован до подтверждения сервером. Не повторяйте неподтверждённую заявку вручную.");
        }
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
            b.append("\n\nПОЗИЦИИ ТЕСТА (включая закрытые)");
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
                    chartEntryPrice=prefs.getBoolean("open",false) && requested.equals(prefs.getString("trade_symbol","")) ? prefs.getFloat("entry",0) : 0;
                    priceChart.setCandles(candles,chartEntryPrice);
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
        if(priceChart!=null)priceChart.setAnalysis(s,dir);
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
                    .putLong("last_entry_signal_candle",candleTime)                    .putString("last",String.format(Locale.US,"%s • BUY %.4f • объём 100 USDT • SL %.4f • TP %.4f",symbol,lastPrice,lastPrice*(1-STOP_PCT),lastPrice*(1+TAKE_PCT))).apply();
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
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(poll);handler.post(poll);refreshServerReport();refreshPaperAutoReport();handler.removeCallbacks(paperStatusPoll);handler.postDelayed(paperStatusPoll,60000);}
    @Override protected void onPause(){super.onPause();handler.removeCallbacks(poll);handler.removeCallbacks(paperStatusPoll);}
    @Override protected void onDestroy(){handler.removeCallbacks(poll);handler.removeCallbacks(paperStatusPoll);io.shutdownNow();super.onDestroy();}

    private final class PriceChart extends View{
        private final Paint p=new Paint(3);private JSONArray data=new JSONArray();private double entryPrice=0;private int analysisScore=0;private String analysisLabel="ОЖИДАНИЕ АНАЛИЗА";
        PriceChart(){super(BybitTerminalActivity.this);setBackgroundColor(PANEL);}
        void setCandles(JSONArray d,double entry){data=d;entryPrice=entry;invalidate();}
        void setAnalysis(int score,String label){analysisScore=score;analysisLabel=label;invalidate();}
        private double[] emaValues(int period){
            double[] out=new double[data.length()];if(data.length()==0)return out;
            try{
                double alpha=2.0/(period+1.0);
                out[0]=Double.parseDouble(data.getJSONArray(0).getString(4));
                for(int i=1;i<data.length();i++)out[i]=Double.parseDouble(data.getJSONArray(i).getString(4))*alpha+out[i-1]*(1-alpha);
            }catch(Exception ignored){}
            return out;
        }
        private void drawEma(Canvas c,double[] values,int color,float slot,float bottom,float plotH,double min,double range){
            if(values.length<2)return;
            p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setStrokeCap(Paint.Cap.ROUND);
            android.graphics.Path path=new android.graphics.Path();
            for(int i=0;i<values.length;i++){
                float x=(i+0.5f)*slot,y=(float)(bottom-(values[i]-min)/range*plotH);
                if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight();p.setStrokeWidth(1);p.setColor(0xFF202630);
            for(int i=1;i<4;i++)c.drawLine(0,h*i/4,w,h*i/4,p);
            if(data.length()<2)return;
            double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
            for(int i=0;i<data.length();i++){try{JSONArray row=data.getJSONArray(i);min=Math.min(min,Double.parseDouble(row.getString(3)));max=Math.max(max,Double.parseDouble(row.getString(2)));}catch(Exception ignored){}}
            if(entryPrice>0){min=Math.min(min,entryPrice);max=Math.max(max,entryPrice);}
            double[] ema9=emaValues(9),ema21=emaValues(21);
            for(double v:ema9){min=Math.min(min,v);max=Math.max(max,v);}
            for(double v:ema21){min=Math.min(min,v);max=Math.max(max,v);}
            double range=Math.max(1e-9,max-min);float top=dp(30),bottom=h-dp(22),plotH=bottom-top;
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
            drawEma(c,ema9,GOLD,slot,bottom,plotH,min,range);
            drawEma(c,ema21,0xFF979AA3,slot,bottom,plotH,min,range);
            p.setStyle(Paint.Style.FILL);p.setTextSize(dp(9));p.setFakeBoldText(true);
            p.setColor(GOLD);c.drawText("EMA 9",dp(8),dp(15),p);
            p.setColor(0xFF979AA3);c.drawText("EMA 21",dp(58),dp(15),p);
            p.setFakeBoldText(false);
            int signalColor=analysisScore>=3?GREEN:analysisScore<=-3?RED:MUTED;
            String analysisTag="АНАЛИЗ: "+analysisLabel+"  •  "+(analysisScore>0?"+":"")+analysisScore;
            p.setTextSize(dp(9));p.setFakeBoldText(true);
            float aw=p.measureText(analysisTag)+dp(14);
            p.setColor(0xFF20242C);c.drawRoundRect(w-aw-dp(6),dp(22),w-dp(6),dp(42),dp(5),dp(5),p);
            p.setColor(signalColor);c.drawText(analysisTag,w-aw,p.getTextSize()+dp(17),p);p.setFakeBoldText(false);
            if(entryPrice>0){
                float ey=(float)(bottom-(entryPrice-min)/range*plotH);
                p.setColor(GOLD);p.setStrokeWidth(dp(1));p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(5),dp(4)},0));
                c.drawLine(0,ey,w,ey,p);p.setPathEffect(null);
                p.setTextSize(dp(10));p.setFakeBoldText(true);
                String tag="ВХОД  "+String.format(Locale.US,"%.4f",entryPrice);
                float tw=p.measureText(tag)+dp(14),ty=Math.max(dp(27),Math.min(h-dp(24),ey-dp(5)));
                p.setColor(0xFF2B2414);c.drawRoundRect(dp(6),ty-dp(14),dp(6)+tw,ty+dp(4),dp(5),dp(5),p);
                p.setColor(GOLD);c.drawText(tag,dp(13),ty,p);p.setFakeBoldText(false);
            }
            p.setColor(MUTED);p.setTextSize(dp(9));
            c.drawText(String.format(Locale.US,"H %.4f",max),w-dp(95),dp(15),p);
            c.drawText(String.format(Locale.US,"L %.4f",min),w-dp(95),h-dp(5),p);
        }
    }
}