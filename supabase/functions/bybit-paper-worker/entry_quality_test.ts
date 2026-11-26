import {assessEntry,ScanDiagnostics,rejectionKind,failedSignalChecks} from "./entry_quality.ts";
const base={side:"LONG" as const,entry:100,bid:99.95,ask:100,stop:99,target:102.5,feeRate:0.0006,exitSlippageBps:2,minNetRewardRisk:1.5};
function assert(v:unknown){if(!v)throw new Error("Assertion failed");}
Deno.test("net long target and stop include both fees without double spread",()=>{
 const a=assessEntry(base);
 assert(a.ok);assert(a.netRewardPerUnit!<2.5);assert(a.netRiskPerUnit!>1);assert(a.immediateNetPerUnit!<0);
 assert(Math.abs(a.breakevenExit!-100*(1.0006/0.9994))<1e-10);
});
Deno.test("tiny profitable price move is unprofitable after costs",()=>{
 const a=assessEntry({...base,target:100.05});
 assert(!a.ok&&a.reason==="TARGET_BELOW_COSTS");
});
Deno.test("low net reward risk and crossed stop are rejected",()=>{
 assert(assessEntry({...base,target:101}).reason==="NET_REWARD_RISK_TOO_LOW");
 assert(assessEntry({...base,stop:99.99}).reason==="INVALID_EXECUTION_GEOMETRY");
 assert(!assessEntry({...base,feeRate:NaN}).ok);
});
Deno.test("short exit is stressed upward and uses actual executable side",()=>{
 const a=assessEntry({...base,side:"SHORT",bid:100,ask:100.05,stop:101,target:97.5});
 assert(a.ok&&a.netRewardPerUnit!<2.5&&a.netRiskPerUnit!>1&&a.breakevenExit!<100);
});
Deno.test("diagnostic categories are independent",()=>{
 const d=new ScanDiagnostics();d.add("spot","NEUTRAL",2);d.add("spot","NEUTRAL");d.add("linear","NEUTRAL");
 assert(d.counts.spot.NEUTRAL===3&&d.counts.linear.NEUTRAL===1);
 assert(rejectionKind(new Error("5m история устарела"))==="STALE_HISTORY");
 assert(rejectionKind(new Error("Цена/спред не проходят фильтр"))==="INVALID_QUOTE_OR_SPREAD");
 assert(rejectionKind(new Error("network"))==="DATA_OR_API_ERROR");
});

Deno.test("neutral signal diagnostics report all failed checks independently",()=>{
 const reasons=failedSignalChecks({trend_agreement:false,rsi:true,momentum:false,relative_volume:false});
 assert(reasons.join(",")==="TREND_AGREEMENT,MOMENTUM,RELATIVE_VOLUME");
 assert(failedSignalChecks({trend_agreement:true,rsi:true}).length===0);
 const d=new ScanDiagnostics();
 d.add("spot","NEUTRAL");
 for(const reason of reasons)d.add("spot","CHECK_"+reason);
 assert(d.counts.spot.NEUTRAL===1&&d.counts.spot.CHECK_MOMENTUM===1&&d.counts.spot.CHECK_RSI===undefined);
});
