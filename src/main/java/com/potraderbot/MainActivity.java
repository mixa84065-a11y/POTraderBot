package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int NAVY=Color.rgb(5,7,11), PANEL=Color.rgb(15,17,22);
    private static final int GOLD=Color.rgb(247,190,55), TEXT=Color.rgb(242,242,245);
    private static final int MUTED=Color.rgb(151,154,163), GREEN=Color.rgb(0,190,85), RED=Color.rgb(224,43,43);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile String accessToken="", refreshToken="";
    private String symbol="EUR/USD";
    private final Set<String> activeTradeSymbols=new HashSet<>();
    private final Set<String> openingTradeSymbols=new HashSet<>();
    private Spinner pairSelector;
    private TextView instrumentText;
    private int expirySeconds=180;
    private long quoteReceivedElapsed=-1;
    private int analysisGeneration=0;
    private boolean resumed=false;
    private boolean quoteFresh=false, demoEnabled=false, analysisMode=false, refreshing=false, refreshingMarket=false, autoTradeSubmitted=false;
    private JSONArray latestCandles=new JSONArray();
    private ChartView chart;
    private TextView balanceText, quoteText, analysisText, historyText, statsText, signalText;
    private EditText stakeInput;
    private Button upButton, downButton, analysisButton;
    private ProgressBar strengthBar;
    private LinearLayout expiryRow, controlsPanel, historyPanel, accountPanel;
    private View analysisDivider;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!accessToken.isEmpty()) {
                refreshData();
                handler.postDelayed(this, 5000);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        applyImmersiveMode();
        showLogin("Проверяем сохранённую сессию…");
        restoreSession();
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
        if(hasFocus) applyImmersiveMode();
    }

    @Override protected void onResume() {
        super.onResume();resumed=true;
        String[] savedSession=SessionStore.load(this);
        if(savedSession!=null&&savedSession.length==2&&!savedSession[0].isEmpty()){
            accessToken=savedSession[0];refreshToken=savedSession[1];
        }
        if (!accessToken.isEmpty()) handler.removeCallbacks(poll);
        if (!accessToken.isEmpty()) handler.post(poll);
    }

    @Override protected void onPause() {
        super.onPause();resumed=false;invalidateMarketAnalysis();
        handler.removeCallbacks(poll);
    }

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,1);return t;
    }
    private GradientDrawable surface(int color,int radiusDp,int strokeColor){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radiusDp));
        if(strokeColor!=0)d.setStroke(dp(1),strokeColor);return d;
    }
    private void stylePanel(View view,int paddingDp){
        view.setBackground(surface(PANEL,14,0xFF51401D));
        if(view instanceof TextView)((TextView)view).setPadding(dp(paddingDp),dp(paddingDp),dp(paddingDp),dp(paddingDp));
        view.setElevation(dp(2));
    }
    private Button button(String s,int bg,int fg){
        Button b=new Button(this);b.setText(s);b.setTextColor(fg);b.setTextSize(14);
        b.setAllCaps(false);b.setTypeface(null,1);b.setMinHeight(dp(44));
        b.setPadding(dp(12),dp(8),dp(12),dp(8));
        b.setBackground(surface(bg,13,bg==GOLD?0:0xFF51401D));b.setElevation(dp(2));return b;
    }
    private LinearLayout vertical(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private LinearLayout horizontal(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;
    }
    private ScrollView page(LinearLayout content){
        ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setBackgroundColor(NAVY);
        s.addView(content);setContentView(s);return s;
    }
    private void pageWithNavigation(LinearLayout content,int selected){
        dashboardVisible=false;
        LinearLayout shell=vertical();shell.setBackgroundColor(NAVY);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(NAVY);
        scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=horizontal();nav.setPadding(dp(3),dp(2),dp(3),dp(2));nav.setBackground(surface(0xff17212e,0,0xff303b49));
        String[] labels={"Главная","Статистика","Сигналы","Настройки"},icons={"home","stats","signals","settings"};
        Runnable[] actions={()->showMain(),()->showSection("СТАТИСТИКА"),()->showSection("АНАЛИТИКА"),()->showMenu()};
        for(int i=0;i<labels.length;i++){
            final int index=i;LinearLayout item=vertical();item.setGravity(Gravity.CENTER);int color=i==0?GOLD:MUTED;
            item.addView(new DashboardIcon(this,icons[i],color),new LinearLayout.LayoutParams(dp(23),dp(24)));
            TextView text=label(labels[i],10,color,false);text.setGravity(Gravity.CENTER);text.setSingleLine(true);item.addView(text);
            item.setFocusable(true);item.setContentDescription(labels[i]);item.setOnClickListener(v->actions[index].run());
            nav.addView(item,new LinearLayout.LayoutParams(0,dp(49),1));
        }
        shell.addView(nav,new LinearLayout.LayoutParams(-1,-2));setContentView(shell);
    }
    private void showLogin(String status){
        dashboardVisible=false;
        LinearLayout root=vertical();root.setPadding(dp(24),dp(30),dp(24),dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);root.setBackgroundColor(NAVY);
        TextView brand=label("GOD OF TRADING",25,GOLD,true);brand.setGravity(Gravity.CENTER);
        root.addView(brand,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView sub=label("2.0  •  ДЕМО-ТЕРМИНАЛ",12,MUTED,true);sub.setGravity(Gravity.CENTER);
        root.addView(sub,new LinearLayout.LayoutParams(-1,dp(30)));
        TextView title=label("Вход в аккаунт",20,TEXT,true);title.setPadding(0,dp(28),0,dp(12));root.addView(title);
        EditText email=new EditText(this);email.setSingleLine(true);email.setHint("Электронная почта");
        email.setTextColor(TEXT);email.setHintTextColor(MUTED);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(email,new LinearLayout.LayoutParams(-1,dp(54)));
        EditText password=new EditText(this);password.setSingleLine(true);password.setHint("Пароль");
        password.setTextColor(TEXT);password.setHintTextColor(MUTED);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password,new LinearLayout.LayoutParams(-1,dp(54)));
        TextView statusText=label(status,12,MUTED,false);statusText.setPadding(0,dp(12),0,dp(12));root.addView(statusText);
        Button login=button("ВОЙТИ",GOLD,NAVY);root.addView(login,new LinearLayout.LayoutParams(-1,dp(50)));
        Button register=button("СОЗДАТЬ АККАУНТ",PANEL,TEXT);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(50));rp.topMargin=dp(8);root.addView(register,rp);
        TextView note=label("Каждому аккаунту выделяется отдельный виртуальный счёт. Реальные деньги и брокерские аккаунты не используются.",11,MUTED,false);
        note.setPadding(0,dp(18),0,0);root.addView(note);
        page(root);
        login.setOnClickListener(v->authenticate(email.getText().toString(),password.getText().toString(),false,statusText,login,register));
        register.setOnClickListener(v->authenticate(email.getText().toString(),password.getText().toString(),true,statusText,login,register));
    }

    private void restoreSession(){
        String[] saved=SessionStore.load(this);
        if(saved==null||saved.length!=2){showLogin("Войдите или создайте аккаунт.");return;}
        io.execute(()->{
            try{
                String a=saved[0],r=saved[1];
                try{SupabaseApi.getUser(a);}
                catch(Exception expired){
                    JSONObject refreshed=SupabaseApi.refresh(r);
                    a=refreshed.optString("access_token","");
                    r=refreshed.optString("refresh_token",r);
                    if(a.isEmpty())throw new Exception("Сессия истекла");
                    SessionStore.save(this,a,r);
                    SupabaseApi.getUser(a);
                }
                final String fa=a,fr=r;
                runOnUiThread(()->{accessToken=fa;refreshToken=fr;showMain();});
            }catch(Exception e){SessionStore.clear(this);runOnUiThread(()->showLogin("Сессия завершена. Войдите снова."));}
        });
    }

    private void authenticate(String email,String password,boolean create,TextView status,Button login,Button register){
        if(email.trim().isEmpty()||password.length()<6){status.setText("Введите почту и пароль (не менее 6 символов).");return;}
        login.setEnabled(false);register.setEnabled(false);status.setText(create?"Создаём аккаунт…":"Выполняем вход…");
        io.execute(()->{
            try{
                JSONObject auth=create?SupabaseApi.signUp(email,password):SupabaseApi.signIn(email,password);
                String a=auth.optString("access_token",""),r=auth.optString("refresh_token","");                if(a.isEmpty()){
                    runOnUiThread(()->{status.setText("Аккаунт создан. Подтвердите почту по письму, затем войдите.");login.setEnabled(true);register.setEnabled(true);});
                    return;
                }
                SessionStore.save(this,a,r);
                SupabaseApi.getUser(a);
                runOnUiThread(()->{accessToken=a;refreshToken=r;showMain();});
            }catch(Exception e){
                runOnUiThread(()->{status.setText("Не удалось выполнить действие: "+e.getMessage());login.setEnabled(true);register.setEnabled(true);});
            }
        });
    }

    private boolean dashboardVisible=false;

    private void showMain(){
        // Initialize the existing trading views before the account polling callback runs.
        showTradingScreen();
        dashboardVisible=true;
        setContentView(new AnalyticsDashboard(this, action -> {
            switch(action){
                case "trade": showTradingScreen(); break;
                case "sport": startActivity(new android.content.Intent(this,SportActivity.class)); break;
                case "crypto": openBybitTerminal(); break;
                case "finance": showSection("СТАТИСТИКА"); break;
                case "profile": showSection("ПРОФИЛЬ"); break;
                case "signals": showSection("АНАЛИТИКА"); break;
                case "settings": showMenu(); break;
                default: throw new IllegalArgumentException("Unknown dashboard action");
            }
        }).build());
    }

    @Override public void onBackPressed(){
        if(!accessToken.isEmpty()&&!dashboardVisible){showMain();return;}
        super.onBackPressed();
    }

    private void showTradingScreen(){
        dashboardVisible=false;analysisMode=false;invalidateMarketAnalysis();
        LinearLayout content=vertical();content.setPadding(dp(9),dp(6),dp(9),dp(9));
        LinearLayout header=horizontal();
        Button back=button("‹",PANEL,TEXT);back.setPadding(0,0,0,0);
        header.addView(back,new LinearLayout.LayoutParams(dp(36),dp(38)));back.setOnClickListener(v->showMain());
        TextView brand=label("Трейдинг",19,TEXT,true);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(new DashboardIcon(this,"crown",GOLD),new LinearLayout.LayoutParams(dp(31),dp(34)));
        Button mode=button("Демо ▾",PANEL,GREEN);mode.setTextSize(12);mode.setPadding(dp(6),0,dp(6),0);
        header.addView(mode,new LinearLayout.LayoutParams(dp(74),dp(38)));
        mode.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Торговый режим").setMessage("Виртуальный счёт. Реальные сделки отключены.").setPositiveButton("Открыть Bybit",(dialog,which)->openBybitTerminal()).setNegativeButton("Закрыть",null).show());
        content.addView(header);
        LinearLayout quoteRow=horizontal();
        pairSelector=new Spinner(this);
        String[] pairs={"EUR/USD","GBP/USD","USD/JPY","AUD/USD","USD/CHF","USD/CAD","NZD/USD"};
        ArrayAdapter<String> pairAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,pairs);
        pairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);pairSelector.setAdapter(pairAdapter);
        pairSelector.setSelection(Math.max(0,java.util.Arrays.asList(pairs).indexOf(symbol)));
        pairSelector.setBackground(surface(PANEL,9,0xff445569));pairSelector.setPadding(dp(6),0,dp(4),0);
        quoteRow.addView(pairSelector,new LinearLayout.LayoutParams(0,dp(38),1));
        accountPanel=vertical();accountPanel.setGravity(Gravity.RIGHT);accountPanel.setPadding(dp(5),0,0,0);
        TextView accountLabel=label("Демо-баланс",10,MUTED,false);accountLabel.setGravity(Gravity.RIGHT);accountPanel.addView(accountLabel);
        balanceText=label("Загрузка…",14,TEXT,true);balanceText.setGravity(Gravity.RIGHT);balanceText.setMaxLines(2);
        accountPanel.addView(balanceText);quoteRow.addView(accountPanel,new LinearLayout.LayoutParams(0,-2,1.2f));
        content.addView(quoteRow,new LinearLayout.LayoutParams(-1,-2));
        chart=new ChartView(this);chart.setSymbol(symbol);
        android.widget.FrameLayout chartFrame=new android.widget.FrameLayout(this);
        chartFrame.setBackground(surface(0xff0b1420,10,0xff445569));chartFrame.setPadding(dp(1),dp(1),dp(1),dp(1));
        chartFrame.addView(chart,new android.widget.FrameLayout.LayoutParams(-1,-1));
        Button expand=button("⛶",PANEL,TEXT);expand.setTextSize(15);expand.setPadding(0,0,0,0);
        android.widget.FrameLayout.LayoutParams ep=new android.widget.FrameLayout.LayoutParams(dp(38),dp(34),Gravity.TOP|Gravity.RIGHT);
        ep.setMargins(0,dp(3),dp(3),0);chartFrame.addView(expand,ep);
        final int normalChartHeight=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?178:Math.max(178,Math.min(275,getResources().getConfiguration().screenHeightDp-490));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(normalChartHeight));cp.topMargin=dp(6);content.addView(chartFrame,cp);
        expand.setOnClickListener(v->{
            analysisMode=!analysisMode;chart.setExpanded(analysisMode);
            chartFrame.getLayoutParams().height=dp(analysisMode?Math.max(380,normalChartHeight):normalChartHeight);chartFrame.requestLayout();
        });
        LinearLayout chartInfo=horizontal();
        instrumentText=label("M1 · Закрытые свечи",10,GOLD,true);chartInfo.addView(instrumentText,new LinearLayout.LayoutParams(0,-2,1));
        TextView bybit=label("Bybit ›",11,GOLD,true);bybit.setPadding(dp(9),dp(7),dp(2),dp(7));bybit.setOnClickListener(v->openBybitTerminal());chartInfo.addView(bybit);
        content.addView(chartInfo);
        quoteText=label("Нажмите «Анализ» для получения котировки.",10,MUTED,false);
        quoteText.setPadding(dp(3),0,dp(3),dp(3));content.addView(quoteText);
        signalText=label("Оценка модели: —",11,TEXT,true);signalText.setPadding(dp(3),dp(4),0,0);content.addView(signalText);
        strengthBar=new ModelStrengthBar(this);strengthBar.setMax(100);strengthBar.setProgress(0);content.addView(strengthBar,new LinearLayout.LayoutParams(-1,dp(18)));
        analysisDivider=new View(this);analysisDivider.setBackgroundColor(0xff263d5a);content.addView(analysisDivider,new LinearLayout.LayoutParams(-1,dp(1)));
        analysisText=label("Анализ: ожидание котировки. Без свежих данных сделки заблокированы.",11,TEXT,false);
        stylePanel(analysisText,7);content.addView(analysisText);
        analysisText.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Рыночный анализ").setMessage(analysisText.getText()).setPositiveButton("Закрыть",null).show());
        controlsPanel=vertical();
        LinearLayout parameters=horizontal();
        LinearLayout stakeCol=vertical(),expiryCol=vertical();
        stakeCol.addView(label("Сумма демо-сделки, ₽",10,MUTED,false));
        stakeInput=new EditText(this);stakeInput.setText("500");stakeInput.setSingleLine(true);stakeInput.setTextSize(14);
        stakeInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);stakeInput.setTextColor(TEXT);
        stakeInput.setBackground(surface(PANEL,9,0xff445569));stakeInput.setGravity(Gravity.CENTER);stakeInput.setPadding(dp(3),0,dp(3),0);
        LinearLayout stakeRow=horizontal();
        Button minus=button("−",PANEL,TEXT),plus=button("+",PANEL,TEXT);
        for(Button btn:new Button[]{minus,plus}){btn.setPadding(0,0,0,0);btn.setMinWidth(0);btn.setMinimumWidth(0);}
        stakeRow.addView(minus,new LinearLayout.LayoutParams(dp(33),dp(37)));
        stakeRow.addView(stakeInput,new LinearLayout.LayoutParams(0,dp(37),1));
        stakeRow.addView(plus,new LinearLayout.LayoutParams(dp(33),dp(37)));
        minus.setOnClickListener(v->adjustStake(-100));plus.setOnClickListener(v->adjustStake(100));stakeCol.addView(stakeRow);
        expiryCol.addView(label("Экспирация",10,MUTED,false));
        expiryRow=horizontal();Button duration=button(expirySeconds/60+" мин ▾",PANEL,TEXT);duration.setTextSize(13);duration.setPadding(dp(4),0,dp(4),0);
        expiryRow.addView(duration,new LinearLayout.LayoutParams(-1,dp(37)));
        duration.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Экспирация").setSingleChoiceItems(new String[]{"3 мин","4 мин","5 мин"},Math.max(0,Math.min(2,expirySeconds/60-3)),(dialog,which)->{expirySeconds=180+which*60;updateExpiryButtons();dialog.dismiss();}).setNegativeButton("Отмена",null).show());
        expiryCol.addView(expiryRow);parameters.addView(stakeCol,new LinearLayout.LayoutParams(0,-2,1.3f));
        LinearLayout.LayoutParams ec=new LinearLayout.LayoutParams(0,-2,1);ec.leftMargin=dp(8);parameters.addView(expiryCol,ec);controlsPanel.addView(parameters);
        LinearLayout actions=horizontal();
        upButton=button("↑ ВВЕРХ",GREEN,Color.WHITE);downButton=button("↓ ВНИЗ",RED,Color.WHITE);analysisButton=button("Анализ",PANEL,GOLD);
        for(Button btn:new Button[]{upButton,analysisButton,downButton}){btn.setTextSize(12);btn.setPadding(dp(2),0,dp(2),0);btn.setSingleLine(true);btn.setMinWidth(0);btn.setMinimumWidth(0);}
        LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,dp(45),1);l.rightMargin=dp(4);actions.addView(upButton,l);
        actions.addView(analysisButton,new LinearLayout.LayoutParams(0,dp(45),.8f));
        LinearLayout.LayoutParams r=new LinearLayout.LayoutParams(0,dp(45),1);r.leftMargin=dp(4);actions.addView(downButton,r);
        LinearLayout.LayoutParams act=new LinearLayout.LayoutParams(-1,-2);act.topMargin=dp(6);controlsPanel.addView(actions,act);
        upButton.setEnabled(false);downButton.setEnabled(false);
        upButton.setOnClickListener(v->placeTrade("UP"));downButton.setOnClickListener(v->placeTrade("DOWN"));analysisButton.setOnClickListener(v->fetchMarketSnapshot());
        content.addView(controlsPanel);
        historyPanel=vertical();LinearLayout historyHeader=horizontal();
        TextView historyTitle=label("История сделок",14,TEXT,true);historyHeader.addView(historyTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView all=label("Все ›",11,GOLD,true);all.setOnClickListener(v->showSection("ИСТОРИЯ СДЕЛОК"));historyHeader.addView(all);historyPanel.addView(historyHeader);
        historyText=label("Пока нет сделок",11,TEXT,false);stylePanel(historyText,7);historyText.setMaxLines(3);historyText.setOnClickListener(v->showSection("ИСТОРИЯ СДЕЛОК"));historyPanel.addView(historyText);
        TextView statsTitle=label("Статистика ▸",11,GOLD,true);historyPanel.addView(statsTitle);
        statsText=label("Загружаем статистику…",11,TEXT,false);stylePanel(statsText,7);statsText.setVisibility(View.GONE);historyPanel.addView(statsText);
        statsTitle.setOnClickListener(v->{boolean open=statsText.getVisibility()==View.VISIBLE;statsText.setVisibility(open?View.GONE:View.VISIBLE);statsTitle.setText(open?"Статистика ▸":"Статистика ▾");});
        content.addView(historyPanel);
        TextView footer=label("Демо · оценка модели не гарантирует результат",9,MUTED,false);footer.setGravity(Gravity.CENTER);content.addView(footer);
        pairSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                String selected=pairs[position];if(!selected.equals(symbol)){
                    symbol=selected;invalidateMarketAnalysis();latestCandles=new JSONArray();
                    chart.setSymbol(symbol);chart.setMarketData(latestCandles,false);strengthBar.setProgress(0);signalText.setText("Оценка модели: —");
                    analysisText.setText("Анализ: ожидание новой котировки.");quoteText.setText("Нажмите «Анализ» для "+symbol);updateTradeButtons();refreshData();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        pageWithNavigation(content,0);updateExpiryButtons();handler.removeCallbacks(poll);handler.post(poll);
    }
    private void adjustStake(int change){
        double value=500;try{value=Double.parseDouble(stakeInput.getText().toString().replace(',','.'));}catch(Exception ignored){}
        if(!Double.isFinite(value))value=500;
        stakeInput.setText(String.format(Locale.US,"%.0f",Math.max(1,Math.min(20000,value+change))));
    }

    private void showSection(String section){
        LinearLayout root=vertical();root.setPadding(dp(16),dp(18),dp(16),dp(24));root.setBackgroundColor(NAVY);
        LinearLayout top=horizontal();
        Button back=button("‹",PANEL,GOLD);top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(42)));
        TextView title=label("♛  GOD OF TRADING 2.0",17,GOLD,true);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(42),1);tp.leftMargin=dp(10);top.addView(title,tp);root.addView(top);
        back.setOnClickListener(v->showMain());
        TextView heading=label("СТАТИСТИКА".equals(section)?"СТАТИСТИКА ТОРГОВЛИ  ▾":section,20,TEXT,true);
        heading.setPadding(dp(2),dp(20),0,dp(12));root.addView(heading);
        TextView info=label("ДЕМО-РЕЖИМ  •  BYBIT / GOD OF TRADING",10,GOLD,true);root.addView(info);
        TextView body=label("Загружаем подтверждённые данные…",13,TEXT,false);stylePanel(body,14);
        if("СТАТИСТИКА".equals(section)){
            heading.setOnClickListener(v->{
                boolean expanded=body.getVisibility()==View.VISIBLE;
                body.setVisibility(expanded?View.GONE:View.VISIBLE);
                heading.setText(expanded?"СТАТИСТИКА ТОРГОВЛИ  ▸":"СТАТИСТИКА ТОРГОВЛИ  ▾");
            });
        }
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(12);root.addView(body,bp);
        Button refresh=button("ОБНОВИТЬ",GOLD,NAVY);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(48));rp.topMargin=dp(12);root.addView(refresh,rp);
        refresh.setOnClickListener(v->loadSectionData(section,body));
        Button terminal=button("ОТКРЫТЬ ТЕРМИНАЛ BYBIT",PANEL,TEXT);
        LinearLayout.LayoutParams xp=new LinearLayout.LayoutParams(-1,dp(48));xp.topMargin=dp(8);root.addView(terminal,xp);
        terminal.setOnClickListener(v->openBybitTerminal());
        int selected="АНАЛИТИКА".equals(section)?2:("ИСТОРИЯ СДЕЛОК".equals(section)?3:4);
        pageWithNavigation(root,selected);loadSectionData(section,body);
    }

    private void loadSectionData(String section,TextView target){
        if("ПРОФИЛЬ".equals(section)){
            target.setText("Профиль GOD OF TRADING 2.0\n\nРежим: виртуальная торговля\nБаланс: отображается на главном экране\nРеальные ордера: отключены\n\nНастройки API и аккаунта доступны через меню главного экрана.");
            return;
        }
        target.setText("Получаем статистику и историю демо-сделок…");
        final String token=accessToken;
        io.execute(()->{
            JSONArray trades=null;String error=null;
            try{trades=SupabaseApi.getTrades(token);}catch(Exception e){error=e.getMessage();}
            final JSONArray rows=trades;final String problem=error;
            runOnUiThread(()->{
                if(isFinishing())return;
                if(rows==null){target.setText("Данные пока недоступны: "+problem);return;}
                int wins=0,losses=0,open=0;double pnl=0;
                StringBuilder history=new StringBuilder();
                for(int i=0;i<rows.length();i++){
                    JSONObject t=rows.optJSONObject(i);if(t==null)continue;
                    String status=t.optString("status","OPEN");
                    if("WIN".equals(status))wins++;
                    if("LOSS".equals(status))losses++;
                    if("OPEN".equals(status))open++;
                    pnl+=t.optDouble("pnl",0);
                    if(i<30){
                        if(history.length()>0)history.append("\n\n");
                        history.append(t.optString("symbol","—")).append("  •  ")
                            .append("UP".equals(t.optString("direction"))?"ВВЕРХ":"ВНИЗ")
                            .append("  •  ").append(status)
                            .append("  •  ").append(t.optString("stake","—")).append(" ₽");
                    }
                }
                if("СТАТИСТИКА".equals(section)){
                    int closed=wins+losses;
                    double rate=closed==0?0:wins*100.0/closed;
                    target.setText("РЕЗУЛЬТАТЫ ДЕМО\n\nВсего сделок: "+rows.length()
                        +"\nПобеды: "+wins+"\nУбытки: "+losses+"\nОткрытые: "+open
                        +"\nТочность закрытых сделок: "+String.format(Locale.US,"%.1f",rate)+"%"
                        +"\nЧистый P/L: "+String.format(Locale.US,"%+,.2f ₽",pnl));
                }else if("АНАЛИТИКА".equals(section)){
                    target.setText("АНАЛИТИКА МОДЕЛИ\n\n"+(signalText==null?"Нажмите «АНАЛИЗ» на главном экране для получения свежего сигнала.":signalText.getText())
                        +"\n\nОценка модели является эвристикой и не гарантирует прибыль.\nЗакрытых сделок: "+(wins+losses)
                        +"\nПобеды: "+wins+" • Убытки: "+losses);
                }else{
                    target.setText(history.length()==0?"Пока нет сделок.":history.toString());
                }
            });
        });
    }

    private void addExpiry(int seconds,String title){
        Button b=button(title,PANEL,TEXT);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.leftMargin=dp(3);p.rightMargin=dp(3);
        expiryRow.addView(b,p);b.setOnClickListener(v->{expirySeconds=seconds;updateExpiryButtons();});
        b.setTag(seconds);
    }

    private void updateExpiryButtons(){
        if(expiryRow!=null&&expiryRow.getChildCount()>0)((Button)expiryRow.getChildAt(0)).setText(expirySeconds/60+" мин ▾");
    }

    // Wallet and trade history refresh independently; they never consume market-provider credits.
    private void refreshData(){
        if(refreshing||accessToken.isEmpty())return;
        refreshing=true;final String token=accessToken;
        io.execute(()->{
            JSONObject wallet=null;JSONArray trades=null;
            String walletError=null,tradesError=null;
            String active=token;
            try{active=ensureSession(token,false);wallet=SupabaseApi.getWallet(active);}
            catch(Exception e){
                if(SessionStore.isExpired(e.getMessage()))try{active=ensureSession(active,true);wallet=SupabaseApi.getWallet(active);}catch(Exception again){walletError=again.getMessage();}
                else walletError=e.getMessage();
            }
            try{trades=SupabaseApi.getTrades(active);}catch(Exception e){tradesError=e.getMessage();}
            final JSONObject fw=wallet;final JSONArray ft=trades;
            final String fwe=walletError,fte=tradesError;
            runOnUiThread(()->{
                refreshing=false;
                if(isFinishing()||accessToken.isEmpty())return;
                if(fw!=null&&fw.has("balance")&&!fw.isNull("balance")){
                    try{balanceText.setText(String.format(Locale.US,"%,.2f ₽",fw.getDouble("balance")));}
                    catch(Exception ignored){balanceText.setText(fw.optString("balance")+" ₽");}
                }else balanceText.setText(fwe==null?"Баланс не найден":SessionStore.isExpired(fwe)?"Сессия истекла. Войдите снова.":"Баланс недоступен. Проверьте связь.");
                if(ft==null && fte!=null) historyText.setText("История недоступна: "+fte);
                if(ft!=null){
                    activeTradeSymbols.clear();
                    for(int i=0;i<ft.length();i++){
                        JSONObject trade=ft.optJSONObject(i);
                        if(trade!=null && "OPEN".equals(trade.optString("status","")))
                            activeTradeSymbols.add(trade.optString("symbol",""));
                    }
                    renderStats(ft);renderHistory(ft);updateTradeButtons();
                }
            });
        });
    }

    private String ensureSession(String expected,boolean force)throws Exception{
        String[] next=SessionStore.fresh(this,expected,force);
        if(!SessionStore.sameSession(accessToken,expected))throw new Exception("Сессия изменена. Войдите снова.");
        accessToken=next[0];refreshToken=next[1];return next[0];
    }
    private void invalidateMarketAnalysis(){
        analysisGeneration++;quoteFresh=false;demoEnabled=false;quoteReceivedElapsed=-1;
        if(analysisButton!=null)analysisButton.setEnabled(!refreshingMarket);
        updateTradeButtons();
    }
    private boolean usableQuote(){return quoteFresh&&QuoteFreshness.usable(quoteReceivedElapsed,android.os.SystemClock.elapsedRealtime());}
    private void updateTradeButtons(){
        boolean canTrade=usableQuote()&&demoEnabled
            && !activeTradeSymbols.contains(symbol)
            && !openingTradeSymbols.contains(symbol);
        if(upButton!=null){upButton.setEnabled(canTrade);upButton.setAlpha(canTrade?1f:.45f);}
        if(downButton!=null){downButton.setEnabled(canTrade);downButton.setAlpha(canTrade?1f:.45f);}
    }

    // This is the only client path that calls Twelve Data. It runs only after ANALYSIS is pressed.
    private void fetchMarketSnapshot(){
        if(accessToken.isEmpty()||refreshingMarket)return;
        autoTradeSubmitted=false;
        refreshingMarket=true;
        quoteFresh=false;quoteReceivedElapsed=-1;
        updateTradeButtons();
        quoteText.setText("Запрашиваем котировку для "+symbol+"…");
        analysisButton.setEnabled(false);
        final String token=accessToken,requestedSymbol=symbol;
        final int generation=++analysisGeneration;
        final Button requestedButton=analysisButton;
        final long requestStarted=android.os.SystemClock.elapsedRealtime();
        io.execute(()->{
            JSONObject snapshot=null;String error=null;
            String active=token;
            try{active=ensureSession(token,false);snapshot=SupabaseApi.getMarketSnapshot(active,requestedSymbol);}
            catch(Exception e){if(SessionStore.isExpired(e.getMessage()))try{active=ensureSession(active,true);snapshot=SupabaseApi.getMarketSnapshot(active,requestedSymbol);}catch(Exception again){error=again.getMessage();}else error=e.getMessage();}
            final JSONObject fs=snapshot;final String fe=error;            runOnUiThread(()->{
                refreshingMarket=false;
                if(isFinishing()||isDestroyed()||accessToken.isEmpty())return;
                if(analysisButton!=null)analysisButton.setEnabled(true);
                if(generation!=analysisGeneration||requestedButton!=analysisButton||!resumed||!SessionStore.sameSession(token,accessToken)||!requestedSymbol.equals(symbol)||!requestedButton.isShown())return;
                if(fs==null){
                    quoteText.setText("Не удалось получить котировку"+(fe==null?"":": "+fe));
                    quoteFresh=false;updateTradeButtons();
                    return;
                }
                latestCandles=fs.optJSONArray("candles");
                if(latestCandles==null)latestCandles=new JSONArray();
                quoteFresh=fs.optBoolean("quote_fresh",false);
                quoteReceivedElapsed=requestStarted;
                quoteFresh=usableQuote();
                if(quoteFresh){final int currentGeneration=generation;handler.postDelayed(()->{
                    if(currentGeneration!=analysisGeneration||usableQuote())return;
                    quoteFresh=false;chart.setMarketData(latestCandles,false);strengthBar.setProgress(0);
                    signalText.setText("Котировка устарела");quoteText.setText("Нажмите «Анализ» для обновления котировки.");updateTradeButtons();
                },Math.max(1,QuoteFreshness.MAX_AGE_MS-(android.os.SystemClock.elapsedRealtime()-quoteReceivedElapsed)+1));}
                demoEnabled=fs.optBoolean("demo_enabled",false);
                chart.setMarketData(latestCandles,quoteFresh);
                JSONObject q=fs.optJSONObject("quote");
                String price=q==null?"":q.optString("price","");
                String status=fs.optBoolean("key_required",false)
                    ?"Добавьте личный API-ключ Twelve Data в настройках"
                    :(quoteFresh?"Котировка получена":"Нет свежей котировки");
                if(!demoEnabled)status+=" • демо-инструмент ещё не включён";
                if(!price.isEmpty())status+=" • "+price;
                quoteText.setText(status);
                MarketAnalyzer.Result a=MarketAnalyzer.analyze(latestCandles);
                chart.setDecision(a.direction,a.strength);
                analysisText.setText(a.explanation);
                signalText.setText(a.strength>50
                    ?"ОЦЕНКА НАПРАВЛЕНИЯ: "+a.direction+" • СИЛА "+a.strength+"%"
                    :"ОЦЕНКА МОДЕЛИ: НЕТ ПРЕИМУЩЕСТВА");
                strengthBar.setProgress(a.strength);
                if(analysisMode)chart.setExpanded(true);
                updateTradeButtons();

                // One automatic demo entry per explicit ANALYSIS request.
                // "strength" is a heuristic model score, not a calibrated win probability.
                if(usableQuote() && demoEnabled && !activeTradeSymbols.contains(symbol) && !openingTradeSymbols.contains(symbol)
                        && !autoTradeSubmitted && a.strength>70
                        && ("ВВЕРХ".equals(a.direction) || "ВНИЗ".equals(a.direction))) {
                    autoTradeSubmitted=true;
                    String autoDirection="ВВЕРХ".equals(a.direction)?"UP":"DOWN";
                    Toast.makeText(this,"Сила сигнала "+a.strength+"% — открываю демо-сделку "+a.direction,Toast.LENGTH_LONG).show();
                    placeTrade(autoDirection);
                }
            });
        });
    }

    private void renderStats(JSONArray trades){
        int wins=0,losses=0,draws=0,open=0,cancelled=0;
        double net=0.0;
        for(int i=0;i<trades.length();i++){
            JSONObject t=trades.optJSONObject(i);if(t==null)continue;
            String status=t.optString("status","OPEN");
            if("WIN".equals(status)){wins++;net+=t.optDouble("pnl",0.0);}
            else if("LOSS".equals(status)){losses++;net+=t.optDouble("pnl",0.0);}
            else if("DRAW".equals(status)){draws++;net+=t.optDouble("pnl",0.0);}
            else if("OPEN".equals(status))open++;
            else if("CANCELLED".equals(status))cancelled++;
        }
        int closed=wins+losses+draws;
        int decisive=wins+losses;
        double winRate=decisive==0?0.0:(wins*100.0/decisive);
        String pnl=(net>0?"+":"")+String.format(Locale.US,"%,.2f",net)+" ₽";
        String summary="Закрыто: "+closed+"   •   Победы: "+wins+"   •   Поражения: "+losses
            +"\nПроцент побед: "+String.format(Locale.US,"%.1f",winRate)+"%"
            +"   •   Ничьих: "+draws
            +"\nЧистый результат: "+pnl
            +"\nОткрыто: "+open+"   •   Отменено: "+cancelled;
        statsText.setText(summary);
    }

    private void renderHistory(JSONArray trades){
        if(trades.length()==0){historyText.setText("Пока нет сделок");return;}
        StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.min(8,trades.length());i++){
            JSONObject t=trades.optJSONObject(i);if(t==null)continue;
            String dir=t.optString("direction","");String status=t.optString("status","OPEN");
            String result=status.equals("WIN")?"ВЫИГРЫШ":status.equals("LOSS")?"УБЫТОК":status.equals("DRAW")?"НИЧЬЯ":status.equals("CANCELLED")?"ОТМЕНА":"ОТКРЫТА";
            b.append(dir.equals("UP")?"↑ ВВЕРХ":"↓ ВНИЗ").append("  •  ")
              .append(t.optString("stake","—")).append(" ₽  •  ")
              .append(t.optInt("expiry_seconds")/60).append(" мин  •  ").append(result);
            if(i<Math.min(8,trades.length())-1)b.append("\n");
        }
        historyText.setText(b.toString());
    }

    private void placeTrade(String direction){
        if(!usableQuote()||!demoEnabled){updateTradeButtons();Toast.makeText(this,"Сделка заблокирована: нет проверенной свежей котировки.",Toast.LENGTH_LONG).show();return;}
        if(activeTradeSymbols.contains(symbol)||openingTradeSymbols.contains(symbol)){
            Toast.makeText(this,"По этой валютной паре уже есть открытая сделка.",Toast.LENGTH_LONG).show();return;
        }
        double stake;
        try{stake=Double.parseDouble(stakeInput.getText().toString().replace(',','.'));}
        catch(Exception e){Toast.makeText(this,"Укажите сумму сделки.",Toast.LENGTH_SHORT).show();return;}
        if(!Double.isFinite(stake)||stake<1||stake>20000){Toast.makeText(this,"Сумма должна быть от 1 до 20 000 ₽.",Toast.LENGTH_SHORT).show();return;}
        final String tradeSymbol=symbol;
        openingTradeSymbols.add(tradeSymbol);updateTradeButtons();
        final double amount=stake;final String token=accessToken;
        final int tradeExpiry=expirySeconds;final long received=quoteReceivedElapsed;
        io.execute(()->{
            try{
                String current=ensureSession(token,false);
                if(!QuoteFreshness.usable(received,android.os.SystemClock.elapsedRealtime()))throw new Exception("Котировка устарела. Повторите анализ.");
                JSONObject result=SupabaseApi.openDemoTrade(current,tradeSymbol,direction,amount,tradeExpiry);
                runOnUiThread(()->{
                    JSONObject trade=result.optJSONObject("trade");
                    String id=trade==null?"":trade.optString("trade_id","");
                    openingTradeSymbols.remove(tradeSymbol);
                    activeTradeSymbols.add(tradeSymbol);
                    updateTradeButtons();
                    Toast.makeText(this,"Демо-сделка открыта"+(id.isEmpty()?"":" • "+id),Toast.LENGTH_LONG).show();
                    scheduleExpiryQuote(current,tradeSymbol,tradeExpiry);
                });
            }catch(Exception e){runOnUiThread(()->{
                openingTradeSymbols.remove(tradeSymbol);
                updateTradeButtons();
                Toast.makeText(this,"Сделка не открыта: "+e.getMessage(),Toast.LENGTH_LONG).show();
            });}
            runOnUiThread(this::refreshData);
        });
    }

    // One provider request is made only when an opened demo trade reaches expiry.
    // Wallet/history polling never calls the market provider.
    private void scheduleExpiryQuote(String token,String tradeSymbol,int seconds){
        handler.postDelayed(()->{
            if(!SessionStore.sameSession(token,accessToken))return;
            io.execute(()->{
                try{
                    // Fetch the final quote; the server settles all trades now due.
                    SupabaseApi.getMarketSnapshot(ensureSession(token,false),tradeSymbol);
                }catch(Exception ignored){
                    // The next explicit ANALYSIS request can retry settlement.
                }
                runOnUiThread(()->{
                    if(SessionStore.sameSession(token,accessToken)){
                        refreshData();
                        if(tradeSymbol.equals(symbol)){
                            quoteFresh=false;
                            quoteText.setText("Время экспирации прошло. Результат проверяется по истории.");
                            updateTradeButtons();
                        }
                    }
                });
            });
        },Math.max(1,seconds)*1000L);
    }

    private void toggleAnalysis(){
        boolean requestingMarket=!analysisMode;
        analysisMode=!analysisMode;
        if(requestingMarket)fetchMarketSnapshot();
        chart.setExpanded(analysisMode);
        int chartHeight=analysisMode?dp(420):(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?dp(175):dp(256));
        chart.getLayoutParams().height=chartHeight;chart.requestLayout();
        accountPanel.setVisibility(analysisMode?View.GONE:View.VISIBLE);
        quoteText.setVisibility(View.VISIBLE);
        // Keep manual UP/DOWN controls available while the expanded analysis is shown.
        controlsPanel.setVisibility(View.VISIBLE);
        historyPanel.setVisibility(analysisMode?View.GONE:View.VISIBLE);
        signalText.setVisibility(View.VISIBLE);strengthBar.setVisibility(View.VISIBLE);
        analysisButton.setText(analysisMode?"Свернуть":"Анализ");
        MarketAnalyzer.Result currentAnalysis=MarketAnalyzer.analyze(latestCandles);
        chart.setDecision(currentAnalysis.direction,currentAnalysis.strength);
        analysisText.setText(analysisMode?"ПОЛНЫЙ АНАЛИЗ\n"+currentAnalysis.explanation:currentAnalysis.explanation);
    }

    private void openBybitTerminal(){
        String[] savedSession=SessionStore.load(this);
        if(savedSession!=null&&savedSession.length==2&&!savedSession[0].isEmpty()){
            accessToken=savedSession[0];refreshToken=savedSession[1];
        }
        android.content.Intent intent=new android.content.Intent(this,BybitTerminalActivity.class);
        intent.putExtra("access_token",accessToken);
        intent.putExtra("refresh_token",refreshToken);
        startActivity(intent);
    }

    private void showMenu(){
        String[] items={"Аналитика","Статистика","История сделок","Профиль","Открыть BYBIT",
            "API-ключ Twelve Data","Обновить данные","Telegram-бот","Выйти из аккаунта"};
        new AlertDialog.Builder(this).setTitle("РАЗДЕЛЫ И НАСТРОЙКИ").setItems(items,(d,which)->{
            if(which==0)showSection("АНАЛИТИКА");
            else if(which==1)showSection("СТАТИСТИКА");
            else if(which==2)showSection("ИСТОРИЯ СДЕЛОК");
            else if(which==3)showSection("ПРОФИЛЬ");
            else if(which==4)openBybitTerminal();
            else if(which==5)showTwelveDataSettings();
            else if(which==6)refreshData();
            else if(which==7)new AlertDialog.Builder(this).setMessage("Ссылка на Telegram-бот ещё не настроена.").setPositiveButton("ОК",null).show();
            else logout();
        }).show();
    }


    private void showTwelveDataSettings(){
        LinearLayout box=vertical();box.setPadding(dp(8),dp(4),dp(8),0);
        TextView info=label("У каждого аккаунта свой ключ. Он проверяется и сохраняется отдельно в зашифрованном хранилище. Другие пользователи его не видят.",12,MUTED,false);
        box.addView(info);
        EditText keyInput=new EditText(this);keyInput.setSingleLine(true);keyInput.setHint("Вставьте личный API-ключ");keyInput.setTextColor(TEXT);keyInput.setHintTextColor(MUTED);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(-1,dp(52));kp.topMargin=dp(10);box.addView(keyInput,kp);
        TextView status=label("Проверяем настройки ключа…",11,MUTED,false);status.setPadding(0,dp(8),0,0);box.addView(status);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Twelve Data — личный ключ").setView(box)
            .setPositiveButton("СОХРАНИТЬ",null).setNegativeButton("ЗАКРЫТЬ",null)
            .setNeutralButton("УДАЛИТЬ КЛЮЧ",null).create();
        dialog.setOnShowListener(ignored->{
            Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE), remove=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            save.setOnClickListener(v->{
                String value=keyInput.getText().toString().trim();
                if(value.length()<8){status.setText("Введите действующий ключ Twelve Data.");return;}
                save.setEnabled(false);status.setText("Проверяем ключ и сохраняем…");
                io.execute(()->{
                    try{SupabaseApi.saveTwelveDataKey(accessToken,value);runOnUiThread(()->{status.setText("Ключ сохранён для этого аккаунта.");keyInput.setText("");save.setEnabled(true);Toast.makeText(this,"API-ключ подключён",Toast.LENGTH_SHORT).show();});}
                    catch(Exception e){runOnUiThread(()->{status.setText("Не удалось сохранить: "+e.getMessage());save.setEnabled(true);});}
                });
            });
            remove.setOnClickListener(v->{
                remove.setEnabled(false);status.setText("Удаляем ключ…");
                io.execute(()->{
                    try{SupabaseApi.deleteTwelveDataKey(accessToken);runOnUiThread(()->{status.setText("Личный ключ удалён.");keyInput.setText("");remove.setEnabled(true);});}
                    catch(Exception e){runOnUiThread(()->{status.setText("Не удалось удалить: "+e.getMessage());remove.setEnabled(true);});}
                });
            });
        });
        dialog.show();
        io.execute(()->{
            try{boolean configured=SupabaseApi.getTwelveDataKeyStatus(accessToken).optBoolean("configured",false);
                runOnUiThread(()->status.setText(configured?"Для этого аккаунта уже сохранён личный ключ. Повторный ввод заменит его.":"Личный ключ ещё не добавлен."));
            }catch(Exception e){runOnUiThread(()->status.setText("Не удалось проверить статус ключа."));}
        });
    }

    private void logout(){
        accessToken="";refreshToken="";invalidateMarketAnalysis();handler.removeCallbacks(poll);
        SessionStore.clear(this);showLogin("Вы вышли из аккаунта.");
    }

    @Override protected void onDestroy(){
        handler.removeCallbacks(poll);
        super.onDestroy();
    }
}