import {netAcquiredBase,floorInventoryToStep} from "./spot_inventory.ts";
Deno.test("SOL incident: base fee reduces sellable inventory",()=>{
  const net=netAcquiredBase([{qty:"1.406271973000",fee_amount:"0.001406271973",fee_currency:"SOL"}],"SOL");
  if(net!=="1.404865701027")throw new Error(net);
  if(floorInventoryToStep(net,"0.0001")!=="1.404800000000")throw new Error("Step rounding");
});
Deno.test("quote fees do not reduce base inventory; mixed fills summed exactly",()=>{
  const net=netAcquiredBase([{qty:"1",fee_amount:"0.1",fee_currency:"USDT"},{qty:"2",fee_amount:"0.002",fee_currency:"SOL"}],"SOL");
  if(net!=="2.998000000000")throw new Error(net);
});
Deno.test("invalid inventory cannot produce a quantity",()=>{
  for(const fee of ["1.01","-0.1","NaN"]){
    let failed=false;
    try{netAcquiredBase([{qty:"1",fee_amount:fee,fee_currency:"SOL"}],"SOL");}catch{failed=true;}
    if(!failed)throw new Error("Invalid fee accepted");
  }
});
