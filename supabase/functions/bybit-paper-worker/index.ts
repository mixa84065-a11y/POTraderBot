import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {assessEntry, ScanDiagnostics, rejectionKind, failedSignalChecks, paperPositionSize, PAPER_MIN_NOTIONAL_PCT, PAPER_MAX_NOTIONAL_PCT, PAPER_DEFAULT_NOTIONAL_PCT} from "./entry_quality.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ALLOWED = ["spot","linear","inverse","option","stock","ETF","forex","commodity"];

type Run = {
 id:string; user_id:string; status:string; market_categories:string[]; starting_balance_usdt:number; started_at?:string;
 cash_usdt:number; realized_pnl_usdt:number; max_daily_loss_usdt:number; max_positions:number; peak_equity_usdt:number; max_drawdown_pct:number;
 lease_token?:string|null; lease_until?:string|null; last_error?:string|null;
};
type Position = {
 id:string; run_id:string; user_id:string; market_category:string; symbol:string; side:string; qty:number;
 entry_price:number; mark_price:number; notional_usdt:number; stop_price:number; target_price:number;
 entry_fee_usdt:number; signal_score:number; signal_reason:string; opened_at:string;
};
type Ticker = { symbol:string; lastPrice?:string; bid1Price?:string; ask1Price?:string; turnover24h?:string;
 markPrice?:string; delta?:string; gamma?:string; vega?:string; theta?:string; markIv?:string; }
type Candidate = { category:string; symbol:string; price:number; bid:number; ask:number; turnover:number; score:number;
 side:"LONG"|"SHORT"|"CALL"|"PUT"; reason:string; expiry?:number; stopPct?:number; targetPct?:number; };

