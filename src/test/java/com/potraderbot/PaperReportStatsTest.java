package com.potraderbot;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class PaperReportStatsTest {
    private JSONObject trade(String pnl,String fee) throws Exception {
        return new JSONObject().put("net_pnl_usdt",pnl).put("fees_usdt",fee);
    }
    @Test public void newRunIsIndependent() throws Exception {
        PaperReportStats old=new PaperReportStats();
        old.add(trade("-0.194405","0.1"));
        PaperReportStats fresh=new PaperReportStats();
        assertEquals(0,fresh.toJson().getInt("trades_total"));
        assertEquals("0",fresh.toJson().getString("net_pnl_usdt"));
        assertEquals(1,old.toJson().getInt("trades_total"));
    }
    @Test public void exactTotalsBeyondFirstPage() throws Exception {
        PaperReportStats stats=new PaperReportStats();
        for(int i=0;i<1001;i++)stats.add(trade("0.000001","0.000002"));
        stats.add(trade("-0.001001","0"));stats.add(trade("0","0"));
        JSONObject r=stats.toJson();
        assertEquals(1003,r.getInt("trades_total"));
        assertEquals(1001,r.getInt("wins"));
        assertEquals(1,r.getInt("losses"));assertEquals(1,r.getInt("breakeven"));
        assertEquals("0.000000",r.getString("net_pnl_usdt"));
        assertEquals("0.002002",r.getString("fees_usdt"));
        assertEquals("99.80",r.getString("win_rate_pct"));
    }
    @Test(expected=Exception.class) public void missingPnlIsNotZero() throws Exception {
        new PaperReportStats().add(new JSONObject().put("fees_usdt","0"));
    }
}
