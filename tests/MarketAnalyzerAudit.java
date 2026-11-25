package com.potraderbot;

import java.util.ArrayList;
import java.util.List;

public final class MarketAnalyzerAudit {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<Candle> sample(double trend) {
        List<Candle> candles = new ArrayList<>();
        long start = 1700000040000L;
        for (int i = 0; i < 120; i++) {
            double p = 100 + i * trend + Math.sin(i * 0.7) * 0.4;
            double open = i == 0 ? p : candles.get(i-1).close;
            candles.add(new Candle(start + i * 60000L, open,
                    Math.max(open,p)+0.05, Math.min(open,p)-0.05,p));
        }
        return candles;
    }

    public static void main(String[] args) {
        MarketAnalyzer analyzer = new MarketAnalyzer();
        int waits = 0, calls = 0, puts = 0;
        for (double trend : new double[]{0.02, -0.02, 0.0}) {
            List<Candle> candles = sample(trend);
            require(analyzer.analyze(candles.subList(0,29)).signal == MarketAnalyzer.Signal.WAIT,
                    "Insufficient history must return WAIT");
            for (int n=30;n<=candles.size();n++) {
                MarketAnalyzer.Analysis a=analyzer.analyze(candles.subList(0,n));
                require(a.strength == (a.signal == MarketAnalyzer.Signal.WAIT ? 0 : 100),
                        "Rule agreement must match the signal");
                switch (a.signal) {
                    case WAIT: waits++; break;
                    case CALL: calls++; break;
                    case PUT: puts++; break;
                }
            }
        }
        require(waits>0 && calls>0 && puts>0, "Test must exercise every signal");
        List<Candle> missing = sample(0.02);
        missing.remove(missing.size()-5);
        require(analyzer.analyze(missing).signal == MarketAnalyzer.Signal.WAIT,
                "Recent data gap must prevent a signal");
        List<Candle> duplicate = sample(0.02);
        duplicate.add(duplicate.get(duplicate.size()-1));
        require(analyzer.analyze(duplicate).signal == MarketAnalyzer.Signal.WAIT,
                "Duplicate timestamp must prevent a signal");
        List<Candle> nullCandle = sample(0.02);
        nullCandle.set(40,null);
        require(analyzer.analyze(nullCandle).signal == MarketAnalyzer.Signal.WAIT,
                "Null candle must prevent a signal");
        require(new MarketAnalyzer.Analysis(MarketAnalyzer.Signal.WAIT,1,1,50,100,"test").strength==0,
                "Externally blocked signals must have zero strength");
        List<Candle> oldGap = sample(0.02);
        oldGap.remove(10);
        MarketAnalyzer.Analysis afterGap = analyzer.analyze(oldGap);
        MarketAnalyzer.Analysis suffix = analyzer.analyze(oldGap.subList(10,oldGap.size()));
        require(afterGap.signal==suffix.signal && afterGap.ema9==suffix.ema9
                && afterGap.ema21==suffix.ema21 && afterGap.rsi14==suffix.rsi14,
                "Indicators must not bridge old data gaps");
        System.out.println("Audit passed: WAIT="+waits+", CALL="+calls+", PUT="+puts);
    }
}
