create or replace function public.bybit_demo_worker_unpriced_order_count(p_run_id uuid)
returns integer
language plpgsql security definer set search_path to ''
as $function$
declare v_count integer;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_run_id is null then raise exception 'invalid run id' using errcode='22023'; end if;
  select count(*)::integer into v_count
    from private.bybit_demo_orders o
   where o.run_id=p_run_id and not o.fee_conversion_complete;
  return coalesce(v_count,0);
end;
$function$;
revoke all on function public.bybit_demo_worker_unpriced_order_count(uuid) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_unpriced_order_count(uuid) to service_role;
