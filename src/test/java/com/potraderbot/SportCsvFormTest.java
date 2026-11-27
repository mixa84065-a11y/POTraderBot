package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportCsvFormTest{
 @Test public void venueAndSecondHalfUseOnlyKnownScores()throws Exception{
 String raw="Div,Date,HomeTeam,AwayTeam,FTHG,FTAG,HTHG,HTAG\nE1,01/01/2025,A,B,3,1,1,1\nE1,02/01/2025,C,A,0,0,,\nE1,03/01/2025,A,D,0,2,0,1\n";
 SportCsvHistory.Team t=SportCsvHistory.parseBefore(raw,"E1",1800000000000L).team("A");
 assertEquals(3,t.recentN);assertEquals(4,t.recentPoints);assertEquals(2,t.homeN);assertEquals(1,t.awayN);assertEquals(2,t.goals.htN);assertEquals(2,t.secondGF);assertEquals(1,t.secondGA);assertEquals(1,t.cleanSheets);assertEquals(2,t.scoreless);assertEquals("П Н В",t.form.toString());
 }
}