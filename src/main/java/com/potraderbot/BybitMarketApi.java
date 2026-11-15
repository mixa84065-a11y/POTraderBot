package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public final class BybitMarketApi {
    private BybitMarketApi() {}

    private static JSONObject get(String path) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.bybit.com"+path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept","application/json");
        int code=c.getResponseCode();
        InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(in!=null){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();}
        c.disconnect();
        JSONObject body=new JSONObject(out.toString("UTF-8"));
        if(code<200||code>=300||body.optInt("retCode",-1)!=0)
            throw new Exception("Bybit API: "+body.optString("retMsg","HTTP "+code));
        return body;
    }

    public static JSONObject getLinearMarket(String symbol) throws Exception {
        String encoded=URLEncoder.encode(symbol,"UTF-8");
        JSONObject ticker=get("/v5/market/tickers?category=linear&symbol="+encoded)
            .getJSONObject("result").getJSONArray("list").getJSONObject(0);
        JSONArray raw=get("/v5/market/kline?category=linear&symbol="+encoded+"&interval=1&limit=100")
            .getJSONObject("result").getJSONArray("list");
        JSONArray candles=new JSONArray();
        for(int i=raw.length()-1;i>=0;i--){
            JSONArray k=raw.getJSONArray(i);
            JSONObject candle=new JSONObject();
            candle.put("open_time",k.optString(0));
            candle.put("open",number(k.optString(1)));
            candle.put("high",number(k.optString(2)));
            candle.put("low",number(k.optString(3)));
            candle.put("close",number(k.optString(4)));
            candles.put(candle);
        }
        JSONObject result=new JSONObject();
        result.put("symbol",symbol);
        result.put("lastPrice",ticker.optString("lastPrice"));
        result.put("price24hPcnt",ticker.optString("price24hPcnt"));
        result.put("candles",candles);
        return result;
    }

    private static double number(String value) throws Exception {
        double parsed=Double.parseDouble(value);
        if(!Double.isFinite(parsed)||parsed<=0)throw new Exception("Invalid market candle");
        return parsed;
    }
}
