package com.potraderbot;
import android.content.Context;
import android.graphics.*;
import android.widget.ProgressBar;

/** Displays model score only; not a calibrated probability or loss estimate. */
final class ModelStrengthBar extends ProgressBar {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ModelStrengthBar(Context context){super(context,null,android.R.attr.progressBarStyleHorizontal);setContentDescription("Оценка модели, шкала от 0 до 100");}
    @Override protected synchronized void onDraw(Canvas c){
        float w=getWidth(),h=getHeight(),r=Math.min(h/2,7*getResources().getDisplayMetrics().density);
        if(w<=2*r||r<=0)return;
        float y=h/2,left=r,right=w-r;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left,0,right,0,new int[]{0xffe23136,0xffffa32e,0xfff7df4d,0xff25d05d},null,Shader.TileMode.CLAMP));
        c.drawRoundRect(left,y-r*.48f,right,y+r*.48f,r,r,paint);
        paint.setShader(null);
        if(getProgress()>0){
            float x=left+(right-left)*(getProgress()/(float)Math.max(1,getMax()));
            paint.setColor(0xfff3f7f9);c.drawCircle(x,y,r*.8f,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.4f);paint.setColor(0xff394954);c.drawCircle(x,y,r*.8f,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    @Override public synchronized void setProgress(int progress){super.setProgress(progress);invalidate();}
}
