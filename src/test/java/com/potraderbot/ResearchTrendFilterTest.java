package com.potraderbot;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResearchTrendFilterTest {
    private List<HistoricalSpotReplay.Bar> sample(int count,double slope){
        List<HistoricalSpotReplay.Bar> bars=new ArrayList<>();
        for(int i=0;i<count;i++){
            double p=100+slope*i;
            bars.add(new HistoricalSpotReplay.Bar(i*60000L,p,p,1000000));
        }
        return bars;
    }
    @Test public void trendAndFlatAreDistinguished(){
        assertEquals(ResearchTrendFilter.Decision.CONFIRMED,ResearchTrendFilter.evaluate(sample(240,0.1),0,239));
        assertEquals(ResearchTrendFilter.Decision.CONFLICT,ResearchTrendFilter.evaluate(sample(240,-0.1),0,239));
        assertEquals(ResearchTrendFilter.Decision.FLAT,ResearchTrendFilter.evaluate(sample(240,0),0,239));
        assertEquals(ResearchTrendFilter.Decision.INSUFFICIENT_HISTORY,ResearchTrendFilter.evaluate(sample(239,0.1),0,238));
    }
    @Test public void incompleteBucketsAndFutureObservationsExcluded(){
        List<HistoricalSpotReplay.Bar> bars=sample(260,0.1);
        double[] closes=ResearchTrendFilter.completedCloses(bars,1,242,15);
        assertEquals(15,closes.length);
        assertEquals(bars.get(29).close,closes[0],0);
        assertEquals(bars.get(239).close,closes[closes.length-1],0);
        ResearchTrendFilter.Decision before=ResearchTrendFilter.evaluate(bars,1,240);
        bars.set(241,new HistoricalSpotReplay.Bar(241*60000L,1,1,1000000));
        assertEquals(before,ResearchTrendFilter.evaluate(bars,1,240));
    }
    @Test public void missingMinuteRejectsHistory(){
        List<HistoricalSpotReplay.Bar> bars=sample(241,0.1);
        bars.remove(120);
        assertEquals(ResearchTrendFilter.Decision.INVALID_HISTORY,ResearchTrendFilter.evaluate(bars,0,239));
    }
    @Test public void comparisonUsesSameEligibleWindows(){
        HistoricalSpotReplay.Report report=HistoricalSpotReplay.replay(sample(300,0.1),240,5,8,6,2);
        assertTrue(report.comparisonWindows>0);
        assertEquals(report.eligibleWindows,report.comparisonWindows+report.filterUnavailable);
        assertEquals(report.matchedOriginal.trades,report.filtered.trades+report.filterFlat+report.filterConflict);
        assertTrue(report.filtered.trades<=report.matchedOriginal.trades);
    }
}
