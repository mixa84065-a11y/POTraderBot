package com.potraderbot;

import java.util.List;

/** EMA(9/21) + Wilder RSI(14) signal filter. Signals are informational, not predictions. */
public final class MarketAnalyzer {
    public enum Signal { CALL, PUT, WAIT }

    public static final class Analysis {
        public final Signal signal;
        public final double ema9, ema21, rsi14;
        public final String explanation;
        Analysis(Signal signal, double ema9, double ema21, double rsi14, String explanation) {
            this.signal=signal; this.ema9=ema9; this.ema21=ema21; this.rsi14=rsi14; this.explanation=explanation;
        }
    }

    public Analysis analyze(List<Candle> candles) {
        if(candles==null || candles.size()<30) return new Analysis(Signal.WAIT,0,0,0,"Недостаточно закрытых свечей (нужно минимум 30).");
        for(int i=1;i<candles.size();i++) if(candles.get(i).time<=candles.get(i-1).time)
            return new Analysis(Signal.WAIT,0,0,0,"Свечи пришли не в хронологическом порядке.");
        double e9=ema(candles,9), e21=ema(candles,21), rsi=rsiWilder(candles,14);
        Candle last=candles.get(candles.size()-1), prev=candles.get(candles.size()-2);
        boolean trendUp=e9>e21, trendDown=e9<e21;
        boolean rsiUp=rsi>=50 && rsi<=68, rsiDown=rsi>=32 && rsi<=50;
        boolean candleUp=last.close>last.open && last.close>prev.close;
        boolean candleDown=last.close<last.open && last.close<prev.close;
        Signal signal=trendUp&&rsiUp&&candleUp?Signal.CALL:
                trendDown&&rsiDown&&candleDown?Signal.PUT:Signal.WAIT;
        StringBuilder why=new StringBuilder();
        why.append("ПОДТВЕРЖДЕНИЯ: ");
        appendFactor(why,"EMA 9 выше EMA 21",trendUp);
        appendFactor(why,"EMA 9 ниже EMA 21",trendDown);
        appendFactor(why,"RSI 14 в зоне 50–68",rsiUp);
        appendFactor(why,"RSI 14 в зоне 32–50",rsiDown);
        appendFactor(why,"последняя свеча бычья и выше предыдущего закрытия",candleUp);
        appendFactor(why,"последняя свеча медвежья и ниже предыдущего закрытия",candleDown);
        if(!trendUp && !trendDown && !rsiUp && !rsiDown && !candleUp && !candleDown) why.append("нет совпадений по проверенным условиям");
        why.append("\nПРОТИВОРЕЧИЯ / НЕСОВПАДЕНИЯ: ");
        if(signal==Signal.CALL || signal==Signal.PUT) why.append("встречных условий среди EMA, RSI и последней свечи не выявлено.");
        else {
            boolean anyMissing=false;
            if(!trendUp && !trendDown){why.append("EMA 9 и EMA 21 не задают направление; ");anyMissing=true;}
            if(trendUp && !rsiUp){why.append("RSI не подтверждает восходящий тренд; ");anyMissing=true;}
            if(trendDown && !rsiDown){why.append("RSI не подтверждает нисходящий тренд; ");anyMissing=true;}
            if(trendUp && !candleUp){why.append("свеча не подтвердила движение вверх; ");anyMissing=true;}
            if(trendDown && !candleDown){why.append("свеча не подтвердила движение вниз; ");anyMissing=true;}
            if(!rsiUp && !rsiDown && (trendUp || trendDown)){why.append("RSI вне рабочих зон 32–68; ");anyMissing=true;}
            if(!candleUp && !candleDown && !trendUp && !trendDown){why.append("последняя свеча не подтвердила импульс; ");anyMissing=true;}
            if(!anyMissing) why.append("три условия не совпали в одном направлении.");
        }
        why.append("\nРЕШЕНИЕ: ");
        if(signal==Signal.CALL) why.append("ВВЕРХ — совпали тренд, RSI и импульс свечи.");
        else if(signal==Signal.PUT) why.append("ВНИЗ — совпали тренд, RSI и импульс свечи.");
        else why.append("ЖДАТЬ — нет одновременного подтверждения всех трёх условий.");
        why.append("\nEMA 9: ").append(String.format(java.util.Locale.US,"%.5f",e9))
                .append(" • EMA 21: ").append(String.format(java.util.Locale.US,"%.5f",e21))
                .append(" • RSI 14: ").append(String.format(java.util.Locale.US,"%.2f",rsi))
                .append("\nВероятность не заявляется: для неё нужна проверенная статистика тестирования стратегии.");
        return new Analysis(signal,e9,e21,rsi,why.toString());
    }

    private void appendFactor(StringBuilder out,String text,boolean ok){
        if(ok) out.append("✓ ").append(text).append("; ");
    }
    private double ema(List<Candle> c,int period){
        double k=2.0/(period+1.0),v=c.get(0).close;
        for(int i=1;i<c.size();i++) v=c.get(i).close*k+v*(1-k);
        return v;
    }
    private double rsiWilder(List<Candle> c,int period){
        double gains=0,losses=0;
        for(int i=1;i<=period;i++){double d=c.get(i).close-c.get(i-1).close;if(d>0)gains+=d;else losses-=d;}
        double ag=gains/period,al=losses/period;
        for(int i=period+1;i<c.size();i++){double d=c.get(i).close-c.get(i-1).close;ag=(ag*(period-1)+Math.max(d,0))/period;al=(al*(period-1)+Math.max(-d,0))/period;}
        if(al==0)return ag==0?50:100;
        double rs=ag/al;return 100.0-(100.0/(1.0+rs));
    }
}