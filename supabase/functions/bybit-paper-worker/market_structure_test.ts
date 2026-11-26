import {confirmedPivots,structureRoom,type Candle} from "./market_structure.ts";
function assert(v:unknown){if(!v)throw new Error("Assertion failed");}
const interval=300000;
const bars:Candle[]=[100,101,105,102,100,98,99,101,100].map((v,i)=>({time:i*interval,high:v+0.5,low:v-0.5,close:v}));
const base={bars,intervalMs:interval,asOf:bars.length*interval,entry:101,target:106,atr:1,side:"LONG" as const};
Deno.test("confirmed support and resistance use only completed pivots",()=>{
 const p=confirmedPivots(bars,interval,base.asOf);
 assert(p.some(x=>x.kind==="HIGH"&&x.price===105.5));
 assert(p.some(x=>x.kind==="LOW"&&x.price===97.5));
 const r=structureRoom(base);
 assert(r.status==="OBSTACLE_BEFORE_TARGET"&&r.resistance===105.5&&r.support===97.5);
 assert(structureRoom({...base,target:103}).status==="CLEAR_TO_TARGET");
});
Deno.test("short obstacle and historical breakout role reversal",()=>{
 assert(structureRoom({...base,entry:100,target:97,side:"SHORT"}).status==="OBSTACLE_BEFORE_TARGET");
 const r=structureRoom({...base,entry:106,target:108});
 assert(r.status==="NO_OBSERVED_OBSTACLE"&&r.support===105.5);
});
Deno.test("unclosed candle and gaps are unavailable; no future data",()=>{
 assert(confirmedPivots(bars,interval,base.asOf-1).length===0);
 assert(confirmedPivots(bars.filter((_,i)=>i!==3),interval,base.asOf).length===0);
 const truncated=bars.slice(0,4);
 assert(confirmedPivots(truncated,interval,4*interval).length===0);
 const flat=bars.map(x=>({...x,high:101,low:99,close:100}));
 assert(structureRoom({...base,bars:flat}).status==="UNAVAILABLE");
});
