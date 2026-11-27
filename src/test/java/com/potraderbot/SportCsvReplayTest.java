package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportCsvReplayTest{
 @Test public void sameDayCannotTrain()throws Exception{
 StringBuilder raw=new StringBuilder("Div,Date,HomeTeam,AwayTeam,FTHG,FTAG\n");
 for(int i=1;i<=8;i++)raw.append("E0,01/01/2025,A,T"+i+",2,0\nE0,01/01/2025,T"+i+",B,0,1\n");
 raw.append("E0,01/01/2025,A,B,9,0\n");
 SportCsvHistory.Replay r=SportCsvHistory.parseBefore(raw.toString(),"E0",1800000000000L).replay();assertEquals(0,r.tested);assertEquals(17,r.skipped);
 raw.append("E0,02/01/2025,A,B,2,1\n");
 r=SportCsvHistory.parseBefore(raw.toString(),"E0",1800000000000L).replay();assertEquals(1,r.tested);assertEquals(17,r.skipped);assertTrue(r.baseBrier>=0&&r.baseBrier<=2);assertTrue(r.venueBrier>=0&&r.venueBrier<=2);
 }
}