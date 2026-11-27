/** Experimental ranking, not a calibrated win probability. Shadow evaluation only. */
export type QualityInput={side:"LONG"|"SHORT";price:number;atr:number;emaFast:number;emaSlow:number;ema15Fast:number;ema15Slow:number;ema60Fast:number;ema60Slow:number;rsi:number;adx:number;momentumPct:number;volumeRatio:number;distanceATR:number;spreadBps:number;structureStatus?:string;regime:string};
const clamp=(x:number)=>Math.max(0,Math.min(1,x));
export function signalQuality(x:QualityInput){
 const values=[x.price,x.atr,x.emaFast,x.emaSlow,x.ema15Fast,x.ema15Slow,x.ema60Fast,x.ema60Slow,x.rsi,x.adx,x.momentumPct,x.volumeRatio,x.distanceATR,x.spreadBps];
 if(!values.every(Number.isFinite)||Math.min(x.price,x.atr,x.emaFast,x.emaSlow,x.ema15Fast,x.ema15Slow,x.ema60Fast,x.ema60Slow)<=0||x.rsi<0||x.rsi>100||x.adx<0||x.adx>100||x.volumeRatio<0||x.distanceATR<0||x.spreadBps<0)return null;
 const sign=x.side==="LONG"?1:-1;
 const alignment=[sign*(x.emaFast-x.emaSlow)/x.atr,sign*(x.ema15Fast-x.ema15Slow)/x.atr,sign*(x.ema60Fast-x.ema60Slow)/x.atr];
 const trend=alignment.reduce((s,a)=>s+clamp(a/0.8),0)/3;
 const strength=clamp((x.adx-16)/20);
 const directionalRsi=x.side==="LONG"?x.rsi:100-x.rsi;
 const rsiFit=clamp(1-Math.abs(directionalRsi-57)/12);
 const momentumATR=sign*x.momentumPct*x.price/(100*x.atr);
 const momentumFit=clamp(momentumATR/0.8)*clamp((3-momentumATR)/1.5);
 const volume=clamp(x.volumeRatio/1.5);
 const entry=clamp(1-x.distanceATR/1.5);
 const spread=clamp(1-x.spreadBps/8);
 const structure=x.structureStatus==="CLEAR_TO_TARGET"?1:x.structureStatus==="OBSTACLE_BEFORE_TARGET"?0:0.5;
 const regime=x.regime==="FLAT"?0:x.regime===(sign===1?"TREND_UP":"TREND_DOWN")||x.regime===(sign===1?"BREAKOUT_UP":"BREAKOUT_DOWN")?1:0.5;
 const parts={trend,strength,rsiFit,momentumFit,volume,entry,spread,structure,regime};
 const score=Math.round(100*(.20*trend+.10*strength+.10*rsiFit+.10*momentumFit+.10*volume+.15*entry+.05*spread+.10*structure+.10*regime));
 return {score,parts};
}
