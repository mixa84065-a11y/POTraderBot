create or replace function public.bybit_demo_worker_pending_orders(p_run_id uuid)
returns table(
  order_id uuid, position_id uuid, symbol text, side text, purpose text,
  order_link_id text, exchange_order_id text, requested_qty numeric,
  qty_unit text, status text
)
language plpgsql security definer set search_path to ''
as $function$
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_run_id is null then raise exception 'invalid run id' using errcode='22023'; end if;
  return query
    select o.id,o.position_id,o.symbol,o.side,o.purpose,o.order_link_id,
           o.exchange_order_id,o.requested_qty,o.qty_unit,o.status
      from private.bybit_demo_orders o
     where o.run_id=p_run_id
       and o.status in ('INTENT','SUBMITTED','PARTIALLY_FILLED','UNKNOWN')
     order by o.created_at asc;
end;
$function$;

create or replace function public.bybit_demo_worker_link_order_position(
  p_order_id uuid, p_position_id uuid
) returns void
language plpgsql security definer set search_path to ''
as $function$
declare v_order private.bybit_demo_orders%rowtype; v_pos private.bybit_demo_positions%rowtype;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_order_id is null or p_position_id is null then raise exception 'invalid link' using errcode='22023'; end if;
  select * into v_order from private.bybit_demo_orders where id=p_order_id for update;
  select * into v_pos from private.bybit_demo_positions where id=p_position_id;
  if v_order.id is null or v_pos.id is null or v_order.purpose<>'ENTRY'
     or v_order.run_id<>v_pos.run_id or v_order.user_id<>v_pos.user_id
     or v_order.symbol<>v_pos.symbol or v_order.exchange_order_id is distinct from v_pos.entry_order_id then
    raise exception 'order and position do not match';
  end if;
  if v_order.position_id is not null and v_order.position_id<>p_position_id then
    raise exception 'order already linked to another position';
  end if;
  update private.bybit_demo_orders set position_id=p_position_id,updated_at=now() where id=p_order_id;
end;
$function$;

revoke all on function public.bybit_demo_worker_pending_orders(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_link_order_position(uuid,uuid) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_pending_orders(uuid) to service_role;
grant execute on function public.bybit_demo_worker_link_order_position(uuid,uuid) to service_role;
