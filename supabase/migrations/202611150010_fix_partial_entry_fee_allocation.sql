alter table private.bybit_demo_trades
  add column if not exists entry_fee_allocated numeric(30,12) not null default 0
  check (entry_fee_allocated >= 0 and entry_fee_allocated <= entry_fees);

create or replace function public.bybit_demo_worker_apply_exit_fill(
  p_position_id uuid, p_exit_qty numeric, p_exit_price numeric,
  p_exit_fee_usdt numeric, p_exit_order_id text, p_reason text
) returns jsonb
language plpgsql security definer set search_path to ''
as $function$
declare
  v_pos private.bybit_demo_positions%rowtype;
  v_trade private.bybit_demo_trades%rowtype;
  v_entry_fee_alloc numeric;
  v_exit_fee_total numeric;
  v_net_pnl numeric;
  v_closed_qty numeric;
  v_new_qty numeric;
  v_new_avg_exit numeric;
  v_closed boolean;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_position_id is null or p_exit_qty is null or p_exit_qty<=0
     or p_exit_price is null or p_exit_price<=0
     or p_exit_fee_usdt is null or p_exit_fee_usdt<0
     or p_exit_order_id is null or length(p_exit_order_id) not between 1 and 128 then
    raise exception 'invalid exit fill' using errcode='22023';
  end if;
  select * into v_pos from private.bybit_demo_positions
   where id=p_position_id and status='OPEN' for update;
  if v_pos.id is null then raise exception 'open position not found'; end if;
  select * into v_trade from private.bybit_demo_trades
   where run_id=v_pos.run_id and entry_order_id=v_pos.entry_order_id and status='OPEN'
   for update;
  if v_trade.id is null then raise exception 'open trade not found'; end if;
  if p_exit_qty > v_pos.qty then raise exception 'exit quantity exceeds remaining position'; end if;

  v_new_qty := v_pos.qty-p_exit_qty;
  v_closed := v_new_qty <= 0.000000000001;
  v_entry_fee_alloc := case when v_closed
    then greatest(0,v_trade.entry_fees-v_trade.entry_fee_allocated)
    else least(greatest(0,v_trade.entry_fees-v_trade.entry_fee_allocated),
      v_trade.entry_fees*(p_exit_qty/v_trade.qty)) end;
  v_exit_fee_total := v_trade.exit_fees+p_exit_fee_usdt;
  v_net_pnl := (p_exit_price-v_trade.entry_price)*p_exit_qty-v_entry_fee_alloc-p_exit_fee_usdt;
  v_closed_qty := v_trade.qty-v_pos.qty+p_exit_qty;
  v_new_avg_exit := case when v_closed_qty>0 then
    (coalesce(v_trade.exit_price,0)*(v_closed_qty-p_exit_qty)+p_exit_price*p_exit_qty)/v_closed_qty
    else p_exit_price end;

  update private.bybit_demo_trades set
    exit_price=v_new_avg_exit,
    exit_order_id=p_exit_order_id,
    entry_fee_allocated=entry_fee_allocated+v_entry_fee_alloc,
    exit_fees=v_exit_fee_total,
    fees=v_trade.fees+v_entry_fee_alloc+p_exit_fee_usdt,
    pnl=coalesce(v_trade.pnl,0)+v_net_pnl,
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
    fees_paid=fees_paid+v_entry_fee_alloc+p_exit_fee_usdt,
    updated_at=now()
   where id=v_pos.run_id;

  return jsonb_build_object('closed',v_closed,'remaining_qty',case when v_closed then 0 else v_new_qty end,
    'realized_pnl',v_net_pnl,'fees',v_entry_fee_alloc+p_exit_fee_usdt);
end;
$function$;

revoke all on function public.bybit_demo_worker_apply_exit_fill(uuid,numeric,numeric,numeric,text,text) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_apply_exit_fill(uuid,numeric,numeric,numeric,text,text) to service_role;
