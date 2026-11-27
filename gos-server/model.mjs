/** GoS server-compatible pure model; not empirically validated. */
export function analyze(input){
 const valid = (s) => {
  if(!s || typeof s!=="object" || Array.isArray(s))throw new Error("invalid_summary");
  for(const k of ["n","gf","ga","htN","htFor","htAgainst"]) if(!Number.isInteger(s[k])||s[k]<0)throw new Error("invalid_summary");
  if(s.n>20||s.htN>s.n||s.gf>100*s.n||s.ga>100*s.n||s.htFor>s.gf||s.htAgainst>s.ga||s.htFor>100*s.htN||s.htAgainst>100*s.htN)throw new Error("invalid_summary");
  return s;
 };
 const h=valid(input?.home),a=valid(input?.away);
 if(h.n<5||a.n<5)return {model:"poisson-v1",status:"insufficient_history"};
 const x=(h.gf/h.n+a.ga/a.n)/2,y=(a.gf/a.n+h.ga/h.n)/2;
 if(x+y>50)throw new Error("invalid_mean");
 const above=(m,k)=>{let t=Math.exp(-m),s=t;for(let i=1;i<=k;i++){t*=m/i;s+=t;}return Math.max(0,Math.min(1,1-s));};
 const both=(u,v)=>Math.expm1(-u)*Math.expm1(-v);
 const dist=(m)=>{const p=[Math.exp(-m)];for(let i=1;i<=150;i++)p.push(p[i-1]*m/i);return p;};
 const p=dist(x),q=dist(y),r=[0,0,0];
 for(let i=0;i<p.length;i++)for(let j=0;j<q.length;j++)r[i>j?0:i===j?1:2]+=p[i]*q[j];
 const total=r.reduce((acc,v)=>acc+v,0);
 const out={model:"poisson-v1",status:"ok",validated:false,expected:{home:x,away:y},sample:{home:h.n,away:a.n,homeHalf:h.htN,awayHalf:a.htN},fullTime:{homeWin:r[0]/total,draw:r[1]/total,awayWin:r[2]/total,bothScore:both(x,y),goalsAtLeast3:above(x+y,2)},firstHalf:null};
 if(h.htN>=5&&a.htN>=5){
  const u=(h.htFor/h.htN+a.htAgainst/a.htN)/2,v=(a.htFor/a.htN+h.htAgainst/h.htN)/2;
  if(u<=x&&v<=y)out.firstHalf={expectedHome:u,expectedAway:v,noGoal:1-above(u+v,0),goalsAtLeast1:above(u+v,0),goalsAtLeast2:above(u+v,1),goalsAtLeast3:above(u+v,2),bothScore:both(u,v)};
 }
 return out;
}
