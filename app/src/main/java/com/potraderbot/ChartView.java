package com.potraderbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.List;

/** Native OHLC chart using the same timestamped candles as the signal analyzer. */
public final class ChartView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Candle> candles;
    private MarketAnalyzer.Signal signal = MarketAnalyzer.Signal.WAIT;
    private double entry;
    private boolean stale;

    public ChartView(Context context) { super(context); }

    public void setMarketData(List<Candle> data, MarketAnalyzer.Signal direction, double price, boolean isStale) {
        candles = data; signal = direction; entry = price; stale = isStale; invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(), h=getHeight(), left=10, right=w-10, top=22, bottom=h-25;
        c.drawColor(0xFF101720);
        p.setTextSize(11f*getResources().getDisplayMetrics().density);
        p.setColor(0xFFB9C5D5);
        if(candles==null || candles.size()<2){ c.drawText("Ожидание рыночных свечей",14,h/2,p); return; }
        int n=Math.min(60,candles.size()), start=candles.size()-n;
        double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
        for(int i=start;i<candles.size();i++){ Candle x=candles.get(i); min=Math.min(min,x.low); max=Math.max(max,x.high); }
        double extra=Math.max((max-min)*0.12,Math.abs(max)*0.00001); min-=extra; max+=extra;
        float ph=bottom-top, slot=(right-left)/n, body=Math.max(2,slot*.58f);
        Path e9path=new Path(),e21path=new Path();
        double e9=candles.get(0).close,e21=e9,k9=2.0/10,k21=2.0/22;
        for(int i=0;i<candles.size();i++){
            Candle x=candles.get(i); e9=x.close*k9+e9*(1-k9); e21=x.close*k21+e21*(1-k21);
            if(i<start) continue;
            int j=i-start; float xx=left+slot*(j+.5f);
            float yo=map(x.open,min,max,top,bottom), yc=map(x.close,min,max,top,bottom);
            p.setColor(x.close>=x.open?0xFF27C985:0xFFFF6262); p.setStrokeWidth(1.5f);
            c.drawLine(xx,map(x.high,min,max,top,bottom),xx,map(x.low,min,max,top,bottom),p);
            c.drawRect(xx-body/2,Math.min(yo,yc),xx+body/2,Math.max(Math.min(yo,yc)+2,Math.max(yo,yc)),p);
            float a=map(e9,min,max,top,bottom), b=map(e21,min,max,top,bottom);
            if(j==0){e9path.moveTo(xx,a);e21path.moveTo(xx,b);} else {e9path.lineTo(xx,a);e21path.lineTo(xx,b);}
        }
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.7f);
        p.setColor(0xFFFFC857); c.drawPath(e9path,p);
        p.setColor(0xFF66B5FF); c.drawPath(e21path,p);
        float ey=map(entry,min,max,top,bottom);
        p.setColor(signal==MarketAnalyzer.Signal.CALL?0xFF27C985:signal==MarketAnalyzer.Signal.PUT?0xFFFF6262:0xFFFFC857);
        p.setPathEffect(new android.graphics.DashPathEffect(new float[]{7,5},0)); c.drawLine(left,ey,right,ey,p); p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL); p.setTextSize(10f*getResources().getDisplayMetrics().density);
        p.setColor(0xFFFFC857); c.drawText("EMA 9",left,14,p);
        p.setColor(0xFF66B5FF); c.drawText("EMA 21",left+55,14,p);
        p.setColor(0xFFFFFFFF);
        String d=signal==MarketAnalyzer.Signal.CALL?"ВХОД ВВЕРХ":signal==MarketAnalyzer.Signal.PUT?"ВХОД ВНИЗ":"ЖДАТЬ";
        c.drawText(d+(stale?" • УСТАРЕЛО":""),Math.max(left+115,right-130),14,p);
        c.drawText(String.format(java.util.Locale.US,"Вход: %.5f",entry),left,h-7,p);
    }
    private float map(double v,double min,double max,float top,float bottom){return bottom-(float)((v-min)/(max-min))*(bottom-top);}
}