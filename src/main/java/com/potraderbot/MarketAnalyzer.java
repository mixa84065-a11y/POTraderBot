package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MarketAnalyzer {
    public static final class Result {
        public final String direction;
        public final int strength;
        public final String explanation;
        Result(String d, int s, String e) { direction=d; strength=s; explanation=e; }
    }
    private MarketAnalyzer() {}

    public static Result analyze(JSONArray candles) {
        if (candles == null || candles.length() < 25)
            return new Result("НЕТ СИГНАЛА",0,"Для анализа нужно минимум 25 подтверждённых минутных свечей. Не открывайте сделку по неполным данным.");
        int n=candles.length();
        double[] close=new double[n];
        for(int i=0;i<n;i++){
            JSONObject c=candles.optJSONObject(i);
            if(c==null) return new Result("НЕТ СИГНАЛА",0,"В данных графика обнаружена ошибка. Анализ временно недоступен.");
            close[i]=c.optDouble("close",Double.NaN);
            if((Double.isNaN(close[i]) || Double.isInfinite(close[i])) || close[i]<=0)
                return new Result("НЕТ СИГНАЛА",0,"Котировки неполные. Анализ временно недоступен.");
        }
        double ema9=ema(close,9), ema21=ema(close,21), rsi=rsi(close,14);
        double momentum=(close[n-1]-close[n-4])/close[n-4]*100.0;
        int score=0;
        if(ema9>ema21) score+=2; else if(ema9<ema21) score-=2;
        if(momentum>0.002) score+=1; else if(momentum< -0.002) score-=1;
        if(rsi>=52 && rsi<=70) score+=1;
        else if(rsi<=48 && rsi>=30) score-=1;
        else if(rsi>75) score-=1;
        else if(rsi<25) score+=1;
        String direction=score>0?"ВВЕРХ":score<0?"ВНИЗ":"НЕТ ЧЁТКОГО ПРЕИМУЩЕСТВА";
        int strength=score==0?50:Math.min(78,50+Math.abs(score)*6);
        String trend=ema9>ema21?"EMA 9 выше EMA 21":ema9<ema21?"EMA 9 ниже EMA 21":"EMA 9 и EMA 21 почти совпадают";
        String mom=momentum>0?"последние 3 свечи растут":momentum<0?"последние 3 свечи снижаются":"импульс слабый";
        String rsiText=String.format(java.util.Locale.US,"RSI(14) %.1f",rsi);
        String explanation=trend+"; "+mom+"; "+rsiText+". Оценка направления — эвристика, не статистическая вероятность выигрыша.";
        return new Result(direction,strength,explanation);
    }
    private static double ema(double[] v,int period){
        double alpha=2.0/(period+1.0), e=v[0];
        for(int i=1;i<v.length;i++) e=alpha*v[i]+(1-alpha)*e;
        return e;
    }
    private static double rsi(double[] v,int period){
        double gain=0,loss=0;
        for(int i=1;i<=period;i++){double d=v[i]-v[i-1];if(d>0)gain+=d;else loss-=d;}
        gain/=period;loss/=period;
        for(int i=period+1;i<v.length;i++){
            double d=v[i]-v[i-1];
            gain=(gain*(period-1)+Math.max(d,0))/period;
            loss=(loss*(period-1)+Math.max(-d,0))/period;
        }
        if(loss==0)return 100;
        return 100-(100/(1+gain/loss));
    }
}
