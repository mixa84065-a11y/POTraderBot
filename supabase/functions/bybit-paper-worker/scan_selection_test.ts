import {rotatingWatchlist,rankQuality} from "./scan_selection.ts";
function assert(v:unknown){if(!v)throw new Error("Assertion failed");}
const quotes=Array.from({length:15},(_,i)=>({symbol:"A"+i,lastPrice:"100",bid1Price:"99.97",ask1Price:"100.03",turnover24h:String(1000-i)}));
Deno.test("bounded rotation keeps liquidity leader and covers all eligible assets",()=>{
 const seen=new Set<string>();
 for(let i=0;i<7;i++){
  const batch=rotatingWatchlist(quotes,{budget:3,poolSize:15,now:i*300000});
  assert(batch.length===3&&batch[0].symbol==="A0"&&new Set(batch.map(t=>t.symbol)).size===3);
  for(const t of batch)seen.add(t.symbol);
 }
 assert(seen.size===15);
});
Deno.test("invalid quotes, duplicates and wide spreads excluded",()=>{
 const batch=rotatingWatchlist([quotes[0],quotes[0],quotes[1],{...quotes[2],ask1Price:"110"},{...quotes[3],turnover24h:"0"}],{budget:3,poolSize:15,now:0});
 assert(batch.length===2);assert(batch[0].symbol==="A0"&&batch[1].symbol==="A1");
 assert(rotatingWatchlist(quotes,{budget:0,poolSize:15,now:0}).length===0);
});
Deno.test("ranking favors clear cost-qualified setup over obstacles and failed economics",()=>{
 const a={symbol:"A",category:"spot",side:"LONG",price:100,bid:99.97,ask:100,score:10,stopPct:0.01,targetPct:0.022};
 const out=rankQuality([{...a,symbol:"blocked",structureStatus:"OBSTACLE_BEFORE_TARGET"},{...a,symbol:"failed",targetPct:0.0002,structureStatus:"CLEAR_TO_TARGET"},{...a,symbol:"clear",structureStatus:"CLEAR_TO_TARGET"}]);
 assert(out.map(x=>x.symbol).join(",")==="clear,blocked,failed");
});
