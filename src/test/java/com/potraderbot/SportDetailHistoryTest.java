package com.potraderbot;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportDetailHistoryTest{
 @Test public void missingIsNotZero()throws Exception{
  JSONArray a=new JSONArray("[{\"team\":{\"id\":1},\"statistics\":[{\"type\":\"Corner Kicks\",\"value\":6},{\"type\":\"Yellow Cards\",\"value\":null}]},{\"team\":{\"id\":2},\"statistics\":[{\"type\":\"Corner Kicks\",\"value\":0},{\"type\":\"Yellow Cards\",\"value\":2}]}]");
  SportDetailHistory.Summary s=new SportDetailHistory.Summary();s.add(a,1);assertEquals(1,s.cornersN);assertEquals(6,s.cornersFor);assertEquals(0,s.cornersAgainst);assertEquals(0,s.cardsN);
 }
}
