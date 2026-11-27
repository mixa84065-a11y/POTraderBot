package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
public class SupportResistanceTest {
 @Test public void findsNearestConfirmedSwings(){
    ArrayList<double[]> b=new ArrayList<>();
    double[] close={100,102,108,104,100,94,98,102,105,101,97,100,103};
    for(double v:close)b.add(new double[]{v,v+1,v-1,v});
    SupportResistance.Levels x=SupportResistance.calculate(b,101);
    assertEquals(96,x.support,0);assertEquals(106,x.resistance,0);
 }
 @Test public void avoidsInventingLevels(){
    ArrayList<double[]> b=new ArrayList<>();for(int i=0;i<20;i++)b.add(new double[]{100+i,101+i,99+i,100+i});
    SupportResistance.Levels x=SupportResistance.calculate(b,120);
    assertTrue(Double.isNaN(x.support));assertTrue(Double.isNaN(x.resistance));
 }
 @Test public void refusesInsufficientData(){assertTrue(Double.isNaN(SupportResistance.calculate(new ArrayList<>(),100).support));}
 @Test public void describesDistanceWithoutFakeLevels(){
    String s=SupportResistance.describe(new SupportResistance.Levels(99,102),100);
    assertTrue(s.contains("1,00%"));assertTrue(s.contains("2,00%"));
    String unknown=SupportResistance.describe(new SupportResistance.Levels(Double.NaN,98),100);
    assertTrue(unknown.contains("Поддержка: не найден"));assertTrue(unknown.contains("Сопротивление: не найден"));
 }
 @Test public void refusesUnknownReference(){
    assertEquals("Уровни: нет подтверждённых данных.",SupportResistance.describe(new SupportResistance.Levels(99,102),0));
 }
}
