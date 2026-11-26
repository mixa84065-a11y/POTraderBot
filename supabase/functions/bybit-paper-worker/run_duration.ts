/** Test deadline uses server UTC, and a missing or invalid start fails closed. */
export function expiredRun(startedAt:string|undefined,durationMinutes:number|undefined,now:number):boolean{
 const start=Date.parse(startedAt??"");const minutes=durationMinutes??2880;
 return !Number.isFinite(start)||!Number.isFinite(now)||!Number.isInteger(minutes)||minutes<1||minutes>2880||now>=start+minutes*60000;
}
