package com.potraderbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Chart surface intentionally renders no fabricated candles. A verified live OHLC feed is required. */
public class ChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int background = 0xFF0E1F37;
    private boolean expanded = false;

    public ChartView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setExpanded(boolean value) {
        expanded = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        canvas.drawColor(background);
        paint.setColor(0xFF1B3452);
        paint.setStrokeWidth(1f);
        for (int i=1;i<6;i++) {
            float y = h * i / 6f;
            canvas.drawLine(0,y,w,y,paint);
        }
        for (int i=1;i<8;i++) {
            float x = w * i / 8f;
            canvas.drawLine(x,0,x,h,paint);
        }
        paint.setColor(0xFFE0B75B);
        paint.setTextSize(14f);
        paint.setFakeBoldText(true);
        String title = expanded ? "ГРАФИК + АНАЛИЗ" : "РЕАЛЬНЫЕ РЫНОЧНЫЕ СВЕЧИ";
        canvas.drawText(title, 18, 30, paint);
        paint.setFakeBoldText(false);
        paint.setColor(0xFF9AAFC9);
        paint.setTextSize(12f);
        String waiting = "Ожидается проверенный источник OHLC";
        float tw = paint.measureText(waiting);
        canvas.drawText(waiting, Math.max(16,(w-tw)/2f), h/2f, paint);
        paint.setColor(0xFF6E849F);
        paint.setTextSize(10f);
        canvas.drawText("Свечи не генерируются искусственно", 18, h-16, paint);
    }
}