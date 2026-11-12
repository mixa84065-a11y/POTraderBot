package com.potraderbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public class ChartView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<double[]> candles = new ArrayList<>();
    private boolean fresh = false;
    private boolean expanded = false;
    private boolean showDecision = false;
    private String decision = "НЕТ СИГНАЛА";
    private int decisionStrength = 0;
    private String message = "Ожидание проверенного потока котировок";

    public ChartView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setExpanded(boolean value) { expanded = value; invalidate(); }

    public void setDecision(String direction, int strength) {
        decision = direction == null ? "НЕТ СИГНАЛА" : direction;
        decisionStrength = Math.max(0, Math.min(100, strength));
        showDecision = true;
        invalidate();
    }

    public void setMarketData(JSONArray rows, boolean quoteFresh) {
        candles.clear();
        fresh = quoteFresh;
        showDecision = false;
        if (rows != null) {
            for (int i=0;i<rows.length();i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                double open=o.optDouble("open",Double.NaN), high=o.optDouble("high",Double.NaN);
                double low=o.optDouble("low",Double.NaN), close=o.optDouble("close",Double.NaN);
                if (Double.isFinite(open) && Double.isFinite(high) && Double.isFinite(low) && Double.isFinite(close)
                        && open>0 && high>0 && low>0 && close>0) candles.add(new double[]{open,high,low,close});
            }
        }
        message = candles.isEmpty() ? "Нет свечей от подтверждённого источника" :
                (fresh ? "Котировка актуальна" : "Последняя котировка устарела");
        invalidate();
    }

    private double[] ema(int period) {
        double[] values = new double[candles.size()];
        if (candles.isEmpty()) return values;
        double alpha = 2.0 / (period + 1.0);
        values[0] = candles.get(0)[3];
        for (int i=1;i<candles.size();i++)
            values[i] = alpha * candles.get(i)[3] + (1.0-alpha) * values[i-1];
        return values;
    }

    private void drawEma(Canvas c, double[] values, int color, float left, float slot,
                         float bottom, float plotH, double min, double max) {
        if (values.length < 2) return;
        Path path = new Path();
        for (int i=0;i<values.length;i++) {
            float x=left+slot*(i+0.5f);
            float y=bottom-(float)((values[i]-min)/(max-min))*plotH;
            if (i==0) path.moveTo(x,y); else path.lineTo(x,y);
        }
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.4f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(path,p);
        p.setStyle(Paint.Style.FILL);
        float lastX=left+slot*(values.length-0.5f);
        float lastY=bottom-(float)((values[values.length-1]-min)/(max-min))*plotH;
        c.drawCircle(lastX,lastY,3.5f,p);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(), h=getHeight();
        c.drawColor(0xFF0E1F37);
        p.setColor(0xFF1B3452); p.setStrokeWidth(1f);
        for(int i=1;i<6;i++) c.drawLine(0,h*i/6f,w,h*i/6f,p);
        for(int i=1;i<8;i++) c.drawLine(w*i/8f,0,w*i/8f,h,p);
        p.setColor(0xFFE0B75B); p.setTextSize(14f); p.setFakeBoldText(true);
        c.drawText(expanded ? "ГРАФИК И АНАЛИЗ • M1" : "EUR/USD • M1",16,24,p);
        p.setFakeBoldText(false);
        if(candles.isEmpty()) {
            p.setColor(0xFF9AAFC9); p.setTextSize(12f);
            String t="Свечи появятся после подключения источника";
            c.drawText(t,Math.max(12,(w-p.measureText(t))/2f),h/2f,p);
        } else {
            double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
            for(double[] a:candles){min=Math.min(min,a[2]);max=Math.max(max,a[1]);}
            double pad=(max-min)*0.12;
            if(pad<=0) pad=Math.max(max*0.0001,0.000001);
            min-=pad; max+=pad;
            float left=12, right=w-12, top=42, bottom=h-22;
            float plotH=Math.max(1,bottom-top);
            float slot=(right-left)/candles.size();
            float bodyW=Math.max(2,Math.min(12,slot*0.58f));
            for(int i=0;i<candles.size();i++){
                double[] a=candles.get(i);
                float x=left+slot*(i+0.5f);
                float yHigh=bottom-(float)((a[1]-min)/(max-min))*plotH;
                float yLow=bottom-(float)((a[2]-min)/(max-min))*plotH;
                float yOpen=bottom-(float)((a[0]-min)/(max-min))*plotH;
                float yClose=bottom-(float)((a[3]-min)/(max-min))*plotH;
                boolean up=a[3]>=a[0];
                p.setColor(up?0xFF26B88A:0xFFE45B68); p.setStrokeWidth(1.6f);
                c.drawLine(x,yHigh,x,yLow,p);
                float y=Math.min(yOpen,yClose), bh=Math.max(2,Math.abs(yClose-yOpen));
                p.setStyle(Paint.Style.FILL);
                c.drawRect(new RectF(x-bodyW/2,y,x+bodyW/2,y+bh),p);
            }

            if(showDecision && candles.size()>=21) {
                drawEma(c,ema(9),0xFF36C7E8,left,slot,bottom,plotH,min,max);
                drawEma(c,ema(21),0xFFE0B75B,left,slot,bottom,plotH,min,max);

                p.setTextSize(10f); p.setFakeBoldText(true);
                p.setColor(0xFF36C7E8); c.drawText("EMA 9",16,42,p);
                p.setColor(0xFFE0B75B); c.drawText("EMA 21",66,42,p);
                p.setFakeBoldText(false);

                int signalColor = decision.contains("ВВЕРХ") ? 0xFF26B88A :
                        decision.contains("ВНИЗ") ? 0xFFE45B68 : 0xFF9AAFC9;
                String label = decision + " • " + decisionStrength + "%";
                p.setTextSize(10f); p.setFakeBoldText(true);
                float badgeW=p.measureText(label)+16;
                float badgeLeft=Math.max(120,w-badgeW-12);
                p.setColor(0xFF193653);
                c.drawRoundRect(new RectF(badgeLeft,10,w-10,31),8,8,p);
                p.setColor(signalColor);
                c.drawText(label,badgeLeft+8,24,p);
                p.setFakeBoldText(false);
            }
        }
        p.setColor(fresh?0xFF26B88A:0xFF9AAFC9); p.setTextSize(10f);
        c.drawText(message,16,h-7,p);
    }
}
