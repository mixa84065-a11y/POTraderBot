package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
public class MarketVolatilityTest {
 private ArrayList<double[]> stable(int n){ArrayList<double[]> b=new ArrayList<>();for(int i=0;i<n;i++)b.add(new double[]{100,101,99,100});return b;}
 @Test public void constantRange(){MarketVolatility.Reading r=MarketVolatility.calculate(stable(30));assertEquals(2,r.atr,1e-9);assertEquals(2,r.percent,1e-9);}
 @Test public void gapIsCounted(){ArrayList<double[]> b=stable(15);b.add(new double[]{110,111,109,110});assertEquals((2*13+11)/14.0,MarketVolatility.calculate(b).atr,1e-9);}
 @Test public void rejectsInvalidAndShortHistory(){assertNull(MarketVolatility.calculate(stable(14)));ArrayList<double[]> b=stable(20);b.set(4,new double[]{100,90,110,100});assertNull(MarketVolatility.calculate(b));}
 @Test public void ordinaryExcludesNewest()throws Exception{JSONArray a=new JSONArray();for(int i=0;i<15;i++)a.put(new JSONObject().put("open",100).put("high",101).put("low",99).put("close",100));a.put(new JSONObject().put("open",100).put("high",1000).put("low",1).put("close",300));assertEquals(2,MarketVolatility.ordinary(a).atr,1e-9);}
}
