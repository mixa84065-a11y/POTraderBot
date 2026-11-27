package com.potraderbot;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportSquadsTest {
 @Test public void emptyNotHealthy(){assertTrue(SportSquads.injuries(new JSONArray()).contains("неизвестен"));}
 @Test public void lineup()throws Exception{JSONArray rows=new JSONArray("[{\"team\":{\"name\":\"Team\"},\"formation\":\"4-4-2\",\"startXI\":[{\"player\":{\"name\":\"Player\",\"number\":9}}]}]");assertTrue(SportSquads.lineups(rows).contains("9 Player"));}
 @Test public void injury()throws Exception{JSONArray rows=new JSONArray("[{\"team\":{\"name\":\"Team\"},\"player\":{\"name\":\"Player\",\"reason\":\"Injury\"}}]");assertTrue(SportSquads.injuries(rows).contains("Injury"));}
}
