package com.potraderbot;
/** Independent Poisson model, not calibrated. */
public final class SportAutoModel {
 private SportAutoModel(){}
 public static double[] outcomes(double home,double away){
   SportStatistics.above(home,0);SportStatistics.above(away,0);
   double[] a=new double[151],b=new double[151];a[0]=Math.exp(-home);b[0]=Math.exp(-away);
   for(int i=1;i<a.length;i++){a[i]=a[i-1]*home/i;b[i]=b[i-1]*away/i;}
   double[] p=new double[3];
   for(int i=0;i<a.length;i++)for(int j=0;j<b.length;j++)p[i>j?0:i==j?1:2]+=a[i]*b[j];
   double total=p[0]+p[1]+p[2];for(int i=0;i<3;i++)p[i]/=total;return p;
 }
}
