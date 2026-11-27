package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class AnalyticsMathTest {
    @Test public void investmentAccountsForFeesAndIncome(){
        AnalyticsMath.Investment i=AnalyticsMath.investment(2,100,120,5,10);
        assertEquals(205,i.cost,1e-9);assertEquals(250,i.value,1e-9);assertEquals(45,i.profit,1e-9);
    }
    @Test public void seriesUsesPeakToTroughDrawdown(){
        AnalyticsMath.Series s=AnalyticsMath.series(1000,new double[]{100,-220,20,0});
        assertEquals(2,s.wins);assertEquals(1,s.losses);assertEquals(1,s.flat);
        assertEquals(20,s.maxDrawdown,1e-9);assertEquals(900,s.ending,1e-9);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsNegativeEquity(){AnalyticsMath.series(100,new double[]{-101});}
    @Test(expected=IllegalArgumentException.class) public void rejectsNonFinite(){AnalyticsMath.investment(1,Double.NaN,1,0,0);}
}
