package com.potraderbot;

final class AnalyticsMath {
    static final class Investment {
        final double cost, value, profit, percent;
        Investment(double cost,double value){this.cost=cost;this.value=value;profit=value-cost;percent=100*(profit/cost);}
    }
    static Investment investment(double quantity,double entry,double current,double fees,double income){
        if(!Double.isFinite(quantity)||!Double.isFinite(entry)||!Double.isFinite(current)||!Double.isFinite(fees)||!Double.isFinite(income)
                ||quantity<=0||entry<=0||current<0||fees<0||income<0)throw new IllegalArgumentException("Invalid values");
        double cost=quantity*entry+fees, value=quantity*current+income;
        if(!Double.isFinite(cost)||!Double.isFinite(value)||cost<=0)throw new IllegalArgumentException("Invalid total");
        Investment i=new Investment(cost,value);
        if(!Double.isFinite(i.profit)||!Double.isFinite(i.percent))throw new IllegalArgumentException("Invalid result");
        return i;
    }
    static final class Series {
        int wins,losses,flat;
        double total,maxDrawdown,ending,average;
    }
    static Series series(double start,double[] changes){
        if(!Double.isFinite(start)||start<=0||changes==null||changes.length==0||changes.length>1000)throw new IllegalArgumentException("Invalid series");
        Series r=new Series();double peak=start,equity=start;
        for(double change:changes){
            if(!Double.isFinite(change)||!Double.isFinite(equity+change)||equity+change<0)throw new IllegalArgumentException("Invalid equity");
            equity+=change;peak=Math.max(peak,equity);r.maxDrawdown=Math.max(r.maxDrawdown,100*((peak-equity)/peak));
            if(change>0)r.wins++;else if(change<0)r.losses++;else r.flat++;
        }
        r.ending=equity;r.total=equity-start;r.average=r.total/changes.length;return r;
    }
}
