package com.potraderbot;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HistoricalSpotReplayTest {
    private List<HistoricalSpotReplay.Bar> bars(int n){
        List<HistoricalSpotReplay.Bar> a=new ArrayList<>();
        for(int i=0;i<n;i++)a.add(new HistoricalSpotReplay.Bar(60000L*i,100+i*0.2,100+i*0.2+(i%2==0?0.2:-0.15),1000000));
        return a;
    }
    @Test public void costsReduceReturnAndFlatMarketLosesAfterCosts(){
        assertEquals(1,HistoricalSpotReplay.netFactor(100,100,0,0,0),1e-12);
        assertTrue(HistoricalSpotReplay.netFactor(100,100,8,6,2)<1);
        assertTrue(HistoricalSpotReplay.netFactor(100,102,8,6,2)<HistoricalSpotReplay.netFactor(100,102,0,0,0));
    }
    @Test public void missingMinuteRejectsReplayWindow(){
        List<HistoricalSpotReplay.Bar> a=bars(61);a.remove(10);
        HistoricalSpotReplay.Report r=HistoricalSpotReplay.replay(a,55,3,8,6,2);
        assertEquals(0,r.eligibleWindows);assertTrue(r.skippedGaps>0);
    }
    @Test public void fixedHorizonDoesNotOverlapAndUsesNextOpen(){
        HistoricalSpotReplay.Report r=HistoricalSpotReplay.replay(bars(64),55,3,0,0,0);
        assertEquals(2,r.eligibleWindows);
        assertEquals(2,r.baseline.trades);
        double expected=(111.6/111.0)*(112.4/111.8);
        assertEquals(expected,r.baseline.equity,1e-12);
        assertTrue(r.strategy.trades<=r.baseline.trades);
    }
    @Test(expected=IllegalArgumentException.class) public void duplicateTimestampRejected(){
        List<HistoricalSpotReplay.Bar> a=bars(60);a.set(4,a.get(3));
        HistoricalSpotReplay.replay(a,55,3,8,6,2);
    }
}
