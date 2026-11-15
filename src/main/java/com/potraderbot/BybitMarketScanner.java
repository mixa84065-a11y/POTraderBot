package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Read-only Bybit V5 market discovery and multi-symbol scanner.
 * Supports public Spot, Linear and Inverse candles. Options are catalogued
 * separately but are not scored as directional underlying trades.
 */
public final class BybitMarketScanner {
    public static final String[] CANDLE_CATEGORIES = {"spot", "linear", "inverse"};
    public static final String[] CATALOG_CATEGORIES = {"spot", "linear", "inverse", "option"};

    public static final class Instrument {
        public final String category, symbol, baseCoin, quoteCoin, displayName, marketRegion, symbolType;
        public final double turnover24h;
        public final long deliveryTime;
        Instrument(String category, String symbol, String baseCoin, String quoteCoin, double turnover24h) {
            this(category,symbol,baseCoin,quoteCoin,turnover24h,symbol,"","",0L);
        }
        Instrument(String category, String symbol, String baseCoin, String quoteCoin, double turnover24h,
                   String displayName, String marketRegion, String symbolType) {
            this(category,symbol,baseCoin,quoteCoin,turnover24h,displayName,marketRegion,symbolType,0L);
        }
        Instrument(String category, String symbol, String baseCoin, String quoteCoin, double turnover24h,
                   String displayName, String marketRegion, String symbolType, long deliveryTime) {
            this.category=category; this.symbol=symbol; this.baseCoin=baseCoin;
            this.quoteCoin=quoteCoin; this.turnover24h=turnover24h; this.deliveryTime=deliveryTime;
            this.displayName=displayName==null||displayName.isEmpty()?symbol:displayName;
            this.marketRegion=marketRegion==null?"":marketRegion;
            this.symbolType=symbolType==null?"":symbolType;
        }
    }
    public static final class Signal {
        public final Instrument instrument;
        public final double lastPrice;
        public final int score;
        public final String direction, risk, explanation;
        Signal(Instrument instrument, double lastPrice, int score) {
            this(instrument,lastPrice,score,score>=4?"ВВЕРХ":score<=-4?"ВНИЗ":"НЕТ СИГНАЛА","ОЖИДАНИЕ","Оценка не рассчитана.");
        }
        Signal(Instrument instrument,double lastPrice,int score,String direction,String risk,String explanation) {
            this.instrument=instrument; this.lastPrice=lastPrice; this.score=score;
            this.direction=direction; this.risk=risk; this.explanation=explanation;
        }
    }
    private BybitMarketScanner() {}

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(8000); c.setReadTimeout(10000);
        c.setRequestProperty("Accept","application/json");
        int code=c.getResponseCode();
        InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(in!=null){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();}
        c.disconnect();
        if(code<200||code>=300)throw new Exception("Bybit HTTP "+code);
        JSONObject json=new JSONObject(out.toString("UTF-8"));
        if(json.optInt("retCode",-1)!=0)throw new Exception("Bybit "+json.optInt("retCode")+": "+json.optString("retMsg"));
        return json;
    }
    private static String enc(String s) throws Exception { return java.net.URLEncoder.encode(s,"UTF-8"); }

    public static List<Instrument> loadInstruments(String category) throws Exception {
        if(!java.util.Arrays.asList(CATALOG_CATEGORIES).contains(category)) throw new IllegalArgumentException("Unsupported Bybit category");
        List<Instrument> result=new ArrayList<>();
        String cursor="";
        for(int page=0;page<10;page++){
            String url="https://api.bybit.com/v5/market/instruments-info?category="+category
                +( "option".equals(category)?"&baseCoin=All":"")
                +("spot".equals(category)?"":"&limit=1000")
                +(cursor.isEmpty()?"":"&cursor="+enc(cursor));
            JSONObject root=get(url).getJSONObject("result");
            JSONArray list=root.optJSONArray("list");
            if(list!=null)for(int i=0;i<list.length();i++){
                JSONObject x=list.getJSONObject(i);
                String status=x.optString("status","Trading");
                // Bybit options can be PreLaunch, Trading, or Delivering; keep the full active lifecycle catalog.
                if("option".equals(category)){
                    if(!("PreLaunch".equalsIgnoreCase(status)||"Trading".equalsIgnoreCase(status)||"Delivering".equalsIgnoreCase(status)))continue;
                }else if(!"Trading".equalsIgnoreCase(status))continue;
                String symbol=x.optString("symbol","");
                if(symbol.isEmpty())continue;
                long delivery=0L;
                try{delivery=Long.parseLong(x.optString("deliveryTime","0"));}catch(Exception ignored){}
                result.add(new Instrument(category,symbol,x.optString("baseCoin",""),x.optString("quoteCoin",""),0,
                    symbol,"","",delivery));
            }
            cursor=root.optString("nextPageCursor","");
            if(cursor.isEmpty())break;
        }
        return result;
    }

    private static double ema(double[] a,int p){double k=2.0/(p+1),v=a[0];for(int i=1;i<a.length;i++)v=a[i]*k+v*(1-k);return v;}
    private static double rsi(double[] a,int p){
        double up=0,down=0;
        for(int i=1;i<=p;i++){double d=a[i]-a[i-1];if(d>0)up+=d;else down-=d;}
        up/=p;down/=p;
        for(int i=p+1;i<a.length;i++){double d=a[i]-a[i-1];up=(up*(p-1)+Math.max(0,d))/p;down=(down*(p-1)+Math.max(0,-d))/p;}
        return down==0?100:100-100/(1+up/down);
    }
    private static Signal analyze(Instrument instrument) throws Exception {
        String base="https://api.bybit.com/v5/market/";
        JSONObject ticker=get(base+"tickers?category="+instrument.category+"&symbol="+enc(instrument.symbol))
            .getJSONObject("result").getJSONArray("list").getJSONObject(0);
        JSONArray rows=get(base+"kline?category="+instrument.category+"&symbol="+enc(instrument.symbol)+"&interval=1&limit=100")
            .getJSONObject("result").getJSONArray("list");
        List<Double> closes=new ArrayList<>();
        // Bybit returns newest candle first; omit the still-forming candle.
        for(int i=rows.length()-2;i>=0;i--)closes.add(Double.parseDouble(rows.getJSONArray(i).getString(4)));
        if(closes.size()<55)throw new Exception("Недостаточно закрытых свечей: требуется 55");
        double[] c=new double[closes.size()];for(int i=0;i<c.length;i++)c[i]=closes.get(i);
        long lastTime=Long.parseLong(rows.getJSONArray(1).getString(0));
        double last=Double.parseDouble(ticker.getString("lastPrice"));
        double bid=ticker.optDouble("bid1Price",0), ask=ticker.optDouble("ask1Price",0);
        double funding=ticker.has("fundingRate")?ticker.optDouble("fundingRate",Double.NaN):Double.NaN;
        double oiChange=ticker.has("openInterestChangePct")?ticker.optDouble("openInterestChangePct",Double.NaN):Double.NaN;
        String type=instrument.symbolType.isEmpty()?instrument.category:"tradfi";
        MarketStrategyEngine.Result result=MarketStrategyEngine.analyze(type,c,bid,ask,
            ticker.optDouble("turnover24h",instrument.turnover24h),funding,oiChange,
            System.currentTimeMillis()-lastTime,true);
        return new Signal(instrument,last,result.score,result.direction,result.risk,result.explanation);
    }

    /** Scans the most liquid active instruments concurrently; returns strongest signals first. */
    public static List<Signal> scan(String category,int maxInstruments) throws Exception {
        if(!java.util.Arrays.asList(CANDLE_CATEGORIES).contains(category)) throw new IllegalArgumentException("Для этой категории пока нет свечного направленного сканера");
        JSONObject tickers=get("https://api.bybit.com/v5/market/tickers?category="+category);
        JSONArray tickerList=tickers.getJSONObject("result").optJSONArray("list");
        List<Instrument> candidates=new ArrayList<>();
        if(tickerList!=null)for(int i=0;i<tickerList.length();i++){
            JSONObject t=tickerList.getJSONObject(i);
            String symbol=t.optString("symbol","");
            double turnover=t.optDouble("turnover24h",0);
            if(!symbol.isEmpty()&&turnover>0)candidates.add(new Instrument(category,symbol,"","",turnover));
        }
        return scanCandidates(candidates,maxInstruments);
    }

    /** Returns the Bybit TradFi stock/ETF/forex/commodity catalog even when the market is closed. */
    public static List<Instrument> loadTradFiCatalog(String symbolType,int maxItems) throws Exception {
        if(!java.util.Arrays.asList("stock","ETF","forex","commodity").contains(symbolType))
            throw new IllegalArgumentException("Неподдерживаемый тип Bybit TradFi");
        List<Instrument> catalog=new ArrayList<>();
        String cursor="";
        for(int page=0;page<10;page++){
            String url="https://api.bybit.com/v5/market/instrument-summary?category=linear&symbolType="+enc(symbolType)+"&limit=1000"
                +(cursor.isEmpty()?"":"&cursor="+enc(cursor));
            JSONObject result=get(url).getJSONObject("result");
            JSONArray list=result.optJSONArray("list");
            if(list!=null)for(int i=0;i<list.length();i++){
                JSONObject x=list.getJSONObject(i);
                String symbol=x.optString("symbol","");
                if(symbol.isEmpty())continue;
                String name=x.optString("fullName",x.optString("displayName",symbol));
                catalog.add(new Instrument("linear",symbol,x.optString("baseCoin",""),x.optString("settleCoin","USDT"),
                    0,name,x.optString("marketRegion",""),x.optString("symbolType",symbolType)));
            }
            cursor=result.optString("nextPageCursor","");
            if(cursor.isEmpty())break;
        }
        if(catalog.isEmpty())return catalog;
        JSONObject tickers=get("https://api.bybit.com/v5/market/tickers?category=linear").getJSONObject("result");
        JSONArray tickerList=tickers.optJSONArray("list");
        java.util.Map<String,Double> turnoverBySymbol=new java.util.HashMap<>();
        if(tickerList!=null)for(int i=0;i<tickerList.length();i++){
            JSONObject t=tickerList.getJSONObject(i);
            turnoverBySymbol.put(t.optString("symbol",""),t.optDouble("turnover24h",0));
        }
        List<Instrument> ranked=new ArrayList<>();
        for(Instrument x:catalog){
            double turnover=turnoverBySymbol.containsKey(x.symbol)?turnoverBySymbol.get(x.symbol):0;
            // Keep zero-turnover instruments in the catalog: TradFi markets close on weekends/holidays.
            ranked.add(new Instrument(x.category,x.symbol,x.baseCoin,x.quoteCoin,turnover,x.displayName,x.marketRegion,x.symbolType));
        }
        Collections.sort(ranked,(a,b)->Double.compare(b.turnover24h,a.turnover24h));
        int keep=Math.min(Math.max(0,maxItems),ranked.size());
        return new ArrayList<>(ranked.subList(0,keep));
    }

    /** Scores the most liquid available TradFi instruments; stale/closed markets produce no signal. */
    public static List<Signal> scanTradFi(String symbolType,int maxInstruments) throws Exception {
        List<Instrument> candidates=loadTradFiCatalog(symbolType,maxInstruments);
        return scanCandidates(candidates,maxInstruments);
    }

    /** Scores liquid option contracts using exchange Greeks/IV and the underlying trend. */
    public static List<Signal> scanOptions(int maxInstruments) throws Exception {
        List<Instrument> instruments=loadInstruments("option");
        List<Instrument> candidates=new ArrayList<>();
        for(Instrument x:instruments)if(x.deliveryTime>System.currentTimeMillis())candidates.add(x);
        int count=Math.min(Math.max(0,maxInstruments),candidates.size());
        if(count==0)return new ArrayList<>();
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try{
            List<Future<Signal>> futures=new ArrayList<>();
            for(int i=0;i<count;i++){
                final Instrument option=candidates.get(i);
                futures.add(pool.submit(()->{
                    JSONObject ticker=get("https://api.bybit.com/v5/market/tickers?category=option&symbol="+enc(option.symbol))
                        .getJSONObject("result").getJSONArray("list").getJSONObject(0);
                    Instrument underlying=new Instrument("linear",option.baseCoin+"USDT",option.baseCoin,"USDT",0);
                    Signal baseSignal=analyze(underlying);
                    double hours=(option.deliveryTime-System.currentTimeMillis())/3600000.0;
                    MarketStrategyEngine.OptionResult result=MarketStrategyEngine.analyzeOption(
                        ticker.optDouble("bid1Price",0),ticker.optDouble("ask1Price",0),
                        ticker.optDouble("markPrice",0),ticker.optDouble("delta",Double.NaN),
                        ticker.optDouble("gamma",Double.NaN),ticker.optDouble("vega",Double.NaN),
                        ticker.optDouble("theta",Double.NaN),ticker.optDouble("markPriceIv",Double.NaN),
                        ticker.optDouble("turnover24h",0),hours,baseSignal.score);
                    Instrument optionWithLiquidity=new Instrument("option",option.symbol,option.baseCoin,option.quoteCoin,
                        ticker.optDouble("turnover24h",0),option.displayName,option.marketRegion,option.symbolType,option.deliveryTime);
                    return new Signal(optionWithLiquidity,ticker.optDouble("lastPrice",0),baseSignal.score,
                        result.stance,result.risk,result.explanation);
                }));
            }
            List<Signal> signals=new ArrayList<>();
            for(Future<Signal> f:futures)try{signals.add(f.get());}catch(Exception ignored){}
            Collections.sort(signals,(a,b)->Double.compare(b.instrument.turnover24h,a.instrument.turnover24h));
            return signals;
        }finally{pool.shutdownNow();}
    }

    private static List<Signal> scanCandidates(List<Instrument> candidates,int maxInstruments) throws Exception {
        Collections.sort(candidates,(a,b)->Double.compare(b.turnover24h,a.turnover24h));
        int count=Math.min(Math.max(0,maxInstruments),candidates.size());
        if(count==0)return new ArrayList<>();
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try{
            List<Future<Signal>> futures=new ArrayList<>();
            for(int i=0;i<count;i++){final Instrument item=candidates.get(i);futures.add(pool.submit(()->analyze(item)));}
            List<Signal> signals=new ArrayList<>();
            for(Future<Signal> f:futures)try{signals.add(f.get());}catch(Exception ignored){}
            Collections.sort(signals,(a,b)->Integer.compare(Math.abs(b.score),Math.abs(a.score)));
            return signals;
        }finally{pool.shutdownNow();}
    }
}
