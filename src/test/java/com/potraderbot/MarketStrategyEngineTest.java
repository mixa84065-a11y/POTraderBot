package com.potraderbot;

import org.junit.Test;
import static org.junit.Assert.*;

public class MarketStrategyEngineTest {
    private double[] trend(double start,double step) {
        double[] values=new double[80];
        for(int i=0;i<values.length;i++)values[i]=start+i*step;
        return values;
    }

    @Test public void risingSpotTrendCanProduceResearchSignal() {
        MarketStrategyEngine.Result r=MarketStrategyEngine.analyze("spot",trend(100,0.2),
                115.7,115.8,1000000,Double.NaN,Double.NaN,1000,true);
        assertEquals("ВВЕРХ",r.direction);
    }

    @Test public void staleQuotesFailClosed() {
        MarketStrategyEngine.Result r=MarketStrategyEngine.analyze("linear",trend(100,0.2),
                115.7,115.8,1000000,0,0,300000,true);
        assertEquals("НЕТ СДЕЛКИ",r.direction);
        assertEquals("ЗАБЛОКИРОВАНО",r.risk);
    }

    @Test public void wideSpreadBlocksEntry() {
        MarketStrategyEngine.Result r=MarketStrategyEngine.analyze("spot",trend(100,0.2),
                100,102,1000000,Double.NaN,Double.NaN,1000,true);
        assertEquals("НЕТ СДЕЛКИ",r.direction);
    }

    @Test public void closedTradFiSessionBlocksEntry() {
        MarketStrategyEngine.Result r=MarketStrategyEngine.analyze("tradfi",trend(100,0.2),
                115.7,115.8,1000000,0,0,1000,false);
        assertEquals("НЕТ СДЕЛКИ",r.direction);
    }

    @Test public void optionsNeedAdequateExpiryAndDelta() {
        MarketStrategyEngine.OptionResult r=MarketStrategyEngine.analyzeOption(
                10,10.2,10.1,0.1,0.01,0.2,-0.1,0.6,100000,24,5);
        assertEquals("НЕТ СДЕЛКИ",r.stance);
    }

    @Test public void optionCandidateUsesGreeksAndUnderlyingTrend() {
        MarketStrategyEngine.OptionResult r=MarketStrategyEngine.analyzeOption(
                10,10.2,10.1,0.5,0.01,0.2,-0.1,0.6,100000,24,5);
        assertEquals("CALL-КАНДИДАТ",r.stance);
    }

    @Test public void cfdCannotBeScannedAsBybitV5Market() {
        MarketStrategyEngine.Result r=MarketStrategyEngine.analyze("cfd",trend(100,0.2),
                115.7,115.8,1000000,0,0,1000,true);
        assertEquals("НЕТ СДЕЛКИ",r.direction);
    }
}
