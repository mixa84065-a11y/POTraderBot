package com.potraderbot;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportForecastJournalTest{
 private JSONObject forecast()throws Exception{return new JSONObject().put("created",100).put("cutoff",200).put("home",10).put("away",20).put("title","Teams").put("p1",0.6).put("px",0.2).put("p2",0.2).put("btts",0.4).put("over2",0.3);}
 private JSONObject row(String status,long time)throws Exception{return new JSONObject().put("fixture",new JSONObject().put("id",1).put("timestamp",time).put("status",new JSONObject().put("short",status))).put("teams",new JSONObject().put("home",new JSONObject().put("id",10)).put("away",new JSONObject().put("id",20))).put("goals",new JSONObject().put("home",2).put("away",0));}
 @Test public void cancelledNotScored()throws Exception{JSONObject p=forecast();assertTrue(SportForecastJournal.settle(p,1,row("CANC",200)));assertTrue(SportForecastJournal.closed(p));assertFalse(p.has("result"));assertTrue(SportForecastJournal.report(new JSONObject().put("1",p)).contains("Исключено: 1"));}
 @Test public void rescheduledNotScored()throws Exception{JSONObject p=forecast();assertTrue(SportForecastJournal.settle(p,1,row("FT",300)));assertFalse(p.has("result"));}
 @Test public void correctAndImmutable()throws Exception{JSONObject p=forecast();assertFalse(SportForecastJournal.settle(p,2,row("FT",200)));assertTrue(SportForecastJournal.settle(p,1,row("FT",200)));assertFalse(SportForecastJournal.settle(p,1,row("FT",200)));String report=SportForecastJournal.report(new JSONObject().put("1",p));assertTrue(report.contains("1/1"));assertTrue(report.contains("ОЗ 0.160"));}
 @Test public void unfinishedWaits()throws Exception{JSONObject p=forecast();assertFalse(SportForecastJournal.settle(p,1,row("NS",200)));assertFalse(SportForecastJournal.closed(p));}
}
