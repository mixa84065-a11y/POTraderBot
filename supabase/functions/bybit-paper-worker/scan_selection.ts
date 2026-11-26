/** Rotate a bounded liquid watchlist without increasing candle requests per cycle.
 * One liquidity leader remains anchored; the rest are explored in fixed time buckets.
 */
export type Quote={symbol:string;lastPrice?:string;bid1Price?:string;ask1Price?:string;turnover24h?:string};
export function rotatingWatchlist<T extends Quote>(quotes:readonly T[],input:{budget:number;poolSize:number;now:number}):T[]{
 const {budget,poolSize,now}=input;
 if(!Number.isInteger(budget)||budget<1||!Number.isInteger(poolSize)||poolSize<budget||!Number.isFinite(now)||now<0)return [];
 const eligible=quotes.filter(t=>{
  const last=Number(t.lastPrice),bid=Number(t.bid1Price),ask=Number(t.ask1Price),turnover=Number(t.turnover24h);
  return t.symbol&&[last,bid,ask,turnover].every(Number.isFinite)&&last>0&&bid>0&&ask>=bid&&turnover>0&&(ask-bid)/last<=0.0008;
 }).sort((a,b)=>Number(b.turnover24h)-Number(a.turnover24h)||a.symbol.localeCompare(b.symbol));
 const seen=new Set<string>();
 const pool=eligible.filter(q=>{if(seen.has(q.symbol))return false;seen.add(q.symbol);return true;}).slice(0,poolSize);
 if(pool.length<=budget)return pool;
 if(budget===1)return [pool[Math.floor(now/300000)%pool.length]];
 const remaining=pool.slice(1),slots=budget-1,offset=(Math.floor(now/300000)*slots)%remaining.length;
 return [pool[0],...Array.from({length:slots},(_,i)=>remaining[(offset+i)%remaining.length])];
}
import {assessEntry} from "./entry_quality.ts";
export type Rankable={symbol:string;category:string;side:string;price:number;bid:number;ask:number;score:number;stopPct?:number;targetPct?:number;structureStatus?:string};
export function rankQuality<T extends Rankable>(candidates:readonly T[]):T[]{
 const enriched=candidates.map(c=>{
  const stopPct=c.stopPct??0.008,targetPct=c.targetPct??stopPct*2.2;
  const side=c.side;
  const q=side==="LONG"||side==="SHORT"?assessEntry({side,entry:c.price,bid:c.bid,ask:c.ask,
   stop:c.price*(side==="LONG"?1-stopPct:1+stopPct),target:c.price*(side==="LONG"?1+targetPct:1-targetPct),
   feeRate:0.0006,exitSlippageBps:2,minNetRewardRisk:1.5}):null;
  const ratio=q?.ok?q.netRewardRisk??0:0;
  const room=c.structureStatus==="CLEAR_TO_TARGET"?2:c.structureStatus==="OBSTACLE_BEFORE_TARGET"?0:1;
  const spread=c.price>0?(c.ask-c.bid)/c.price:Infinity;
  return {c,ratio,room,spread};
 });
 // No extra entries or relaxed gates. Rank quality-passing candidates first;
 // structure is a provisional preference, not a rejection or win probability.
 return enriched.sort((a,b)=>Number(b.ratio>0)-Number(a.ratio>0)||b.room-a.room||b.ratio-a.ratio||
   a.spread-b.spread||b.c.score-a.c.score||a.c.category.localeCompare(b.c.category)||a.c.symbol.localeCompare(b.c.symbol)).map(x=>x.c);
}
