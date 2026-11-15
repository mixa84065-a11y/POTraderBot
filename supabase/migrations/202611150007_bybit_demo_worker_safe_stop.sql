create or replace function public.bybit_demo_worker_request_stop(
  p_run_id uuid,
  p_reason text default 'Stop requested'
)
returns void
language plpgsql
security definer
set search_path to ''
as $function$
begin
  if not public.bybit_demo_worker_is_service_role() then
    raise exception 'forbidden';
  end if;
  if p_run_id is null then
    raise exception 'invalid run id' using errcode='22023';
  end if;
  update private.bybit_demo_runs
     set status = 'STOPPING',
         last_error = null,
         updated_at = now()
   where id = p_run_id
     and status = 'RUNNING';
  if found then
    insert into private.bybit_demo_events(run_id,user_id,level,event_type,message,details)
    select id,user_id,'WARN','STOP_REQUESTED',left(coalesce(p_reason,'Stop requested'),1000),'{}'::jsonb
      from private.bybit_demo_runs
     where id=p_run_id;
  end if;
end;
$function$;

revoke all on function public.bybit_demo_worker_request_stop(uuid,text) from public, anon, authenticated;
grant execute on function public.bybit_demo_worker_request_stop(uuid,text) to service_role;
