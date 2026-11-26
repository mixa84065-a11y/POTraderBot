/** Paper-only scenario, not a forecast. Entry/exit prices are executable sides
 * (ask to buy, bid to sell); do not subtract the spread a second time.
 * Slippage is an explicit adverse exit-price stress, not observed fill data.
 */
export type Direction="LONG"|"SHORT";
export function assessEntry(input:{side:Direction;entry:number;bid:number;ask:number;stop:number;target:number;
 feeRate:number;exitSlippageBps:number;minNetRewardRisk:number}){
 const {side,entry,bid,ask,stop,target,feeRate,exitSlippageBps,minNetRewardRisk}=input;
 const valid=[entry,bid,ask,stop,target].every(x=>Number.isFinite(x)&&x>0)
  &&[feeRate,exitSlippageBps,minNetRewardRisk].every(Number.isFinite)
  &&feeRate>=0&&feeRate<=0.01&&exitSlippageBps>=0&&exitSlippageBps<=100&&minNetRewardRisk>0
  &&ask>=bid&&(side==="LONG"||side==="SHORT")
  &&(side==="LONG"?entry===ask&&stop<bid&&target>entry:entry===bid&&stop>ask&&target<entry);
 if(!valid)return {ok:false,reason:"INVALID_EXECUTION_GEOMETRY" as const};
 const sign=side==="LONG"?1:-1;
 const stress=exitSlippageBps/10000;
 const targetExit=target*(1-sign*stress),stopExit=stop*(1-sign*stress);
 const net=(exit:number)=>(exit-entry)*sign-feeRate*(entry+exit);
 const reward=net(targetExit),risk=-net(stopExit);
 const immediateNet=net(side==="LONG"?bid:ask);
 const ratio=risk>0?reward/risk:0;
 const breakeven=side==="LONG"?entry*(1+feeRate)/(1-feeRate):entry*(1-feeRate)/(1+feeRate);
 const reason=reward<=0?"TARGET_BELOW_COSTS":ratio<minNetRewardRisk?"NET_REWARD_RISK_TOO_LOW":"PASS";
 return {ok:reason==="PASS",reason,netRewardPerUnit:reward,netRiskPerUnit:risk,netRewardRisk:ratio,immediateNetPerUnit:immediateNet,breakevenExit:breakeven};
}
export function rejectionKind(error:unknown):string{
 const message=error instanceof Error?error.message:String(error);
 if(message.includes("устарела"))return "STALE_HISTORY";
 if(message.includes("свечей")||message.includes("История свечей"))return "INSUFFICIENT_HISTORY";
 if(message.includes("Цена/спред"))return "INVALID_QUOTE_OR_SPREAD";
 if(message.includes("ATR"))return "ATR_UNAVAILABLE";
 if(message.includes("EMA21"))return "LATE_ENTRY";
 return "DATA_OR_API_ERROR";
}
export class ScanDiagnostics {
 readonly counts:Record<string,Record<string,number>>={};
 add(category:string,reason:string,n=1){
  const group=this.counts[category]??(this.counts[category]={});
  group[reason]=(group[reason]??0)+n;
 }
}

/** Returns only failed predicates, without modifying the signal decision. */
export function failedSignalChecks(checks:Readonly<Record<string,boolean>>):string[]{
 return Object.keys(checks).filter(key=>checks[key]===false).map(key=>key.toUpperCase());
}

/** Virtual position notional, not the amount at risk. Conservative default;
 * raising the research setting to 5% requires a separate deliberate change.
 * Retain existing $100 notional cap and database's 10% starting-capital cap.
 * If caps or available virtual margin prevent the 3% minimum, skip the entry.
 */
export const PAPER_MIN_NOTIONAL_PCT=3;
export const PAPER_MAX_NOTIONAL_PCT=5;
export const PAPER_DEFAULT_NOTIONAL_PCT=3;
export function paperPositionSize(input:{equity:number;startingBalance:number;cash:number;requestedPct?:number}):number{
 const {equity,startingBalance,cash,requestedPct=PAPER_DEFAULT_NOTIONAL_PCT}=input;
 if(![equity,startingBalance,cash,requestedPct].every(Number.isFinite)||
  equity<=0||startingBalance<=0||cash<=0||
  requestedPct<PAPER_MIN_NOTIONAL_PCT||requestedPct>PAPER_MAX_NOTIONAL_PCT)return 0;
 const minimum=equity*PAPER_MIN_NOTIONAL_PCT/100;
 // Existing paper ledger reserves 10% of notional; this does not represent real exchange margin.
 const capped=Math.min(equity*requestedPct/100,100,startingBalance*0.10,cash/0.10);
 return capped>=minimum?capped:0;
}
