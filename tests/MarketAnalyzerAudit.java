package com.potraderbot;
import java.util.ArrayList;
import java.util.List;

public final class MarketAnalyzerAudit {
    public static void main(String[] args) {
        MarketAnalyzer analyzer = new MarketAnalyzer();
        List<Candle> candles = new ArrayList<>();
        long start = 1700000040000L;
        for (int i = 0; i < 120; i++) {
            double p = 100 + i * 0.02 + Math.sin(i * 0.7) * 0.4;
            double open = i == 0 ? p : candles.get(i-1).close;
            candles.add(new Candle(start + i * 60000L, open, Math.max(open,p)+0.05, Math.min(open,p)-0.05,p));
        }
        if (analyzer.analyze(candles.subList(0,29)).signal != MarketAnalyzer.Signal.WAIT)
            throw new AssertionError("Insufficient history must return WAIT");
        int contradictory = 0;
        for (int n=30;n<=candles.size();n++) {
            MarketAnalyzer.Analysis a=analyzer.analyze(candles.subList(0,n));
            if (a.signal == MarketAnalyzer.Signal.WAIT && a.strength == 100) contradictory++;
        }
        System.out.println("WAIT signals with strength 100: "+contradictory);
        if (contradictory>0) throw new AssertionError("WAIT must not receive maximum directional strength");
        System.out.println("Audit passed");
    }
}
