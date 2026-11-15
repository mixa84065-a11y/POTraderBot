package com.potraderbot;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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
    private Spinner symbolPicker;
    private TextView priceText, changeText, balanceText, modeText, signalText, riskText, historyText, statusText;
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
        prefs=getSharedPreferences("bybit_paper_ledger",MODE_PRIVATE);
        if(!prefs.contains("cash"))prefs.edit().putFloat("cash",(float)START_BALANCE).apply();
        String today=new SimpleDateFormat("yyyyMMdd",Locale.US).format(new Date());
        if(!today.equals(prefs.getString("loss_day",""))){prefs.edit().putString("loss_day",today).putFloat("loss_today",0).apply();}
        autoEnabled=prefs.getBoolean("auto",false);
        if(prefs.getBoolean("open",false))symbol=prefs.getString("trade_symbol","BTCUSDT");
        buildScreen();
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
        TextView sub=text("SPOT • ПУБЛИЧНЫЕ КОТИРОВКИ BYBIT",10,MUTED,true);sub.setPadding(0,dp(2),0,dp(10));root.addView(sub);
        LinearLayout select=horizontal();
        TextView pairLabel=text("ИНСТРУМЕНТ",10,MUTED,true);select.addView(pairLabel);
        symbolPicker=new Spinner(this);
        String[] symbols={"BTCUSDT","ETHUSDT","SOLUSDT","XRPUSDT","BNBUSDT","DOGEUSDT"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,symbols);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);symbolPicker.setAdapter(adapter);
        select.addView(symbolPicker,new LinearLayout.LayoutParams(0,dp(44),1));
        root.addView(select);
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
        TextView live=text("● LIVE",11,GREEN,true);quote.addView(live); 
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
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);scroll.addView(root);setContentView(scroll);
        renderLedger();
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
                if(rr==null||tt==null){statusText.setText("Нет котировок Bybit: "+ee);return;}
                try{
                    JSONArray ordered=new JSONArray();for(int i=rr.length()-1;i>=0;i--)ordered.put(rr.getJSONArray(i));
                    // Discard the still-forming candle; only closed bars feed the strategy.
                    if(ordered.length()>1)ordered.remove(ordered.length()-1);
                    candles=ordered;lastPrice=Double.parseDouble(tt.getString("lastPrice"));
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
        if(candles.length()<30){statusText.setText("Недостаточно закрытых минутных свечей.");return;}
        try{
            long candleTime=Long.parseLong(candles.getJSONArray(candles.length()-1).getString(0));
            if(System.currentTimeMillis()-candleTime>180000L){
                statusText.setText("Последняя закрытая свеча устарела. Новые автосделки заблокированы.");
                renderLedger();return;
            }
        }catch(Exception e){statusText.setText("Не удалось проверить время свечи. Автосделки заблокированы.");return;}
        int s=score();String dir=s>=3?"ПОКУПКА":s<=-3?"СЛАБЫЙ ТРЕНД / ВЫХОД":"НЕТ СИГНАЛА";
        signalText.setText("АНАЛИЗ: "+dir+"  •  SCORE "+s);
        signalText.setTextColor(s>=3?GREEN:s<=-3?RED:WHITE);
        statusText.setText("EMA 9/21 + RSI(14) + импульс. Балл — эвристика, не вероятность прибыли.");
        managePaperTrade(s);renderLedger();
    }
    private void managePaperTrade(int s){
        boolean open=prefs.getBoolean("open",false);
        if(open){
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
        }else if(autoEnabled&&s>=3&&lastPrice>0){
            double cash=prefs.getFloat("cash",(float)START_BALANCE);
            if(cash>=STAKE){
                prefs.edit().putFloat("cash",(float)(cash-STAKE)).putFloat("entry",(float)lastPrice)
                    .putFloat("stake",(float)STAKE).putString("trade_symbol",symbol).putBoolean("open",true)
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
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(poll);handler.post(poll);}
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
                    p.setColor(close>=open?GREEN:RED);p.setStrokeWidth(Math.max(1,dp(1.2f)));
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