/** Research/reconciliation helper. Exact decimal arithmetic at the persisted 12-place precision.
 * Not used by order submission until the position ledger migration is reviewed.
 */
type Fill = { qty: string; fee_amount: string; fee_currency: string };
const SCALE=10n**12n;
function atoms(s:string):bigint {
  if(!/^\d+(?:\.\d{1,12})?$/.test(s))throw new Error("Invalid nonnegative decimal");
  const [whole,fraction=""]=s.split(".");
  return BigInt(whole)*SCALE+BigInt(fraction.padEnd(12,"0"));
}
function decimal(n:bigint):string{
  return (n/SCALE).toString()+"."+(n%SCALE).toString().padStart(12,"0");
}
export function netAcquiredBase(fills:Fill[],baseCoin:string):string{
  if(!/^[A-Z0-9]{2,20}$/.test(baseCoin)||fills.length===0)throw new Error("Missing base coin or fills");
  let total=0n;
  for(const fill of fills){
    const qty=atoms(fill.qty),fee=atoms(fill.fee_amount);
    if(qty<=0n||!/^[A-Z0-9]{2,20}$/.test(fill.fee_currency))throw new Error("Invalid fill");
    if(fill.fee_currency===baseCoin&&fee>qty)throw new Error("Fee exceeds fill");
    total+=qty-(fill.fee_currency===baseCoin?fee:0n);
  }
  return decimal(total);
}
export function floorInventoryToStep(inventory:string,step:string):string{
  const n=atoms(inventory),s=atoms(step);
  if(s<=0n)throw new Error("Invalid step");
  return decimal(n/s*s);
}
