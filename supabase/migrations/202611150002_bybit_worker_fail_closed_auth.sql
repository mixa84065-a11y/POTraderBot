-- Make service-role checks fail closed if the JWT role claim is absent.
-- Applied to the linked Supabase project on 2026-11-15.
do $$
declare r record; d text;
begin
 for r in
   select p.oid from pg_proc p join pg_namespace n on n.oid=p.pronamespace
   where n.nspname='public' and p.proname in (
     'bybit_demo_worker_active_runs','bybit_demo_worker_close_position',
     'bybit_demo_worker_credentials','bybit_demo_worker_heartbeat',
     'bybit_demo_worker_heartbeat_v2','bybit_demo_worker_log',
     'bybit_demo_worker_open_position','bybit_demo_worker_open_positions'
   )
 loop
   d := pg_get_functiondef(r.oid);
   d := replace(d, $a$current_setting('request.jwt.claim.role',true) <> 'service_role'$a$,
                   $b$current_setting('request.jwt.claim.role',true) is distinct from 'service_role'$b$);
   d := replace(d, $a$current_setting('request.jwt.claim.role', true) <> 'service_role'$a$,
                   $b$current_setting('request.jwt.claim.role', true) is distinct from 'service_role'$b$);
   execute d;
 end loop;
end $$;
