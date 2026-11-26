/** Experimental paper-only helpers; no network requests or orders. */
export type Side="LONG"|"SHORT";
export type Bar={time:number;open:number;high:number;low:number;close:number};
export function validBars(bars:readonly Bar[],interval:number,asOf:number):boolean{
 return Number.isFinite(interval)&&interval>0&&Number.isFinite(asOf)&&bars.length>0&&bars.every((b,i)=>
 [b.time,b.open,b.high,b.low,b.close].every(Number.isFinite)&&b.low>0&&b.high>=Math.max(b.open,b.close)&&
 b.low<=Math.min(b.open,b.close)&&b.time+interval<=asOf&&(i===0||b.time-bars[i-1].time===interval));
}
export function marketRegime(bars:readonly Bar[],interval:number,asOf:number){
 if(bars.length<21||!validBars(bars,interval,asOf))return {regime:"UNAVAILABLE",reversal:"UNAVAILABLE"};
 const prior=bars.slice(-21,-1),last=bars.at(-1)!,prev=prior.at(-1)!;
 const high=Math.max(...prior.map(b=>b.high)),low=Math.min(...prior.map(b=>b.low));
 const path=prior.slice(1).reduce((s,b,i)=>s+Math.abs(b.close-prior[i].close),0);
 const change=prev.close-prior[0].close,efficiency=path>0?Math.abs(change)/path:0;
 const tr=prior.slice(1).map((b,i)=>Math.max(b.high-b.low,Math.abs(b.high-prior[i].close),Math.abs(b.low-prior[i].close)));
 const atr=tr.reduce((a,b)=>a+b,0)/tr.length;
 const regime=atr<=0?"FLAT":last.close>high+atr*0.1?"BREAKOUT_UP":last.close<low-atr*0.1?"BREAKOUT_DOWN":efficiency<0.25?"FLAT":change>0?"TREND_UP":"TREND_DOWN";
 const bullish=prev.close<prev.open&&last.close>last.open&&last.open<=prev.close&&last.close>=prev.open;
 const bearish=prev.close>prev.open&&last.close<last.open&&last.open>=prev.close&&last.close<=prev.open;
 const reversal=efficiency>=0.25&&change<0&&bullish?"BULLISH_ENGULFING":efficiency>=0.25&&change>0&&bearish?"BEARISH_ENGULFING":"NONE";
 return {regime,reversal,efficiency,priorHigh:high,priorLow:low,atr};
}
/** Snapshot only. Quote notional depth assumes linear/base-asset quantity units. */
export function bookSnapshot(input:{bids:readonly (readonly [number,number])[];asks:readonly (readonly [number,number])[];timestamp:number;now:number}){
 const {bids,asks,timestamp,now}=input;
 if(!Number.isFinite(timestamp)||!Number.isFinite(now)||timestamp>now||now-timestamp>5000||!bids.length||!asks.length||
 [...bids,...asks].some(([p,q])=>!Number.isFinite(p)||!Number.isFinite(q)||p<=0||q<=0)||
 bids.some((v,i)=>i>0&&v[0]>=bids[i-1][0])||asks.some((v,i)=>i>0&&v[0]<=asks[i-1][0])||bids[0][0]>=asks[0][0])return null;
 const mid=(bids[0][0]+asks[0][0])/2;
 const depth=(levels:readonly (readonly [number,number])[])=>levels.slice(0,10).filter(([p])=>Math.abs(p-mid)/mid<=0.002).reduce((s,[p,q])=>s+p*q,0);
 const bidDepth=depth(bids),askDepth=depth(asks);
 if(!Number.isFinite(bidDepth+askDepth)||bidDepth<=0||askDepth<=0)return null;
 return {spreadBps:10000*(asks[0][0]-bids[0][0])/mid,imbalance:(bidDepth-askDepth)/(bidDepth+askDepth),bidDepth,askDepth};
}
export function trailingStop(input:{side:Side;entry:number;mark:number;oldStop:number;atr:number;feeRate:number}):number|null{
 const {side,entry,mark,oldStop,atr,feeRate}=input;
 if(![entry,mark,oldStop,atr,feeRate].every(Number.isFinite)||Math.min(entry,mark,oldStop,atr)<=0||feeRate<0||feeRate>=0.01||(side!=="LONG"&&side!=="SHORT"))return null;
 const long=side==="LONG";
 if(long?mark<=oldStop:mark>=oldStop)return null;
 const breakeven=long?entry*(1+feeRate)/(1-feeRate):entry*(1-feeRate)/(1+feeRate);
 if(long?mark-entry<2*atr:entry-mark<2*atr)return oldStop;
 const next=long?Math.max(oldStop,breakeven,mark-1.5*atr):Math.min(oldStop,breakeven,mark+1.5*atr);
 return next>0&&(long?next<mark:next>mark)?next:oldStop;
}
export function researchProfile(category:string){
 switch(category){
 case "spot":return {enabled:true,minRR:1.7,maxSpreadBps:8,maxStopPct:0.012};
 case "linear":return {enabled:true,minRR:1.8,maxSpreadBps:8,maxStopPct:0.012};
 case "stock":case "ETF":return {enabled:true,minRR:1.8,maxSpreadBps:8,maxStopPct:0.010};
 case "forex":return {enabled:true,minRR:1.7,maxSpreadBps:5,maxStopPct:0.008};
 case "commodity":return {enabled:true,minRR:1.9,maxSpreadBps:8,maxStopPct:0.012};
 default:return {enabled:false,minRR:Infinity,maxSpreadBps:0,maxStopPct:0};
 }
}
/** Uncalibrated research sizing. Do not activate without out-of-sample evaluation. */
export function proposedSizePct(input:{netRR:number;drawdownPct:number;market:string;structureClear:boolean;liquid:boolean}):number{
 const {netRR,drawdownPct,market,structureClear,liquid}=input,profile=researchProfile(market);
 if(!profile.enabled||!Number.isFinite(netRR)||!Number.isFinite(drawdownPct)||drawdownPct<0||drawdownPct>=5||netRR<profile.minRR)return 0;
 if(drawdownPct>=2||!structureClear||!liquid)return 4;
 return netRR>=2.5?7:netRR>=2.2?6:netRR>=2?5:4;
}
