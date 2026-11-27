package com.potraderbot;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportHistoryTest {
 @Test public void historicalOnlyAndDedup()throws Exception{
 String row="{\"fixture\":{\"id\":1,\"timestamp\":100,\"status\":{\"short\":\"FT\"}},\"teams\":{\"home\":{\"id\":10},\"away\":{\"id\":20}},\"goals\":{\"home\":2,\"away\":1},\"score\":{\"halftime\":{\"home\":1,\"away\":0}}}";
 JSONArray a=new JSONArray().put(new JSONObject(row)).put(new JSONObject(row));
 SportHistory.Summary x=SportHistory.summarize(a,20,200);assertEquals(1,x.n);assertEquals(1,x.l);assertEquals(1,x.gf);assertEquals(2,x.ga);assertEquals(1,x.both);assertEquals(1,x.htGoal);
 assertEquals(0,SportHistory.summarize(a,20,100).n);assertEquals(0,SportHistory.summarize(a,30,200).n);
 }
}
