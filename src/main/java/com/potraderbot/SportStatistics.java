package com.potraderbot;

/** Educational independent Poisson count model; inputs are supplied estimates, not fitted forecasts. */
public final class SportStatistics {
    private SportStatistics(){}
    private static void validate(double mean){
        if(Double.isNaN(mean)||Double.isInfinite(mean)||mean<0||mean>50)throw new IllegalArgumentException("Допустимое среднее: 0–50");
    }
    public static double above(double mean,int threshold){
        validate(mean);
        if(threshold<0||threshold>100)throw new IllegalArgumentException("threshold");
        double term=Math.exp(-mean),sum=term;
        for(int k=1;k<=threshold;k++){term*=mean/k;sum+=term;}
        return Math.max(0,Math.min(1,1-sum));
    }
    public static double bothScore(double home,double away){
        validate(home);validate(away);
        return -Math.expm1(-home)*-Math.expm1(-away);
    }
}
