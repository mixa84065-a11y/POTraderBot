package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportAutoModelTest{
 @Test public void symmetricAndNormalized(){double[] p=SportAutoModel.outcomes(1.5,1.5);assertEquals(p[0],p[2],1e-12);assertEquals(1,p[0]+p[1]+p[2],1e-12);}
 @Test public void zero(){double[] p=SportAutoModel.outcomes(0,0);assertEquals(1,p[1],1e-12);assertEquals(0,p[0]+p[2],1e-12);}
 @Test public void stronger(){double[] p=SportAutoModel.outcomes(2,1);assertTrue(p[0]>p[2]);}
}
