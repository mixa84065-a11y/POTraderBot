package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int NAVY=Color.rgb(8,20,39), PANEL=Color.rgb(14,31,55);
    private static final int GOLD=Color.rgb(224,183,91), TEXT=Color.rgb(231,239,249);
    private static final int MUTED=Color.rgb(139,160,187), GREEN=Color.rgb(29,143,111), RED=Color.rgb(190,67,78);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String accessToken="", refreshToken="";
    private String symbol="EUR/USD";
    private Spinner pairSelector;
    private TextView instrumentText;
    private int expirySeconds=180;
    private boolean quoteFresh=false, demoEnabled=false, analysisMode=false, refreshing=false, refreshingMarket=false;
    private JSONArray latestCandles=new JSONArray();
    private ChartView chart;
    private TextView balanceText, quoteText, analysisText, historyText, signalText;
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
        super.onResume();
        if (!accessToken.isEmpty()) handler.removeCallbacks(poll);
        if (!accessToken.isEmpty()) handler.post(poll);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,1);return t;
    }
    private Button button(String s,int bg,int fg){
        Button b=new Button(this);b.setText(s);b.setTextColor(fg);b.setTextSize(14);
        b.setAllCaps(false);b.setTypeface(null,1);
        b.setBackgroundTintList(ColorStateList.valueOf(bg));return b;
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
    private void showLogin(String status){
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
                String a=auth.optString("access_token",""),r=auth.optString("refresh_token","");
                if(a.isEmpty()){
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

    private void showMain(){
        analysisMode=false;
        LinearLayout content=vertical();content.setPadding(dp(14),dp(10),dp(14),dp(14));
        LinearLayout header=horizontal();
        TextView brand=label("GOD OF TRADING",19,GOLD,true);header.addView(brand,new LinearLayout.LayoutParams(0,dp(42),1));
        Button menu=button("⋮",PANEL,TEXT);header.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(42)));
        content.addView(header);
        menu.setOnClickListener(v->showMenu());

        accountPanel=horizontal();accountPanel.setPadding(dp(12),dp(10),dp(12),dp(10));accountPanel.setBackgroundColor(PANEL);
        TextView accountLabel=label("ВИРТУАЛЬНЫЙ БАЛАНС",10,MUTED,true);
        accountPanel.addView(accountLabel,new LinearLayout.LayoutParams(0,-2,1));
        balanceText=label("Загрузка…",18,TEXT,true);accountPanel.addView(balanceText);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=dp(8);content.addView(accountPanel,ap);

        TextView pairLabel=label("ВАЛЮТНАЯ ПАРА",10,MUTED,true);
        pairLabel.setPadding(dp(4),dp(10),0,dp(3));content.addView(pairLabel);
        pairSelector=new Spinner(this);
        String[] pairs={"EUR/USD","GBP/USD","USD/JPY","AUD/USD","USD/CHF","USD/CAD","NZD/USD"};
        ArrayAdapter<String> pairAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,pairs);
        pairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pairSelector.setAdapter(pairAdapter);
        pairSelector.setSelection(java.util.Arrays.asList(pairs).indexOf(symbol));
        content.addView(pairSelector,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView pairNote=label("Доступность демо-сделок зависит от включённого потока котировок.",10,MUTED,false);
        content.addView(pairNote);
        instrumentText=label(symbol+"  •  ОБЫЧНЫЙ РЫНОК  •  M1",11,MUTED,true);
        instrumentText.setPadding(dp(4),dp(6),0,dp(4));content.addView(instrumentText);
        pairSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                String selected=pairs[position];
                if(!selected.equals(symbol)){
                    symbol=selected;quoteFresh=false;demoEnabled=false;
                    instrumentText.setText(symbol+"  •  ОБЫЧНЫЙ РЫНОК  •  M1");
                    upButton.setEnabled(false);downButton.setEnabled(false);
                    quoteText.setText("Загружаем котировки для "+symbol+"…");
                    refreshData();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        chart=new ChartView(this);
        int chartHeight=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?dp(175):dp(245);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,chartHeight);content.addView(chart,cp);

        quoteText=label("Проверяем рыночный поток…",11,MUTED,false);quoteText.setPadding(dp(4),dp(6),0,dp(4));content.addView(quoteText);
        analysisButton=button("АНАЛИЗ",GOLD,NAVY);
        LinearLayout.LayoutParams abp=new LinearLayout.LayoutParams(-1,dp(44));abp.topMargin=dp(5);content.addView(analysisButton,abp);
        analysisButton.setOnClickListener(v->toggleAnalysis());

        analysisDivider=new View(this);analysisDivider.setBackgroundColor(0xFF263D5A);
        LinearLayout.LayoutParams divp=new LinearLayout.LayoutParams(-1,dp(1));divp.topMargin=dp(8);content.addView(analysisDivider,divp);
        analysisText=label("Для анализа ожидаются подтверждённые минутные свечи.",12,TEXT,false);
        analysisText.setPadding(dp(8),dp(8),dp(8),dp(8));content.addView(analysisText);

        controlsPanel=vertical();
        TextView expiryLabel=label("ЭКСПИРАЦИЯ",10,MUTED,true);expiryLabel.setPadding(dp(4),dp(10),0,dp(4));controlsPanel.addView(expiryLabel);
        expiryRow=horizontal();
        addExpiry(180,"3 мин");addExpiry(240,"4 мин");addExpiry(300,"5 мин");
        controlsPanel.addView(expiryRow);
        TextView stakeLabel=label("СУММА ДЕМО-СДЕЛКИ, ₽",10,MUTED,true);stakeLabel.setPadding(dp(4),dp(10),0,dp(3));controlsPanel.addView(stakeLabel);
        stakeInput=new EditText(this);stakeInput.setText("500");stakeInput.setSingleLine(true);
        stakeInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        stakeInput.setTextColor(TEXT);stakeInput.setHintTextColor(MUTED);stakeInput.setHint("Например, 500");
        controlsPanel.addView(stakeInput,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout actions=horizontal();
        upButton=button("ВВЕРХ",GREEN,Color.WHITE);downButton=button("ВНИЗ",RED,Color.WHITE);
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(52),1);half.rightMargin=dp(5);actions.addView(upButton,half);
        LinearLayout.LayoutParams half2=new LinearLayout.LayoutParams(0,dp(52),1);half2.leftMargin=dp(5);actions.addView(downButton,half2);
        LinearLayout.LayoutParams actp=new LinearLayout.LayoutParams(-1,-2);actp.topMargin=dp(8);controlsPanel.addView(actions,actp);
        upButton.setEnabled(false);downButton.setEnabled(false);
        upButton.setOnClickListener(v->placeTrade("UP"));downButton.setOnClickListener(v->placeTrade("DOWN"));
        content.addView(controlsPanel);

        signalText=label("ОЦЕНКА МОДЕЛИ: —",11,MUTED,true);signalText.setPadding(dp(4),dp(10),0,dp(3));content.addView(signalText);
        strengthBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        strengthBar.setMax(100);strengthBar.setProgress(0);strengthBar.setProgressTintList(ColorStateList.valueOf(GOLD));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(4));content.addView(strengthBar,bp);

        historyPanel=vertical();
        TextView historyTitle=label("ПОСЛЕДНИЕ СДЕЛКИ",11,MUTED,true);historyTitle.setPadding(dp(4),dp(12),0,dp(5));historyPanel.addView(historyTitle);
        historyText=label("Пока нет сделок",12,TEXT,false);historyText.setPadding(dp(8),dp(8),dp(8),dp(8));historyText.setBackgroundColor(PANEL);historyPanel.addView(historyText);
        content.addView(historyPanel);
        TextView footer=label("Только виртуальный счёт • Аналитика не гарантирует результат",10,MUTED,false);
        footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(14),0,dp(6));content.addView(footer);
        page(content);
        updateExpiryButtons();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    private void addExpiry(int seconds,String title){
        Button b=button(title,PANEL,TEXT);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.leftMargin=dp(3);p.rightMargin=dp(3);
        expiryRow.addView(b,p);b.setOnClickListener(v->{expirySeconds=seconds;updateExpiryButtons();});
        b.setTag(seconds);
    }

    private void updateExpiryButtons(){
        if(expiryRow==null)return;
        for(int i=0;i<expiryRow.getChildCount();i++){
            Button b=(Button)expiryRow.getChildAt(i);
            boolean selected=((Integer)b.getTag())==expirySeconds;
            b.setBackgroundTintList(ColorStateList.valueOf(selected?GOLD:PANEL));
            b.setTextColor(selected?NAVY:TEXT);
        }
    }

    // Wallet and trade history refresh independently; they never consume market-provider credits.
    private void refreshData(){
        if(refreshing||accessToken.isEmpty())return;
        refreshing=true;final String token=accessToken;
        io.execute(()->{
            JSONObject wallet=null;JSONArray trades=null;
            String walletError=null,tradesError=null;
            try{wallet=SupabaseApi.getWallet(token);}catch(Exception e){walletError=e.getMessage();}
            try{trades=SupabaseApi.getTrades(token);}catch(Exception e){tradesError=e.getMessage();}
            final JSONObject fw=wallet;final JSONArray ft=trades;
            final String fwe=walletError,fte=tradesError;
            runOnUiThread(()->{
                refreshing=false;
                if(isFinishing()||accessToken.isEmpty())return;
                if(fw!=null&&fw.has("balance")&&!fw.isNull("balance")){
                    try{balanceText.setText(String.format(Locale.US,"%,.2f ₽",fw.getDouble("balance")));}
                    catch(Exception ignored){balanceText.setText(fw.optString("balance")+" ₽");}
                }else balanceText.setText(fwe==null?"Баланс не найден":"Ошибка баланса: "+fwe);
                if(ft==null && fte!=null) historyText.setText("История недоступна: "+fte);
                if(ft!=null)renderHistory(ft);
            });
        });
    }

    // This is the only client path that calls Twelve Data. It runs only after ANALYSIS is pressed.
    private void fetchMarketSnapshot(){
        if(accessToken.isEmpty()||refreshingMarket)return;
        refreshingMarket=true;
        quoteFresh=false;
        if(upButton!=null)upButton.setEnabled(false);
        if(downButton!=null)downButton.setEnabled(false);
        quoteText.setText("Запрашиваем котировку для "+symbol+"…");
        analysisButton.setEnabled(false);
        final String token=accessToken,requestedSymbol=symbol;
        io.execute(()->{
            JSONObject snapshot=null;String error=null;
            try{snapshot=SupabaseApi.getMarketSnapshot(token,requestedSymbol);}
            catch(Exception e){error=e.getMessage();}
            final JSONObject fs=snapshot;final String fe=error;
            runOnUiThread(()->{
                refreshingMarket=false;
                if(isFinishing()||accessToken.isEmpty())return;
                analysisButton.setEnabled(true);
                if(!requestedSymbol.equals(symbol))return;
                if(fs==null){
                    quoteText.setText("Не удалось получить котировку"+(fe==null?"":": "+fe));
                    quoteFresh=false;upButton.setEnabled(false);downButton.setEnabled(false);
                    return;
                }
                latestCandles=fs.optJSONArray("candles");
                if(latestCandles==null)latestCandles=new JSONArray();
                quoteFresh=fs.optBoolean("quote_fresh",false);
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
                boolean canTrade=quoteFresh&&demoEnabled;
                upButton.setEnabled(canTrade);downButton.setEnabled(canTrade);
            });
        });
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
        if(!quoteFresh||!demoEnabled){Toast.makeText(this,"Сделка заблокирована: нет проверенной свежей котировки.",Toast.LENGTH_LONG).show();return;}
        double stake;
        try{stake=Double.parseDouble(stakeInput.getText().toString().replace(',','.'));}
        catch(Exception e){Toast.makeText(this,"Укажите сумму сделки.",Toast.LENGTH_SHORT).show();return;}
        if(stake<=0||stake>20000){Toast.makeText(this,"Сумма должна быть от 1 до 20 000 ₽.",Toast.LENGTH_SHORT).show();return;}
        upButton.setEnabled(false);downButton.setEnabled(false);
        final double amount=stake;final String token=accessToken;
        io.execute(()->{
            try{
                JSONObject result=SupabaseApi.openDemoTrade(token,symbol,direction,amount,expirySeconds);
                final String tradeSymbol=symbol;
                final int tradeExpiry=expirySeconds;
                runOnUiThread(()->{
                    JSONObject trade=result.optJSONObject("trade");
                    String id=trade==null?"":trade.optString("trade_id","");
                    Toast.makeText(this,"Демо-сделка открыта"+(id.isEmpty()?"":" • "+id),Toast.LENGTH_LONG).show();
                    scheduleExpiryQuote(token,tradeSymbol,tradeExpiry);
                });
            }catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Сделка не открыта: "+e.getMessage(),Toast.LENGTH_LONG).show());}
            runOnUiThread(this::refreshData);
        });
    }

    // One provider request is made only when an opened demo trade reaches expiry.
    // Wallet/history polling never calls the market provider.
    private void scheduleExpiryQuote(String token,String tradeSymbol,int seconds){
        handler.postDelayed(()->{
            if(!token.equals(accessToken))return;
            io.execute(()->{
                try{
                    // Fetch the final quote; the server settles all trades now due.
                    SupabaseApi.getMarketSnapshot(token,tradeSymbol);
                }catch(Exception ignored){
                    // The next explicit ANALYSIS request can retry settlement.
                }
                runOnUiThread(()->{
                    if(token.equals(accessToken)){
                        refreshData();
                        if(tradeSymbol.equals(symbol)){
                            quoteFresh=false;
                            quoteText.setText("Сделка завершена по времени. Нажмите АНАЛИЗ для новой котировки.");
                            upButton.setEnabled(false);
                            downButton.setEnabled(false);
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
        int chartHeight=analysisMode?dp(420):(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?dp(175):dp(245));
        chart.getLayoutParams().height=chartHeight;chart.requestLayout();
        accountPanel.setVisibility(analysisMode?View.GONE:View.VISIBLE);
        quoteText.setVisibility(View.VISIBLE);
        controlsPanel.setVisibility(analysisMode?View.GONE:View.VISIBLE);
        historyPanel.setVisibility(analysisMode?View.GONE:View.VISIBLE);
        signalText.setVisibility(View.VISIBLE);strengthBar.setVisibility(View.VISIBLE);
        analysisButton.setText(analysisMode?"СВЕРНУТЬ АНАЛИЗ":"АНАЛИЗ");
        MarketAnalyzer.Result currentAnalysis=MarketAnalyzer.analyze(latestCandles);
        chart.setDecision(currentAnalysis.direction,currentAnalysis.strength);
        analysisText.setText(analysisMode?"ПОЛНЫЙ АНАЛИЗ\n"+currentAnalysis.explanation:currentAnalysis.explanation);
    }

    private void showMenu(){
        String[] items={"API-ключ Twelve Data","Обновить данные","Telegram-бот","Выйти из аккаунта"};
        new AlertDialog.Builder(this).setTitle("Настройки").setItems(items,(d,which)->{
            if(which==0)showTwelveDataSettings();
            else if(which==1)refreshData();
            else if(which==2)new AlertDialog.Builder(this).setMessage("Ссылка на Telegram-бот ещё не настроена.").setPositiveButton("ОК",null).show();
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
        accessToken="";refreshToken="";quoteFresh=false;demoEnabled=false;handler.removeCallbacks(poll);
        SessionStore.clear(this);showLogin("Вы вышли из аккаунта.");
    }

    @Override protected void onDestroy(){
        handler.removeCallbacks(poll);
        super.onDestroy();
    }
}
