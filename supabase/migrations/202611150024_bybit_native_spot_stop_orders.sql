-- Add exchange-native Spot conditional stop orders for each Bybit Demo long.
alter table private.bybit_demo_orders add column if not exists trigger_price numeric(30,12) null
  check (trigger_price is null or trigger_price>0);

alter table private.bybit_demo_orders drop constraint if exists bybit_demo_orders_purpose_check;
alter table private.bybit_demo_orders add constraint bybit_demo_orders_purpose_check
  check (purpose in ('ENTRY','EXIT','PROTECTION'));

create unique index if not exists bybit_demo_one_active_protection_per_position
  on private.bybit_demo_orders(position_id)
  where purpose='PROTECTION' and status in ('INTENT','SUBMITTED','PARTIALLY_FILLED','UNKNOWN');

create or replace function public.bybit_demo_worker_create_protection_intent(
  p_run_id uuid,p_position_id uuid,p_order_link_id text,p_requested_qty numeric,p_trigger_price numeric
) returns uuid
language plpgsql security definer set search_path to ''
as $function$
declare
  v_pos private.bybit_demo_positions%rowtype;
  v_existing private.bybit_demo_orders%rowtype;
  v_id uuid;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_run_id is null or p_position_id is null or p_order_link_id is null
     or length(p_order_link_id) not between 1 and 36
     or p_requested_qty is null or p_requested_qty<=0
     or p_trigger_price is null or p_trigger_price<=0 then
    raise exception 'invalid protection intent' using errcode='22023';
  end if;
  select * into v_pos from private.bybit_demo_positions
   where id=p_position_id and run_id=p_run_id and status='OPEN' for update;
  if v_pos.id is null then raise exception 'open position not found'; end if;
  if p_requested_qty>v_pos.qty then raise exception 'protection quantity exceeds open position'; end if;
  if p_trigger_price>=v_pos.entry_price then raise exception 'stop trigger must be below entry price'; end if;

  select * into v_existing from private.bybit_demo_orders
   where position_id=p_position_id and purpose='PROTECTION'
     and status in ('INTENT','SUBMITTED','PARTIALLY_FILLED','UNKNOWN')
   for update;
  if v_existing.id is not null then
    if v_existing.order_link_id<>p_order_link_id or v_existing.requested_qty<>p_requested_qty
       or v_existing.trigger_price<>p_trigger_price then
      raise exception 'an active protection order already exists for this position';
    end if;
    return v_existing.id;
  end if;

  insert into private.bybit_demo_orders(
    run_id,user_id,position_id,symbol,side,purpose,order_link_id,
    requested_qty,qty_unit,trigger_price
  ) values (
    p_run_id,v_pos.user_id,v_pos.id,v_pos.symbol,'Sell','PROTECTION',
    p_order_link_id,p_requested_qty,'baseCoin',p_trigger_price
  ) on conflict(run_id,order_link_id) do nothing returning id into v_id;
  if v_id is null then
    select id into v_id from private.bybit_demo_orders
     where run_id=p_run_id and order_link_id=p_order_link_id;
  end if;
  return v_id;
end;
$function$;

create or replace function public.bybit_demo_worker_pending_protections(p_run_id uuid)
returns table(
  order_id uuid,position_id uuid,symbol text,side text,purpose text,
  order_link_id text,exchange_order_id text,requested_qty numeric,
  qty_unit text,status text,trigger_price numeric
)
language plpgsql security definer set search_path to ''
as $function$
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_run_id is null then raise exception 'invalid run id' using errcode='22023'; end if;
  return query
    select o.id,o.position_id,o.symbol,o.side,o.purpose,o.order_link_id,
           o.exchange_order_id,o.requested_qty,o.qty_unit,o.status,o.trigger_price
      from private.bybit_demo_orders o
     where o.run_id=p_run_id and o.purpose='PROTECTION'
       and (
         o.status in ('INTENT','SUBMITTED','PARTIALLY_FILLED','UNKNOWN')
         or exists(select 1 from private.bybit_demo_fills f
                    where f.order_id=o.id and f.applied_at is null)
       )
     order by o.created_at;
end;
$function$;

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
     where o.run_id=p_run_id and o.purpose<>'PROTECTION'
       and (
         o.status in ('INTENT','SUBMITTED','PARTIALLY_FILLED','UNKNOWN')
         or (o.purpose='ENTRY' and o.position_id is null and o.filled_qty>0
             and o.status in ('FILLED','CANCELLED'))
         or (o.purpose='ENTRY' and o.position_id is null and o.status='REJECTED'
             and o.last_reconciled_at is null)
         or (o.purpose='EXIT' and exists (
               select 1 from private.bybit_demo_fills f
                where f.order_id=o.id and f.applied_at is null
             ))
       )
     order by o.created_at asc;
end;
$function$;

create or replace function public.bybit_demo_worker_pending_exit_fills(p_run_id uuid)
returns table(fill_id bigint, order_id uuid, position_id uuid, symbol text, qty numeric,
              price numeric, fee_usdt numeric, exchange_order_id text)
language plpgsql security definer set search_path to ''
as $function$
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_run_id is null then raise exception 'invalid run id' using errcode='22023'; end if;
  return query
    select f.id,o.id,o.position_id,f.symbol,f.qty,f.price,f.fee_usdt,o.exchange_order_id
      from private.bybit_demo_fills f
      join private.bybit_demo_orders o on o.id=f.order_id
     where f.run_id=p_run_id and o.purpose in ('EXIT','PROTECTION')
       and o.position_id is not null and f.applied_at is null
     order by f.executed_at,f.id;
