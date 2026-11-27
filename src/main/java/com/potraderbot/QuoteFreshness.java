package com.potraderbot;
/** A conservative local age cap, in addition to freshness checks by the server. */
final class QuoteFreshness {
    static final long MAX_AGE_MS=60000L;
    static boolean usable(long received,long now){return received>=0&&now>=received&&now-received<=MAX_AGE_MS;}
    static boolean closedCandle(long start,long now){return start>0&&now>=start&&now-start>=60000L&&now-start<=180000L;}
}
