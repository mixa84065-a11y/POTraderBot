package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
public class BybitReplayHistoryTest {
    private BybitReplayHistory.Candle c(long t,double v){return new BybitReplayHistory.Candle(t,100,101,v);}
    @Test public void trailingTurnoverUsesNoFutureCandles(){
        String csv=BybitReplayHistory.csv(Arrays.asList(c(0,10),c(60000,20),c(120000,30),c(180000,1000)),3,2);
        java.util.List<HistoricalSpotReplay.Bar> bars=ReplayCsv.parse(csv);
        assertEquals(2,bars.size());assertEquals(120000,bars.get(0).time);assertEquals(60,bars.get(0).turnover24h,0);
        assertEquals(1050,bars.get(1).turnover24h,0);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsGap(){BybitReplayHistory.csv(Arrays.asList(c(0,1),c(120000,2)),2,1);}
    @Test(expected=IllegalArgumentException.class) public void rejectsZeroLiquidity(){BybitReplayHistory.csv(Arrays.asList(c(0,0),c(60000,0)),2,1);}
    @Test(expected=IllegalArgumentException.class) public void rejectsIncompleteWarmup(){BybitReplayHistory.csv(Arrays.asList(c(0,1)),3,1);}
}