function response(data:unknown,status=200){return new Response(JSON.stringify(data),{status,headers:{"content-type":"application/json","cache-control":"no-store"}});}
async function rpc<T>(name:string,args:Record<string,unknown>={}):Promise<T>{
 const r=await fetch(`${SUPABASE_URL}/rest/v1/rpc/${name}`,{method:"POST",headers:{apikey:SERVICE_ROLE_KEY,authorization:`Bearer ${SERVICE_ROLE_KEY}`,"content-type":"application/json"},body:JSON.stringify(args),signal:AbortSignal.timeout(12000)});
 const raw=await r.text(); if(!r.ok) throw new Error(`RPC ${name} ${r.status}: ${raw.slice(0,300)}`); return raw?JSON.parse(raw) as T:null as T;
}
async function db(path:string,method="GET",body?:unknown){
 const r=await fetch(`${SUPABASE_URL}/rest/v1/${path}`,{method,headers:{apikey:SERVICE_ROLE_KEY,authorization:`Bearer ${SERVICE_ROLE_KEY}`,"content-type":"application/json",Prefer:method==="GET"?"return=representation":"return=minimal"},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(12000)});
 const raw=await r.text(); if(!r.ok) throw new Error(`DB ${method} ${path} ${r.status}: ${raw.slice(0,300)}`); return raw?JSON.parse(raw):null;
}
async function log(run:Run,level:string,type:string,message:string,details:unknown={}){
 await db("paper_events","POST",{run_id:run.id,user_id:run.user_id,level,event_type:type,message,details});
}
async function bybit(path:string,params:Record<string,string>){
 const u=new URL(`https://api.bybit.com${path}`);u.search=new URLSearchParams(params).toString();
 const r=await fetch(u,{signal:AbortSignal.timeout(10000)});const j=await r.json();
 if(!r.ok||j.retCode!==0)throw new Error(`Bybit ${path}: ${j.retMsg??r.status}`);return j.result;
}
function assetKey(symbol:string){return symbol.includes("-")?symbol.split("-")[0]:symbol.replace(/(USDT|USDC|USD)$/,"");}
function num(v:unknown){const n=Number(v);return Number.isFinite(n)?n:0;}
function ema(a:number[],p:number){const k=2/(p+1);let v=a.slice(0,p).reduce((x,y)=>x+y,0)/p;for(const x of a.slice(p))v=x*k+v*(1-k);return v;}
function rsi(a:number[],p=14){let up=0,down=0;for(let i=a.length-p;i<a.length;i++){const d=a[i]-a[i-1];if(d>0)up+=d;else down-=d;}if(down===0)return 100;return 100-100/(1+up/down);}
function atr(rows:number[][],period=14){
 const start=Math.max(1,rows.length-period);let total=0,count=0;
 for(let i=start;i<rows.length;i++){
  const high=rows[i][2],low=rows[i][3],prevClose=rows[i-1][4];
  const tr=Math.max(high-low,Math.abs(high-prevClose),Math.abs(low-prevClose));
  if(Number.isFinite(tr)&&tr>0){total+=tr;count++;}
 }
 return count?total/count:0;
}
function macdHistogram(values:number[]){
 if(values.length<35)return 0;
 const line:number[]=[];
 for(let i=25;i<values.length;i++){
  const slice=values.slice(0,i+1);
  line.push(ema(slice,12)-ema(slice,26));
 }
 if(line.length<10)return 0;
 return line.at(-1)!-ema(line,9);
}
function adx(rows:number[][],period=14){
 const dx:number[]=[];
 for(let i=Math.max(period+1,rows.length-period*2);i<rows.length;i++){
  let tr=0,plus=0,minus=0;
  for(let j=i-period+1;j<=i;j++){
   const up=rows[j][2]-rows[j-1][2];
   const down=rows[j-1][3]-rows[j][3];
   plus+=up>down&&up>0?up:0;
   minus+=down>up&&down>0?down:0;
   tr+=Math.max(rows[j][2]-rows[j][3],Math.abs(rows[j][2]-rows[j-1][4]),Math.abs(rows[j][3]-rows[j-1][4]));
  }
  if(tr<=0)continue;
  const pdi=100*plus/tr,mdi=100*minus/tr,den=pdi+mdi;
  if(den>0)dx.push(100*Math.abs(pdi-mdi)/den);
 }
 return dx.length?dx.slice(-period).reduce((a,b)=>a+b,0)/Math.min(period,dx.length):0;
}
function relativeVolume(rows:number[][],period=20){
 if(rows.length<period+1)return 0;
 const prior=rows.slice(-(period+1),-1).map(x=>x[5]).filter(x=>x>0);
 const avg=prior.reduce((a,b)=>a+b,0)/prior.length;
 return avg>0?rows.at(-1)![5]/avg:0;
}
function rollingVwap(rows:number[][],period=24){
 const recent=rows.slice(-period);let pv=0,vol=0;
 for(const r of recent){const v=r[5];if(v>0){pv+=((r[2]+r[3]+r[4])/3)*v;vol+=v;}}
 return vol>0?pv/vol:0;
}
async function signal(category:string,symbol:string){
 const [tk,kl,ml,ht]=await Promise.all([
  bybit("/v5/market/tickers",{category,symbol}),
  bybit("/v5/market/kline",{category,symbol,interval:"5",limit:"80"}),
  bybit("/v5/market/kline",{category,symbol,interval:"15",limit:"80"}),
  bybit("/v5/market/kline",{category,symbol,interval:"60",limit:"80"})
 ]);
 const t=(tk.list??[])[0] as Ticker|undefined;
 const rows=(kl.list??[]) as string[][];
 const mRows=(ml.list??[]) as string[][];
 const hRows=(ht.list??[]) as string[][];
 if(!t||rows.length<55||mRows.length<55||hRows.length<55)throw new Error("Недостаточно закрытых свечей 5m/15m/1h");
 const now=Date.now(),last5=num(rows[1]?.[0]),last15=num(mRows[1]?.[0]),last60=num(hRows[1]?.[0]);
 if(last5<=0||now-last5>10*60*1000)throw new Error("5m история устарела");
 if(last15<=0||now-last15>30*60*1000)throw new Error("15m история устарела");
 if(last60<=0||now-last60>3*60*60*1000)throw new Error("1h история устарела");
 const bars=rows.slice(1).reverse().map(x=>x.map(num));
 const closed=bars.map(x=>x[4]).filter(x=>x>0);
 const mClosed=mRows.slice(1).reverse().map(x=>num(x[4])).filter(x=>x>0);
 const hClosed=hRows.slice(1).reverse().map(x=>num(x[4])).filter(x=>x>0);
 if(closed.length<55||mClosed.length<55||hClosed.length<55)throw new Error("История свечей неполная");
 const fast=ema(closed,9),slow=ema(closed,21);
 const mFast=ema(mClosed,9),mSlow=ema(mClosed,21);
 const hFast=ema(hClosed,9),hSlow=ema(hClosed,21);
 const strength=rsi(closed);
 const momentum=(closed.at(-1)!/closed.at(-4)!-1)*100;
 const lastBar=bars.at(-1)!;
 const prevBar=bars.at(-2)!;
 const bullish=lastBar[4]>lastBar[1]&&lastBar[4]>prevBar[4];
 const bearish=lastBar[4]<lastBar[1]&&lastBar[4]<prevBar[4];
 const trend5=fast>slow?"UP":"DOWN";
 const trend15=mFast>mSlow?"UP":"DOWN";
 const trend1h=hFast>hSlow?"UP":"DOWN";
 const price=num(t.lastPrice),bid=num(t.bid1Price),ask=num(t.ask1Price);
 if(price<=0||bid<=0||ask<bid||((ask-bid)/price*100)>0.08)throw new Error("Цена/спред не проходят усиленный фильтр качества (макс. 0,08%)");
 const volatility=atr(bars,14);
 if(!Number.isFinite(volatility)||volatility<=0)throw new Error("Не удалось рассчитать ATR");
 const distance=Math.abs(price-slow)/volatility;
 if(distance>1.25)throw new Error("Цена слишком далеко от EMA21: вход после импульса запрещён");
 const macd=macdHistogram(closed);
 const trendStrength=adx(bars,14);
 const volRatio=relativeVolume(bars,20);
 const vwap=rollingVwap(bars,24);
 const macdUp=macd>0,macdDown=macd<0;
 const volumeOk=volRatio>=0.75;
 const adxOk=trendStrength>=16;
 const vwapUp=vwap>0&&price>=vwap;
 const vwapDown=vwap>0&&price<=vwap;
 const longOk=trend5==="UP"&&trend15==="UP"&&trend1h==="UP"&&strength>=52&&strength<=65&&momentum>=0.08&&bullish&&price>=fast&&macdUp&&adxOk&&volumeOk&&vwapUp;
 const shortOk=trend5==="DOWN"&&trend15==="DOWN"&&trend1h==="DOWN"&&strength>=35&&strength<=48&&momentum<=-0.08&&bearish&&price<=fast&&macdDown&&adxOk&&volumeOk&&vwapDown;
 const score=(trend5===trend15?1:0)+(trend15===trend1h?1:0)+(bullish||bearish?1:0)+((strength>=52&&strength<=65)||(strength>=35&&strength<=48)?1:0)+(Math.abs(momentum)>=0.08?1:0)+(distance<=1.25?1:0)+(macdUp||macdDown?1:0)+(adxOk?1:0)+(volumeOk?1:0)+((vwapUp||vwapDown)?1:0);
 const direction=longOk?"UP":shortOk?"DOWN":"NEUTRAL";
 // Report the individual failed predicates for the direction matching M5.
 // These counters are diagnostic only: several checks may fail on one symbol.
 const signalFailures=failedSignalChecks({
  trend_agreement:trend5===trend15&&trend15===trend1h,
  rsi:trend5==="UP"?strength>=52&&strength<=65:strength>=35&&strength<=48,
  momentum:trend5==="UP"?momentum>=0.08:momentum<=-0.08,
  candle_confirmation:trend5==="UP"?bullish:bearish,
  ema_price:trend5==="UP"?price>=fast:price<=fast,
  macd:trend5==="UP"?macdUp:macdDown,
  adx:adxOk,relative_volume:volumeOk,vwap:trend5==="UP"?vwapUp:vwapDown
 });
 const stopPct=Math.max(0.006,Math.min(0.012,(volatility/price)*1.5));
 const targetPct=stopPct*2.2;
 return {t,price,bid,ask,score,direction,stopPct,targetPct,signalFailures,
  reason:`EMA 5m/15m/1h: ${trend5}/${trend15}/${trend1h}; RSI ${strength.toFixed(1)}; MACD ${macd.toPrecision(4)}; ADX ${trendStrength.toFixed(1)}; объём ${volRatio.toFixed(2)}x; VWAP ${vwapUp?"выше":vwapDown?"ниже":"нет данных"}; импульс ${momentum.toFixed(3)}%; ATR-дистанция ${distance.toFixed(2)}`};
}
async function tickers(category:string):Promise<Ticker[]>{
 const r=await bybit("/v5/market/tickers",{category});return (r.list??[]) as Ticker[];
}
async function candidates(run:Run,diagnostics:ScanDiagnostics):Promise<Candidate[]>{
 const out:Candidate[]=[];const cats=run.market_categories.filter(x=>ALLOWED.includes(x));
 for(const category of ["spot","linear","inverse"]){
  if(!cats.includes(category))continue;
  try{
   const all=await tickers(category);
   const filtered=all.filter(t=>(category==="spot"?t.symbol.endsWith("USDT"):category==="linear"?t.symbol.endsWith("USDT"):t.symbol.endsWith("USD"))&&!(category==="spot"&&/^(USDC|USDE|FDUSD|TUSD|DAI|USDP)USDT$/.test(t.symbol)));
   const top=filtered.sort((a,b)=>num(b.turnover24h)-num(a.turnover24h)).slice(0,category==="spot"?3:2);
   diagnostics.add(category,"SELECTED_FOR_SCAN",top.length);
   for(const t of top){try{const s=await signal(category,t.symbol);if(s.direction==="NEUTRAL"){diagnostics.add(category,"NEUTRAL");for(const reason of s.signalFailures)diagnostics.add(category,"CHECK_"+reason);continue;}
    if(category==="spot"&&s.direction!=="UP"){diagnostics.add(category,"SPOT_NO_SHORT");continue;}
    diagnostics.add(category,"SIGNAL");
    out.push({category,symbol:t.symbol,price:s.direction==="UP"?s.ask:s.bid,bid:s.bid,ask:s.ask,turnover:num(t.turnover24h),score:s.score,side:s.direction==="UP"?"LONG":"SHORT",reason:s.reason,stopPct:s.stopPct,targetPct:s.targetPct});
   }catch(e){diagnostics.add(category,rejectionKind(e));}}
  }catch(_){diagnostics.add(category,"CATEGORY_DATA_ERROR");}
 }
 for(const type of ["stock","ETF","forex","commodity"]){
  if(!cats.includes(type))continue;
  try{
   const catalog=await bybit("/v5/market/instrument-summary",{category:"linear",symbolType:type,limit:"1000"});
   const list=(catalog.list??[]) as Array<{symbol:string;status?:string;deliveryTime?:string}>;
   const active=list.filter(x=>x.symbol&&(!x.status||x.status==="Trading")).slice(0,8);
   const linear=await tickers("linear");const map=new Map(linear.map(x=>[x.symbol,x]));
   const top=active.map(x=>map.get(x.symbol)).filter((x):x is Ticker=>!!x).sort((a,b)=>num(b.turnover24h)-num(a.turnover24h)).slice(0,2);
   diagnostics.add(type,"SELECTED_FOR_SCAN",top.length);
   for(const t of top){try{const s=await signal("linear",t.symbol);if(s.direction==="NEUTRAL"){diagnostics.add(type,"NEUTRAL");for(const reason of s.signalFailures)diagnostics.add(type,"CHECK_"+reason);continue;}
    diagnostics.add(type,"SIGNAL");
    out.push({category:type,symbol:t.symbol,price:s.direction==="UP"?s.ask:s.bid,bid:s.bid,ask:s.ask,turnover:num(t.turnover24h),score:s.score,side:s.direction==="UP"?"LONG":"SHORT",reason:`TradFi ${type}: ${s.reason}`,stopPct:s.stopPct,targetPct:s.targetPct});
   }catch(e){diagnostics.add(type,rejectionKind(e));}}
  }catch(_){diagnostics.add(type,"CATEGORY_DATA_ERROR");}
 }
 // Options remain catalogued for manual analysis only: auto-paper is disabled until expiry settlement and Greeks-based exits are implemented.
 if(cats.includes("option"))diagnostics.add("option","MANUAL_ONLY");
 return out.sort((a,b)=>Math.abs(b.score)-Math.abs(a.score));
}
async function processRun(run:Run){
 const lease=crypto.randomUUID();const claimed=await rpc<boolean>("paper_worker_claim_run",{p_run_id:run.id,p_lease_token:lease});
 if(!claimed)return {run_id:run.id,status:"SKIPPED_LOCKED"};
 let error:string|null=null;
 try{
  if(run.status==="RUNNING"&&run.started_at&&Date.now()-Date.parse(run.started_at)>=48*60*60*1000){
   run.status="STOPPING";
   await db(`paper_runs?id=eq.${run.id}`,"PATCH",{status:"STOPPING",stopped_at:new Date().toISOString(),last_error:"TEST_COMPLETE: завершён 48-часовой тест."});
   await log(run,"INFO","PAPER_TEST_48H_COMPLETE","48-часовой тест завершён. Новые входы запрещены; открытые позиции закрываются.");
  }
  const positions=(await db(`paper_positions?run_id=eq.${run.id}&status=eq.OPEN&select=*`)) as Position[];
  const quoteCache=new Map<string,number>();
  for(const p of positions){
   try{
    const cat=p.market_category==="spot"?"spot":p.market_category==="inverse"?"inverse":p.market_category==="option"?"option":"linear";
    const result=await bybit("/v5/market/tickers",{category:cat,symbol:p.symbol});
    const t=(result.list??[])[0] as Ticker|undefined;const price=num(p.side==="SHORT"?t?.ask1Price:t?.bid1Price);
    if(price<=0)continue;quoteCache.set(p.symbol,price);
    const signed=(p.side==="SHORT"?(p.entry_price-price):(price-p.entry_price))*p.qty;
    await db(`paper_positions?id=eq.${p.id}`,"PATCH",{mark_price:price,unrealized_pnl_usdt:signed,updated_at:new Date().toISOString()});
    const hitStop=p.side==="SHORT"?price>=p.stop_price:price<=p.stop_price;
    const hitTarget=p.side==="SHORT"?price<=p.target_price:price>=p.target_price;
    if(hitStop||hitTarget||run.status==="STOPPING"){
     const reason=run.status==="STOPPING"?"MANUAL_STOP":hitStop?"STOP_LOSS":"TAKE_PROFIT";
     await rpc("paper_worker_close_position",{p_position_id:p.id,p_exit_price:price,p_reason:reason,p_fee_rate:0.0006});
     await log(run,"INFO","PAPER_POSITION_CLOSED",`${p.symbol}: ${reason} исполнен.`,{side:p.side,entry:p.entry_price,exit:price});
    }
   }catch(e){await log(run,"WARN","PAPER_MARK_UPDATE_FAILED",String(e),{symbol:p.symbol});}
  }
  const summary=await db(`paper_runs?id=eq.${run.id}&select=*`) as Run[];
  const current=summary[0]??run;
  if(num(current.max_daily_loss_usdt)>0&&current.realized_pnl_usdt<=-Math.abs(current.max_daily_loss_usdt)&&current.status==="RUNNING"){
   current.status="STOPPING";current.last_error="RISK_STOP: достигнут лимит реализованного убытка.";
   await db(`paper_runs?id=eq.${run.id}`,"PATCH",{status:"STOPPING",stopped_at:new Date().toISOString(),last_error:current.last_error});
   await log(run,"ERROR","PAPER_RISK_STOP","Новые входы остановлены по лимиту реализованного убытка; открытые позиции будут сопровождаться.",{realized_pnl_usdt:current.realized_pnl_usdt});
  }
  const openRows=(await db(`paper_positions?run_id=eq.${run.id}&status=eq.OPEN&select=id,symbol,market_category,side,notional_usdt,unrealized_pnl_usdt`)) as Array<{id:string;symbol:string;market_category:string;side:string;notional_usdt:number;unrealized_pnl_usdt:number}>;
  const reserved=openRows.reduce((sum,x)=>sum+num(x.notional_usdt)*0.10,0);
  const floating=openRows.reduce((sum,x)=>sum+num(x.unrealized_pnl_usdt),0);
  const equity=num(current.cash_usdt)+reserved+floating;
  const peak=Math.max(num(current.peak_equity_usdt)||num(current.starting_balance_usdt),equity);
  const drawdown=peak>0?(peak-equity)/peak*100:0;
  await db(`paper_runs?id=eq.${run.id}`,"PATCH",{peak_equity_usdt:peak,max_drawdown_pct:Math.max(num(current.max_drawdown_pct),drawdown)});
  if(drawdown>=5&&current.status==="RUNNING"){
   current.status="STOPPING";current.last_error="RISK_STOP: достигнута просадка 5 процентов.";
   await db(`paper_runs?id=eq.${run.id}`,"PATCH",{status:"STOPPING",stopped_at:new Date().toISOString(),last_error:current.last_error});
   await log(run,"ERROR","PAPER_DRAWDOWN_STOP","Новые входы остановлены: просадка портфеля достигла 5 процентов.",{drawdown_pct:drawdown,equity_usdt:equity});
  }
  const stillOpen=openRows;
  if(current.status==="STOPPING"&&stillOpen.length===0){
   const terminal=current.last_error?.startsWith("RISK_STOP")?"RISK_STOP":"STOPPED";
   await db(`paper_runs?id=eq.${run.id}`,"PATCH",{status:terminal,updated_at:new Date().toISOString()});
   await log(run,"INFO","PAPER_RUN_FINISHED",terminal==="RISK_STOP"?"Демо завершено после закрытия всех позиций по лимиту риска.":"Демо остановлено после закрытия всех открытых позиций.");
  }
  const room=Math.max(0,current.max_positions-stillOpen.length);
  let opened=0;
  if(room>0&&current.status==="RUNNING"){
   const diagnostics=new ScanDiagnostics();
   const picks=await candidates(current,diagnostics);
   await log(run,"INFO","PAPER_SCAN_CYCLE","Мультисканер завершил цикл анализа.",{candidates:picks.slice(0,30).map(x=>({category:x.category,symbol:x.symbol,side:x.side,score:x.score,price:x.price,turnover:x.turnover})),open_positions:stillOpen.length,available_slots:room});
   const openAssets=((await db(`paper_positions?run_id=eq.${run.id}&status=eq.OPEN&select=symbol`)) as Array<{symbol:string}>).map(x=>assetKey(x.symbol));
   const cooldownSince=new Date(Date.now()-45*60*1000).toISOString();
   const recentStops=(await db(`paper_trades?run_id=eq.${run.id}&exit_reason=eq.STOP_LOSS&closed_at=gte.${encodeURIComponent(cooldownSince)}&select=symbol,closed_at`)) as Array<{symbol:string;closed_at:string}>;
   const blockedAssets=new Set([...openAssets,...recentStops.map(x=>assetKey(x.symbol))]);
   const cryptoCategories=new Set(["spot","linear","inverse"]);
   const cryptoExposureOpen=stillOpen.some(p=>cryptoCategories.has(p.market_category));
   if(recentStops.length>0){
    await log(run,"INFO","PAPER_GLOBAL_LOSS_COOLDOWN",
     "Новые входы временно приостановлены после стоп-лосса; ожидается подтверждение нового рыночного цикла.",
     {cooldown_minutes:45,recent_stop_losses:recentStops.length});
   }
   for(const c of picks){
    if(opened>=Math.min(room,1))break;
    if(recentStops.length>0)break;
    if(blockedAssets.has(assetKey(c.symbol))){diagnostics.add(c.category,"ASSET_BLOCKED");continue;}
    if(cryptoCategories.has(c.category)&&cryptoExposureOpen){diagnostics.add(c.category,"CRYPTO_EXPOSURE");continue;}
    const spread=(c.ask-c.bid)/c.price*100;if(!Number.isFinite(spread)||spread>0.08){diagnostics.add(c.category,"SPREAD");continue;}
    const price=c.price;const stopPct=c.stopPct??0.008;const targetPct=c.targetPct??(stopPct*2.2);
    const stop=c.side==="SHORT"?price*(1+stopPct):price*(1-stopPct);
    const target=c.side==="SHORT"?price*(1-targetPct):price*(1+targetPct);
    if(c.side!=="LONG"&&c.side!=="SHORT"){diagnostics.add(c.category,"UNSUPPORTED_SIDE");continue;}
    // Experimental thresholds fixed before evaluation; no claim of calibrated expected return.
    const quality=assessEntry({side:c.side,entry:price,bid:c.bid,ask:c.ask,stop,target,
      feeRate:0.0006,exitSlippageBps:2,minNetRewardRisk:1.5});
    if(!quality.ok){diagnostics.add(c.category,quality.reason);continue;}
    const notional=paperPositionSize({equity,startingBalance:num(current.starting_balance_usdt),cash:num(current.cash_usdt)});
    if(notional<=0){diagnostics.add(c.category,"MIN_POSITION_UNAVAILABLE");continue;}
    if(c.category==="option"&&price>notional)continue;
    const qty=c.category==="option"?1:notional/price;
    const entryFee=qty*price*0.0006;
    try{
     await rpc("paper_worker_open_position",{p_run_id:run.id,p_symbol:c.symbol,p_market_category:c.category,p_side:c.side,p_qty:qty,p_price:price,p_stop:stop,p_target:target,p_score:c.score,p_reason:c.reason,p_fee:entryFee});
     opened++;blockedAssets.add(assetKey(c.symbol));diagnostics.add(c.category,"OPENED");
     await log(run,"INFO","PAPER_POSITION_OPENED",`Открыта виртуальная ${c.side} позиция ${c.symbol}.`,{category:c.category,price,qty,score:c.score,stop,target,notional_usdt:qty*price,notional_pct_equity:100*notional/equity});
    }catch(e){diagnostics.add(c.category,"ORDER_RPC_REJECTED");await log(run,"WARN","PAPER_ENTRY_SKIPPED",String(e),{category:c.category,symbol:c.symbol,score:c.score});}
   }
   await log(run,"INFO","PAPER_SCAN_DIAGNOSTICS","Причины отбора и отказа от виртуальных входов.",{
     categories:diagnostics.counts,global_cooldown:recentStops.length>0,entry_limit_per_cycle:Math.min(room,1),opened,
     note:"CHECK_* counters overlap; each neutral symbol may fail multiple predicates.",
     cost_model:{fee_per_side:0.0006,exit_slippage_bps:2,min_net_reward_risk:1.5},
     sizing:{min_notional_pct:PAPER_MIN_NOTIONAL_PCT,max_notional_pct:PAPER_MAX_NOTIONAL_PCT,default_notional_pct:PAPER_DEFAULT_NOTIONAL_PCT}
   });
  }
  await db(`paper_runs?id=eq.${run.id}`,"PATCH",{last_cycle_at:new Date().toISOString(),...(current.status==="RUNNING"?{last_error:null}:{})});
  return {run_id:run.id,status:"OK",open_positions:stillOpen.length,opened};
 }catch(e){error=String(e);await log(run,"ERROR","PAPER_WORKER_ERROR",error);}
 finally{await rpc("paper_worker_release_run",{p_run_id:run.id,p_lease_token:lease,p_error:error});}
 return {run_id:run.id,status:"ERROR",error};
}
Deno.serve(async(req:Request)=>{
 if(req.method!=="POST")return response({error:"POST required"},405);
 const secret=req.headers.get("x-worker-secret");if(!secret)return response({error:"Unauthorized"},401);
 try{
  const ok=await rpc<boolean>("bybit_demo_worker_authorize",{p_secret:secret});if(!ok)return response({error:"Unauthorized"},401);
  const runs=(await db("paper_runs?status=in.(RUNNING,STOPPING,PAUSED)&select=*")) as Run[];
  const results=[];for(const run of runs??[])results.push(await processRun(run));
  return response({ok:true,processed:results.length,results});
 }catch(e){return response({error:String(e)},500);}
});
