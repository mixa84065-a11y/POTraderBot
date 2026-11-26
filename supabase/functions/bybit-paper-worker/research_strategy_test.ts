import {marketRegime,bookSnapshot,trailingStop,proposedSizePct,researchProfile,type Bar} from "./research_strategy.ts";
import {replay} from "./research_replay.ts";
function assert(v:unknown){if(!v)throw new Error("Assertion failed");}
const interval=300000;
const make=(prices:number[]):Bar[]=>prices.map((p,i)=>({time:i*interval,open:p,close:p,high:p+0.1,low:p-0.1}));
Deno.test("flat, breakout, incomplete history",()=>{
 const flat=make(Array.from({length:21},(_,i)=>100+i%2*0.1));
 assert(marketRegime(flat,interval,21*interval).regime==="FLAT");
 const trend=make(Array.from({length:21},(_,i)=>100+i*0.2));
 assert(marketRegime(trend,interval,21*interval).regime==="BREAKOUT_UP");
 assert(marketRegime(trend,interval,21*interval-1).regime==="UNAVAILABLE");
});
Deno.test("confirmed bullish engulfing after declining trend",()=>{
 const bars=make(Array.from({length:21},(_,i)=>120-i));
 bars[19]={...bars[19],open:101.5,high:102,low:100.5,close:101};
 bars[20]={...bars[20],open:100.9,low:100.8,high:102,close:101.8};
 assert(marketRegime(bars,interval,21*interval).reversal==="BULLISH_ENGULFING");
});
Deno.test("book freshness and spread validation",()=>{
 const book={bids:[[99.95,2]] as const,asks:[[100.05,1]] as const,timestamp:1000,now:2000};
 const b=bookSnapshot(book);assert(b!==null&&b.imbalance>0&&b.spreadBps>0);
 assert(bookSnapshot({...book,now:7000})===null);assert(bookSnapshot({...book,bids:[[101,2]]})===null);
});
Deno.test("trailing stops only tighten after favorable move",()=>{
 const p={side:"LONG" as const,entry:100,mark:104,oldStop:99,atr:1,feeRate:0.0006};
 assert(trailingStop(p)===102.5);assert(trailingStop({...p,mark:101})===99);assert(trailingStop({...p,oldStop:103})===103);
 assert(trailingStop({...p,side:"SHORT",mark:96,oldStop:101})===97.5);assert(trailingStop({...p,mark:98})===null);
});
Deno.test("4–7% sizing and drawdown caps",()=>{
 const p={netRR:2.6,drawdownPct:0,market:"spot",structureClear:true,liquid:true};
 assert(proposedSizePct(p)===7);assert(proposedSizePct({...p,netRR:2.3})===6);assert(proposedSizePct({...p,netRR:2.1})===5);
 assert(proposedSizePct({...p,drawdownPct:2})===4);assert(proposedSizePct({...p,drawdownPct:5})===0);
 assert(proposedSizePct({...p,market:"inverse"})===0);assert(!researchProfile("option").enabled);
});
Deno.test("matched replay without future observations",()=>{
 const bars=make([100,101,102,103,104,105,106,107]),seen:number[]=[];
 const out=replay({bars,interval,warmup:2,horizon:2,feeRate:0.0006,costBps:2,decide:past=>{seen.push(past.length);return "LONG";}});
 assert(seen.join(",")==="2,4,6");assert(out.strategy.trades===3&&out.baseline.trades===3);assert(out.strategy.equity===out.baseline.equity);
 const flat=replay({bars:make(Array(8).fill(100)),interval,warmup:2,horizon:2,feeRate:0.0006,costBps:2,decide:()=>"LONG"});
 assert(flat.strategy.equity<1&&flat.strategy.wins===0);
});
