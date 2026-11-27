package com.potraderbot;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Wilder ATR on closed bars only. Describes volatility, not price direction. */
final class MarketVolatility {
    static final int PERIOD=14;
    static final class Reading {
        final double atr,percent;
        Reading(double a,double p){atr=a;percent=p;}
    }
    static Reading calculate(List<double[]> bars){
        if(bars==null||bars.size()<PERIOD+1)return null;
        double atr=0,previous=0;
        for(int i=0;i<bars.size();i++){
            double[] x=bars.get(i);
            if(x==null||x.length<4)return null;
            double o=x[0],h=x[1],l=x[2],c=x[3];
            if(!Double.isFinite(o)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(c)
                    ||l<=0||h<Math.max(o,c)||l>Math.min(o,c))return null;
            if(i>0){
                double tr=Math.max(h-l,Math.max(Math.abs(h-previous),Math.abs(l-previous)));
                if(i<=PERIOD){atr+=tr;if(i==PERIOD)atr/=PERIOD;}
                else atr=(atr*(PERIOD-1)+tr)/PERIOD;
            }
            previous=c;
        }
        double percent=atr/previous*100;
        return Double.isFinite(atr)&&Double.isFinite(percent)?new Reading(atr,percent):null;
    }
    static Reading ordinary(JSONArray rows){
        if(rows==null)return null;
        ArrayList<double[]> bars=new ArrayList<>();
        // The source may contain a still-forming last candle. Omit it conservatively.
        for(int i=0;i<rows.length()-1;i++){
            JSONObject o=rows.optJSONObject(i);if(o==null)return null;
            bars.add(new double[]{o.optDouble("open",Double.NaN),o.optDouble("high",Double.NaN),o.optDouble("low",Double.NaN),o.optDouble("close",Double.NaN)});
        }
        return calculate(bars);
    }
    static Reading bybit(JSONArray rows){
        if(rows==null)return null;
        ArrayList<double[]> bars=new ArrayList<>();
        try{for(int i=0;i<rows.length();i++){
            JSONArray a=rows.getJSONArray(i);
            bars.add(new double[]{a.getDouble(1),a.getDouble(2),a.getDouble(3),a.getDouble(4)});
        }}catch(Exception e){return null;}
        return calculate(bars);
    }
    static String describe(Reading r){
        if(r==null)return "ATR(14): недостаточно корректных свечей.";
        String value=java.math.BigDecimal.valueOf(r.atr).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
        String pct=r.percent>0&&r.percent<0.005?"<0,01%":String.format(java.util.Locale.forLanguageTag("ru-RU"),"%.2f%%",r.percent);
        return "ATR(14): "+value+" ("+pct+" цены закрытия). Средний истинный диапазон M1, не прогноз направления.";
    }
}