end;
$function$;

create or replace function public.bybit_demo_worker_apply_exit_fill(
  p_fill_id bigint, p_reason text default 'Risk exit'
) returns jsonb
language plpgsql security definer set search_path to ''
as $function$
declare
  v_fill private.bybit_demo_fills%rowtype;
  v_order private.bybit_demo_orders%rowtype;
  v_pos private.bybit_demo_positions%rowtype;
  v_trade private.bybit_demo_trades%rowtype;
  v_entry_fee_alloc numeric;
  v_new_qty numeric;
  v_closed_qty numeric;
  v_new_avg_exit numeric;
  v_net_pnl numeric;
  v_closed boolean;
  v_fee numeric;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_fill_id is null then raise exception 'invalid fill id' using errcode='22023'; end if;
  select * into v_fill from private.bybit_demo_fills where id=p_fill_id for update;
  if v_fill.id is null then raise exception 'fill not found'; end if;
  if v_fill.applied_at is not null then
    return jsonb_build_object('applied',false,'already_applied',true);
  end if;
  select * into v_order from private.bybit_demo_orders where id=v_fill.order_id for update;
  if v_order.id is null or v_order.purpose not in ('EXIT','PROTECTION') or v_order.side<>'Sell'
     or v_order.position_id is null then raise exception 'exit fill is not linked to a position'; end if;
  v_fee := coalesce(v_fill.fee_usdt,0);
  select * into v_pos from private.bybit_demo_positions
   where id=v_order.position_id and run_id=v_fill.run_id and user_id=v_fill.user_id
   for update;
  if v_pos.id is null or v_pos.status<>'OPEN' then raise exception 'open position not found'; end if;
  select * into v_trade from private.bybit_demo_trades
   where run_id=v_pos.run_id and entry_order_id=v_pos.entry_order_id and status='OPEN'
   for update;
  if v_trade.id is null then raise exception 'open trade not found'; end if;
  if v_fill.qty>v_pos.qty then raise exception 'exit fill exceeds remaining position'; end if;
  v_new_qty := v_pos.qty-v_fill.qty;
  v_closed := v_new_qty<=0.000000000001;
  v_entry_fee_alloc := case when v_closed
    then greatest(0,v_trade.entry_fees-v_trade.entry_fee_allocated)
    else least(greatest(0,v_trade.entry_fees-v_trade.entry_fee_allocated),
      v_trade.entry_fees*(v_fill.qty/v_trade.qty)) end;
  v_net_pnl := (v_fill.price-v_trade.entry_price)*v_fill.qty-v_entry_fee_alloc-v_fee;
  v_closed_qty := v_trade.qty-v_pos.qty+v_fill.qty;
  v_new_avg_exit := case when v_closed_qty>0 then
    (coalesce(v_trade.exit_price,0)*(v_closed_qty-v_fill.qty)+v_fill.price*v_fill.qty)/v_closed_qty
    else v_fill.price end;
  update private.bybit_demo_trades set
    exit_price=v_new_avg_exit,
    exit_order_id=coalesce(v_order.exchange_order_id,v_order.order_link_id),
    entry_fee_allocated=entry_fee_allocated+v_entry_fee_alloc,
    exit_fees=exit_fees+v_fee,fees=fees+v_entry_fee_alloc+v_fee,
    pnl=coalesce(pnl,0)+v_net_pnl,
    exit_reason=left(coalesce(p_reason,'Risk exit'),500),
    status=case when v_closed then 'CLOSED' else 'OPEN' end,
    closed_at=case when v_closed then now() else null end
   where id=v_trade.id;
  update private.bybit_demo_positions set
    qty=case when v_closed then qty else v_new_qty end,
    status=case when v_closed then 'CLOSED' else 'OPEN' end,
    closed_at=case when v_closed then now() else null end
   where id=v_pos.id;
  update private.bybit_demo_runs set
    realized_pnl=realized_pnl+v_net_pnl,fees_paid=fees_paid+v_fee,updated_at=now()
   where id=v_pos.run_id;
  update private.bybit_demo_fills set applied_at=now() where id=v_fill.id;
  return jsonb_build_object('applied',true,'closed',v_closed,
    'remaining_qty',case when v_closed then 0 else v_new_qty end,
    'realized_pnl',v_net_pnl,'fees',v_entry_fee_alloc+v_fee,
    'fee_conversion_pending',v_fill.fee_usdt is null);
end;
$function$;

revoke all on function public.bybit_demo_worker_create_protection_intent(uuid,uuid,text,numeric,numeric) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_pending_protections(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_pending_orders(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_pending_exit_fills(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_apply_exit_fill(bigint,text) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_create_protection_intent(uuid,uuid,text,numeric,numeric) to service_role;
grant execute on function public.bybit_demo_worker_pending_protections(uuid) to service_role;
grant execute on function public.bybit_demo_worker_pending_orders(uuid) to service_role;
grant execute on function public.bybit_demo_worker_pending_exit_fills(uuid) to service_role;
grant execute on function public.bybit_demo_worker_apply_exit_fill(bigint,text) to service_role;
