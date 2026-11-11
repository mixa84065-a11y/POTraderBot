package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MarketAnalyzerTest {
    private JSONArray trend(double start,double step) throws Exception {
        JSONArray rows=new JSONArray();
        for(int i=0;i<40;i++) rows.put(new JSONObject().put("close",start+i*step));
        return rows;
    }

    @Test public void risingMarketProducesUpDirection() throws Exception {
        MarketAnalyzer.Result r=MarketAnalyzer.analyze(trend(1.10,0.0001));
        assertEquals("ВВЕРХ",r.direction);
        assertTrue(r.strength>50);
        assertTrue(r.explanation.contains("EMA 9 выше EMA 21"));
    }

    @Test public void fallingMarketProducesDownDirection() throws Exception {
        MarketAnalyzer.Result r=MarketAnalyzer.analyze(trend(1.10,-0.0001));
        assertEquals("ВНИЗ",r.direction);
        assertTrue(r.strength>50);
        assertTrue(r.explanation.contains("EMA 9 ниже EMA 21"));
    }

    @Test public void insufficientCandlesNeverProduceTradeSignal() {
        MarketAnalyzer.Result r=MarketAnalyzer.analyze(new JSONArray());
        assertEquals("НЕТ СИГНАЛА",r.direction);
        assertEquals(0,r.strength);
    }
}
