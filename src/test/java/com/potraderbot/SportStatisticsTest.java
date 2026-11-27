package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportStatisticsTest {
 @Test public void zeroAndComplement(){assertEquals(0,SportStatistics.above(0,0),1e-12);assertEquals(1-Math.exp(-1.4),SportStatistics.above(1.4,0),1e-12);assertEquals(0,SportStatistics.bothScore(1,0),1e-12);}
 @Test public void monotonic(){assertTrue(SportStatistics.above(9,7)>SportStatistics.above(9,8));assertTrue(SportStatistics.above(10,7)>SportStatistics.above(9,7));}
 @Test public void both(){assertEquals(Math.pow(1-Math.exp(-1),2),SportStatistics.bothScore(1,1),1e-12);}
 @Test(expected=IllegalArgumentException.class) public void invalid(){SportStatistics.above(Double.NaN,0);}
}
