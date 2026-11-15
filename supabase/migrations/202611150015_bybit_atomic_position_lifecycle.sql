-- Atomic, replay-safe Bybit Demo position lifecycle.
alter table private.bybit_demo_fills
  add column if not exists applied_at timestamptz null;

create index if not exists bybit_demo_fills_unapplied_idx
  on private.bybit_demo_fills(order_id, id)
  where applied_at is null;

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
       and (
         o.status in ('INTENT','SUBMITTED','PARTIALLY_FILLED','UNKNOWN')
         or (o.purpose='ENTRY' and o.filled_qty>0 and o.position_id is null
             and o.status in ('FILLED','CANCELLED'))
         or (o.purpose='EXIT' and exists (
               select 1 from private.bybit_demo_fills f
                where f.order_id=o.id and f.applied_at is null
             ))
       )
     order by o.created_at asc;
end;
$function$;

create or replace function public.bybit_demo_worker_finalize_entry(p_order_id uuid)
returns jsonb
language plpgsql security definer set search_path to ''
as $function$
declare
  v_order private.bybit_demo_orders%rowtype;
  v_run private.bybit_demo_runs%rowtype;
  v_position_id uuid;
  v_price numeric;
  v_stop numeric;
  v_target numeric;
  v_score integer;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_order_id is null then raise exception 'invalid order id' using errcode='22023'; end if;

  select * into v_order from private.bybit_demo_orders where id=p_order_id for update;
  if v_order.id is null or v_order.purpose<>'ENTRY' or v_order.side<>'Buy' then
    raise exception 'entry order not found or invalid';
  end if;

  if v_order.position_id is not null then
    return jsonb_build_object('position_id',v_order.position_id,'created',false,
      'fee_conversion_complete',v_order.fee_conversion_complete);
  end if;
  if v_order.status not in ('FILLED','CANCELLED') or v_order.filled_qty<=0
     or v_order.exchange_order_id is null or v_order.filled_value<=0 then
    raise exception 'entry order has no confirmed fills';
  end if;

  select * into v_run from private.bybit_demo_runs
   where id=v_order.run_id and status in ('RUNNING','STOPPING') for update;
  if v_run.id is null then raise exception 'run not available for entry recovery'; end if;
  if not (v_order.symbol=any(v_run.symbols)) then raise exception 'symbol not enabled for run'; end if;

  select id into v_position_id from private.bybit_demo_positions
   where run_id=v_order.run_id and entry_order_id=v_order.exchange_order_id;
  if v_position_id is not null then
    update private.bybit_demo_orders set position_id=v_position_id,updated_at=now()
     where id=v_order.id;
    update private.bybit_demo_fills set applied_at=coalesce(applied_at,now())
     where order_id=v_order.id;
    return jsonb_build_object('position_id',v_position_id,'created',false,
      'fee_conversion_complete',v_order.fee_conversion_complete);
  end if;

  if exists(select 1 from private.bybit_demo_positions
     where run_id=v_order.run_id and symbol=v_order.symbol and status='OPEN') then
    raise exception 'another open position exists for this symbol';
  end if;

  v_price := v_order.filled_value/v_order.filled_qty;
  v_stop := v_price*0.993;
  v_target := v_price*1.014;
  v_score := null;

  insert into private.bybit_demo_positions(
    run_id,user_id,symbol,qty,entry_price,entry_order_id,stop_price,target_price
  ) values (
    v_order.run_id,v_order.user_id,v_order.symbol,v_order.filled_qty,v_price,
    v_order.exchange_order_id,v_stop,v_target
  ) returning id into v_position_id;

  insert into private.bybit_demo_trades(
    run_id,user_id,symbol,side,qty,entry_price,entry_order_id,
    fees,entry_fees,strategy_score,entry_reason
  ) values (
    v_order.run_id,v_order.user_id,v_order.symbol,'BUY',v_order.filled_qty,
    v_price,v_order.exchange_order_id,v_order.fee_usdt,v_order.fee_usdt,
    v_score,'Bybit Demo strategy entry'
  );

  update private.bybit_demo_runs
     set fees_paid=fees_paid+v_order.fee_usdt,updated_at=now()
   where id=v_order.run_id;
  update private.bybit_demo_orders
     set position_id=v_position_id,updated_at=now()
   where id=v_order.id;
  update private.bybit_demo_fills set applied_at=coalesce(applied_at,now())
   where order_id=v_order.id;

  return jsonb_build_object('position_id',v_position_id,'created',true,
    'entry_price',v_price,'qty',v_order.filled_qty,
    'fee_conversion_complete',v_order.fee_conversion_complete);
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
  if v_fill.fee_usdt is null then
    raise exception 'exit fee is not yet convertible to USDT; fill remains unapplied';
  end if;
  v_fee := v_fill.fee_usdt;

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
    'realized_pnl',v_net_pnl,'fees',v_entry_fee_alloc+v_fee);
end;
$function$;

revoke all on function public.bybit_demo_worker_pending_orders(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_finalize_entry(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_apply_exit_fill(bigint,text) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_pending_orders(uuid) to service_role;
grant execute on function public.bybit_demo_worker_finalize_entry(uuid) to service_role;
grant execute on function public.bybit_demo_worker_apply_exit_fill(bigint,text) to service_role;
