-- Physical exit fills must reduce the position even if a third-token fee
-- conversion is temporarily unavailable. The later fee repair applies only
-- the fee delta to realized PnL and the fee ledger.
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
     where f.run_id=p_run_id and o.purpose='EXIT' and o.position_id is not null
       and f.applied_at is null
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
  if v_order.id is null or v_order.purpose<>'EXIT' or v_order.side<>'Sell'
     or v_order.position_id is null then raise exception 'exit fill is not linked to a position'; end if;

  -- Null fee means the fill is real but its third-token fee has not yet been
  -- converted. Apply quantity now; the idempotent fee-repair RPC corrects PnL later.
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
    exit_fees=exit_fees+v_fee,
    fees=fees+v_entry_fee_alloc+v_fee,
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
    realized_pnl=realized_pnl+v_net_pnl,
    fees_paid=fees_paid+v_fee,updated_at=now()
   where id=v_pos.run_id;

  update private.bybit_demo_fills set applied_at=now() where id=v_fill.id;
  return jsonb_build_object('applied',true,'closed',v_closed,
    'remaining_qty',case when v_closed then 0 else v_new_qty end,
    'realized_pnl',v_net_pnl,'fees',v_entry_fee_alloc+v_fee,
    'fee_conversion_pending',v_fill.fee_usdt is null);
end;
$function$;
revoke all on function public.bybit_demo_worker_pending_exit_fills(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_apply_exit_fill(bigint,text) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_pending_exit_fills(uuid) to service_role;
grant execute on function public.bybit_demo_worker_apply_exit_fill(bigint,text) to service_role;
