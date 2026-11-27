package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class QuoteFreshnessTest {
 @Test public void expiresLocalQuote(){assertTrue(QuoteFreshness.usable(1000,61000));assertFalse(QuoteFreshness.usable(1000,61001));assertFalse(QuoteFreshness.usable(-1,1000));assertFalse(QuoteFreshness.usable(1000,999));}
 @Test public void rejectsOpenFutureAndOldCandles(){assertTrue(QuoteFreshness.closedCandle(60000,120000));assertFalse(QuoteFreshness.closedCandle(60000,119999));assertFalse(QuoteFreshness.closedCandle(60000,59999));assertFalse(QuoteFreshness.closedCandle(60000,240001));}
}
