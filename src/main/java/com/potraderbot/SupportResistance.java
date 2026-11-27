package com.potraderbot;

import java.util.List;
import java.util.ArrayList;
import org.json.JSONArray;

/** Nearby historical swing levels; descriptive only, not trade signals. */
final class SupportResistance {
    static final class Levels {
        final double support,resistance;
        Levels(double s,double r){support=s;resistance=r;}
    }
    static Levels calculate(List<double[]> bars,double reference){
        if(bars==null||bars.size()<10||!Double.isFinite(reference)||reference<=0)return new Levels(Double.NaN,Double.NaN);
        int n=bars.size(),first=Math.max(0,n-60);
        for(int i=first;i<n;i++){
            double[] x=bars.get(i);
            if(x==null||x.length<4||!Double.isFinite(x[1])||!Double.isFinite(x[2])||x[2]<=0||x[1]<x[2])return new Levels(Double.NaN,Double.NaN);
        }
        double support=Double.NaN,resistance=Double.NaN;
        // A swing must have two observed neighbours on each side.
        for(int i=first+2;i<n-2;i++){
            double hi=bars.get(i)[1],lo=bars.get(i)[2];
            boolean low=true,high=true,strictLow=false,strictHigh=false;
            for(int j=i-2;j<=i+2;j++)if(j!=i){
                double[] a=bars.get(j);low&=lo<=a[2];high&=hi>=a[1];
                strictLow|=lo<a[2];strictHigh|=hi>a[1];
            }
            if(low&&strictLow&&lo<reference&&(Double.isNaN(support)||lo>support))support=lo;
            if(high&&strictHigh&&hi>reference&&(Double.isNaN(resistance)||hi<resistance))resistance=hi;
        }
        return new Levels(support,resistance);
    }
    static Levels fromBybit(JSONArray rows,double reference){
        ArrayList<double[]> bars=new ArrayList<>();
        try{for(int i=0;i<rows.length();i++){
            JSONArray a=rows.getJSONArray(i);
            bars.add(new double[]{a.getDouble(1),a.getDouble(2),a.getDouble(3),a.getDouble(4)});
        }}catch(Exception e){return new Levels(Double.NaN,Double.NaN);}
        return calculate(bars,reference);
    }
}
