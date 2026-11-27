package com.potraderbot;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class PaperMarketGuardTest {
 @Test public void rejectsWrongSymbolOrPrice(){assertFalse(PaperMarketGuard.samePosition("ETHUSDT","BTCUSDT",3000));assertFalse(PaperMarketGuard.samePosition("BTCUSDT","BTCUSDT",Double.NaN));assertTrue(PaperMarketGuard.samePosition("BTCUSDT","BTCUSDT",60000));}
 private JSONObject history(String rows)throws Exception{return new JSONObject("{\"retCode\":0,\"time\":180001,\"result\":{\"category\":\"spot\",\"symbol\":\"BTCUSDT\",\"list\":"+rows+"}}");}
 @Test public void keepsOnlyClosedBarsInOrder()throws Exception{
   JSONArray a=PaperMarketGuard.closedBars(history("[[\"180000\",\"10\",\"12\",\"9\",\"11\"],[\"120000\",\"10\",\"12\",\"9\",\"11\"],[\"60000\",\"10\",\"12\",\"9\",\"11\"]]"),"BTCUSDT");
   assertEquals(2,a.length());assertEquals(60000,a.getJSONArray(0).getLong(0));
 }
 @Test(expected=IllegalArgumentException.class) public void rejectsWrongHistorySymbol()throws Exception{PaperMarketGuard.closedBars(history("[[\"60000\",\"10\",\"12\",\"9\",\"11\"]]"),"ETHUSDT");}
 @Test(expected=IllegalArgumentException.class) public void rejectsDuplicates()throws Exception{PaperMarketGuard.closedBars(history("[[\"60000\",\"10\",\"12\",\"9\",\"11\"],[\"60000\",\"10\",\"12\",\"9\",\"11\"]]"),"BTCUSDT");}
}
