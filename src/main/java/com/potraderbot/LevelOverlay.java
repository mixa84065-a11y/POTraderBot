package com.potraderbot;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.DashPathEffect;
import java.util.Locale;

final class LevelOverlay {
    static void draw(Canvas c,SupportResistance.Levels levels,float left,float right,float top,float bottom,double min,double max,float unit){
        if(levels==null||max<=min||right<=left||bottom<=top||unit<=0)return;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        float sy=y(levels.support,top,bottom,min,max),ry=y(levels.resistance,top,bottom,min,max);
        if(Float.isFinite(sy))line(c,p,left,right,sy,0xff47d58b,unit);
        if(Float.isFinite(ry))line(c,p,left,right,ry,0xfff06a70,unit);
        float sLabel=sy,rLabel=ry;
        if(Float.isFinite(sy)&&Float.isFinite(ry)&&Math.abs(sy-ry)<16*unit){
            sLabel=Math.min(bottom,sy+9*unit);rLabel=Math.max(top+12*unit,ry-9*unit);
        }
        if(Float.isFinite(sy))tag(c,p,left,right,top,bottom,sLabel,"Подд. "+price(levels.support),0xff47d58b,unit);
        if(Float.isFinite(ry))tag(c,p,left,right,top,bottom,rLabel,"Сопр. "+price(levels.resistance),0xfff06a70,unit);
    }
    private static float y(double v,float top,float bottom,double min,double max){
        if(!Double.isFinite(v)||v<min||v>max)return Float.NaN;
        return (float)(bottom-(v-min)/(max-min)*(bottom-top));
    }
    private static String price(double v){
        return java.math.BigDecimal.valueOf(v).round(new java.math.MathContext(8)).stripTrailingZeros().toPlainString();
    }
    private static void line(Canvas c,Paint p,float left,float right,float y,int color,float u){
        p.setColor(color);p.setStrokeWidth(u);p.setPathEffect(new DashPathEffect(new float[]{5*u,3*u},0));c.drawLine(left,y,right,y,p);p.setPathEffect(null);
    }
    private static void tag(Canvas c,Paint p,float left,float right,float top,float bottom,float y,String label,int color,float u){
        p.setTextSize(9*u);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float width=p.measureText(label)+8*u;
        if(width>right-left)return;
        float baseline=Math.max(top+10*u,Math.min(bottom-2*u,y-3*u));
        p.setColor(0xff101b27);c.drawRoundRect(left+2*u,baseline-10*u,left+2*u+width,baseline+3*u,3*u,3*u,p);
        p.setColor(color);c.drawText(label,left+6*u,baseline,p);
    }
}
