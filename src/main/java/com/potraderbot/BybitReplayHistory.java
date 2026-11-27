package com.potraderbot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public, read-only spot candles. No API credentials or order endpoints. */
final class BybitReplayHistory {
    static final long MINUTE=60000L;
    static final int WINDOW=1440;
    static final class Candle {
        final long time;final double open,close,turnover;
        Candle(long t,double o,double c,double v){
            if(t<0||t%MINUTE!=0||!Double.isFinite(o)||!Double.isFinite(c)||!Double.isFinite(v)
                ||o<1e-12||o>1e12||c<1e-12||c>1e12||v<0||v>1e15)throw new IllegalArgumentException("Некорректная свеча");
            time=t;open=o;close=c;turnover=v;
        }
    }
    private static JSONObject get(String symbol,long start,long end)throws Exception{
        URL url=new URL("https://api.bybit.com/v5/market/kline?category=spot&symbol="+symbol+"&interval=1&start="+start+"&end="+end+"&limit=1000");
        HttpsURLConnection conn=(HttpsURLConnection)url.openConnection();
        conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept","application/json");
        try{
            int status=conn.getResponseCode();
            if(status!=200)throw new IOException("Bybit HTTP "+status);
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>1500000)throw new IOException("Слишком большой ответ");out.write(buffer,0,n);}
                JSONObject result=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                if(result.optInt("retCode",-1)!=0)throw new IOException("Bybit API: код "+result.optInt("retCode",-1));
                return result;
            }
        }finally{conn.disconnect();}
    }
    static String download(String symbol,int count)throws Exception{
        if(symbol==null||!symbol.matches("[A-Z0-9]{1,20}USDT")||count<500||count>5000)throw new IllegalArgumentException("Неверная пара или объём истории");
        long last=System.currentTimeMillis()/MINUTE*MINUTE-MINUTE;
        int total=count+WINDOW-1;
        long first=last-(total-1L)*MINUTE;
        TreeMap<Long,Candle> sorted=new TreeMap<>();
        for(long start=first;start<=last;){
            long end=Math.min(last,start+999L*MINUTE);
            JSONObject response=get(symbol,start,end+MINUTE-1);
            long server=response.optLong("time",0);
            if(server<last+MINUTE)throw new IOException("Время сервера не подтверждает закрытие последней свечи");
            JSONObject result=response.getJSONObject("result");
            if(!symbol.equals(result.optString("symbol"))||!"spot".equals(result.optString("category")))throw new IOException("Ответ не соответствует инструменту");
            JSONArray rows=result.getJSONArray("list");
            if(rows.length()>1000)throw new IOException("Неверный размер страницы");
            for(int i=0;i<rows.length();i++){
                JSONArray row=rows.getJSONArray(i);
                Candle c=new Candle(Long.parseLong(row.getString(0)),Double.parseDouble(row.getString(1)),Double.parseDouble(row.getString(4)),Double.parseDouble(row.getString(6)));
                if(c.time<start||c.time>end||sorted.put(c.time,c)!=null)throw new IOException("Лишняя или повторная свеча");
            }
            start=end+MINUTE;
        }
        if(sorted.size()!=total||sorted.firstKey()!=first||sorted.lastKey()!=last)throw new IOException("История неполная; расчёт отменён");
        return csv(new ArrayList<>(sorted.values()),WINDOW,count);
    }
    // The first WINDOW-1 observations warm up the trailing turnover calculation.
    // At each signal close only current and earlier completed candles contribute.
    static String csv(List<Candle> candles,int window,int count){
        if(window<1||count<1||candles==null||candles.size()!=window-1+count)throw new IllegalArgumentException("Недостаточно истории");
        StringBuilder out=new StringBuilder("timestamp_ms,open,close,turnover24h\n");
        double sum=0;
        for(int i=0;i<candles.size();i++){
            Candle c=candles.get(i);
            if(c==null||(i>0&&c.time-candles.get(i-1).time!=MINUTE))throw new IllegalArgumentException("Пропуск в истории");
            sum+=c.turnover;if(i>=window)sum-=candles.get(i-window).turnover;
            if(i>=window-1){
                if(!Double.isFinite(sum)||sum<=0||sum>1e18)throw new IllegalArgumentException("Нет подтверждённого оборота");
                out.append(c.time).append(',').append(c.open).append(',').append(c.close).append(',').append(sum).append('\n');
            }
        }
        return out.toString();
    }
}
