/** Research-only structure from completed historical candles.
 * Pivot confirmation needs two completed candles on each side.
 * No level is projected beyond the supplied observation time.
 */
export type Candle={time:number;high:number;low:number;close:number};
type Pivot={price:number;time:number;kind:"HIGH"|"LOW"};
export function confirmedPivots(bars:ReadonlyArray<Candle>,intervalMs:number,asOf:number):Pivot[]{
 if(!Number.isFinite(asOf)||!Number.isFinite(intervalMs)||intervalMs<=0||bars.length<5)return [];
 for(let i=0;i<bars.length;i++){
  const b=bars[i];
  if(![b.time,b.high,b.low,b.close].every(Number.isFinite)||b.low<=0||b.high<b.low||b.close<b.low||b.close>b.high||
    b.time+intervalMs>asOf||(i>0&&b.time-bars[i-1].time!==intervalMs))return [];
 }
 const result:Pivot[]=[];
 for(let i=2;i<bars.length-2;i++){
  const b=bars[i],neighbors=[bars[i-2],bars[i-1],bars[i+1],bars[i+2]];
  if(neighbors.every(x=>b.high>x.high))result.push({price:b.high,time:b.time,kind:"HIGH"});
  if(neighbors.every(x=>b.low<x.low))result.push({price:b.low,time:b.time,kind:"LOW"});
 }
 return result;
}
export function structureRoom(input:{bars:ReadonlyArray<Candle>;intervalMs:number;asOf:number;entry:number;target:number;atr:number;side:"LONG"|"SHORT"}){
 const {bars,intervalMs,asOf,entry,target,atr,side}=input;
 if(![entry,target,atr].every(x=>Number.isFinite(x)&&x>0)||
   (side!=="LONG"&&side!=="SHORT")||(side==="LONG"?target<=entry:target>=entry))return {status:"UNAVAILABLE" as const};
 const pivots=confirmedPivots(bars,intervalMs,asOf);
 if(pivots.length===0)return {status:"UNAVAILABLE" as const};
 const above=pivots.filter(p=>p.price>entry).sort((a,b)=>a.price-b.price);
 const below=pivots.filter(p=>p.price<entry).sort((a,b)=>b.price-a.price);
 // Historical highs or lows can become either resistance or support after a break.
 const resistance=above[0]?.price??null,support=below[0]?.price??null;
 const obstacle=side==="LONG"?resistance:support;
 if(obstacle===null)return {status:"NO_OBSERVED_OBSTACLE" as const,support,resistance};
 const buffer=atr*0.2;
 const room=Math.abs(obstacle-entry);
 const needed=Math.abs(target-entry)+buffer;
 return {status:room<needed?"OBSTACLE_BEFORE_TARGET" as const:"CLEAR_TO_TARGET" as const,
  support,resistance,roomAtr:room/atr,obstacle,buffer};
}
