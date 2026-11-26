import {validBars,type Bar,type Side} from "./research_strategy.ts";
/** Offline matched windows. Next-open entry, fixed-horizon exit, no future candles in signal. */
export function replay(input:{bars:readonly Bar[];interval:number;warmup:number;horizon:number;feeRate:number;costBps:number;decide:(past:readonly Bar[])=>Side|null}){
 const {bars,interval,warmup,horizon,feeRate,costBps,decide}=input;
 if(!Number.isInteger(warmup)||warmup<2||!Number.isInteger(horizon)||horizon<1||!Number.isFinite(feeRate)||feeRate<0||feeRate>=0.01||
 !Number.isFinite(costBps)||costBps<0||costBps>100||!validBars(bars,interval,(bars.at(-1)?.time??0)+interval))throw new Error("Invalid replay input");
 const cost=costBps/10000,stats=()=>({trades:0,wins:0,netReturnSum:0,equity:1,peak:1,maxDrawdown:0});
 const strategy=stats(),baseline=stats();let windows=0,skipped=0;
 function apply(s:ReturnType<typeof stats>,side:Side,start:number,end:number){
 const sign=side==="LONG"?1:-1,entry=start*(1+sign*cost),exit=end*(1-sign*cost);
 const net=((exit-entry)*sign-feeRate*(entry+exit))/entry;
 s.trades++;if(net>0)s.wins++;s.netReturnSum+=net;s.equity*=Math.max(0,1+net);
 s.peak=Math.max(s.peak,s.equity);s.maxDrawdown=Math.max(s.maxDrawdown,1-s.equity/s.peak);
 }
 for(let i=warmup;i+horizon-1<bars.length;i+=horizon){
 windows++;const side=decide(bars.slice(0,i).map(b=>({...b})));if(side===null){skipped++;continue;}
 if(side!=="LONG"&&side!=="SHORT")throw new Error("Invalid decision");
 const start=bars[i].open,end=bars[i+horizon-1].close;
 apply(strategy,side,start,end);apply(baseline,"LONG",start,end);
 }
 return {windows,skipped,strategy,baseline,comparison:"matched eligible windows; unconditional LONG; full-notional hypothetical compounded returns"};
}
