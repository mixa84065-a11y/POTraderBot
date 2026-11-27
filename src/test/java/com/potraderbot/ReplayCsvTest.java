package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReplayCsvTest {
    private static final String HEADER="timestamp_ms,open,close,turnover24h\n";
    @Test public void readsChronologicalBars(){
        java.util.List<HistoricalSpotReplay.Bar> bars=ReplayCsv.parse(HEADER+"60000,10,11,1000\n120000,11,12,1100\n");
        assertEquals(2,bars.size());assertEquals(12,bars.get(1).close,0);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsDuplicate(){ReplayCsv.parse(HEADER+"60000,10,11,1000\n60000,11,12,1000");}
    @Test(expected=IllegalArgumentException.class) public void rejectsUnalignedTime(){ReplayCsv.parse(HEADER+"60001,10,11,1000");}
    @Test(expected=IllegalArgumentException.class) public void rejectsMissingValues(){ReplayCsv.parse(HEADER+"60000,10,,1000");}
    @Test(expected=IllegalArgumentException.class) public void rejectsNonFinite(){ReplayCsv.parse(HEADER+"60000,10,NaN,1000");}
}
