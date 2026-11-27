package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportCsvHistoryTest{
 @Test public void pastOnlyAndMissingStats()throws Exception{
  String raw="Div,Date,HomeTeam,AwayTeam,FTHG,FTAG,HTHG,HTAG,HC,AC,HY,AY\nE0,01/01/2025,\"A, FC\",B,2,1,1,0,5,3,,2\nE0,01/01/2025,\"A, FC\",B,2,1,1,0,5,3,,2\nE0,01/01/2099,B,\"A, FC\",1,1,0,0,1,1,1,1\n";
  SportCsvHistory a=SportCsvHistory.parseBefore(raw,"E0",1800000000000L);assertEquals(1,a.size());SportCsvHistory.Team t=a.team("A, FC");assertEquals(1,t.goals.n);assertEquals(2,t.goals.gf);assertEquals(1,t.goals.htFor);assertEquals(1,t.cn);assertEquals(0,t.yn);
 }
 @Test(expected=java.io.IOException.class) public void badHeader()throws Exception{SportCsvHistory.parse("html","E0");}
}
