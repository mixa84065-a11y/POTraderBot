-- Return durable fill identifiers and expose only unapplied EXIT fills to the worker.
create or replace function public.bybit_demo_worker_record_fill(
  p_order_id uuid, p_exec_id text, p_qty numeric, p_price numeric,
  p_value_usdt numeric, p_fee_amount numeric, p_fee_currency text,
  p_fee_usdt numeric, p_executed_at timestamptz, p_order_status text
) returns jsonb
language plpgsql security definer set search_path to ''
as $function$
declare
  v_order private.bybit_demo_orders%rowtype;
  v_fill_id bigint;
  v_inserted integer;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_order_id is null or p_exec_id is null or length(p_exec_id) not between 1 and 128
     or p_qty is null or p_qty<=0 or p_price is null or p_price<=0
     or p_value_usdt is null or p_value_usdt<=0
     or p_fee_amount is null or p_fee_amount<0
     or p_fee_currency is null or length(p_fee_currency)>20
     or (p_fee_usdt is not null and p_fee_usdt<0)
     or p_executed_at is null
     or p_order_status not in ('PARTIALLY_FILLED','FILLED','CANCELLED','UNKNOWN') then
    raise exception 'invalid execution fill' using errcode='22023';
  end if;
  select * into v_order from private.bybit_demo_orders where id=p_order_id for update;
  if v_order.id is null then raise exception 'order intent not found'; end if;
  if v_order.status='REJECTED' then raise exception 'exchange reported a fill for a rejected order'; end if;

  insert into private.bybit_demo_fills(
    order_id,run_id,user_id,exec_id,symbol,side,qty,price,value_usdt,
    fee_amount,fee_currency,fee_usdt,executed_at
  ) values (
    v_order.id,v_order.run_id,v_order.user_id,p_exec_id,v_order.symbol,v_order.side,
    p_qty,p_price,p_value_usdt,p_fee_amount,p_fee_currency,p_fee_usdt,p_executed_at
  ) on conflict(order_id,exec_id) do nothing returning id into v_fill_id;
  get diagnostics v_inserted = row_count;

  if v_inserted=1 then
    update private.bybit_demo_orders set
      filled_qty=filled_qty+p_qty,filled_value=filled_value+p_value_usdt,
      fee_usdt=fee_usdt+coalesce(p_fee_usdt,0),
      fee_conversion_complete=fee_conversion_complete and p_fee_usdt is not null,
      status=case when status in ('FILLED','CANCELLED') then status else p_order_status end,
      last_reconciled_at=now(),updated_at=now()
     where id=p_order_id;
  else
    select id into v_fill_id from private.bybit_demo_fills
     where order_id=p_order_id and exec_id=p_exec_id;
    update private.bybit_demo_orders set
      status=case when status in ('FILLED','CANCELLED','REJECTED') then status else p_order_status end,
      last_reconciled_at=now(),updated_at=now()
     where id=p_order_id;
  end if;

  select * into v_order from private.bybit_demo_orders where id=p_order_id;
  return jsonb_build_object('fill_id',v_fill_id,'inserted',v_inserted=1,
    'filled_qty',v_order.filled_qty,'filled_value_usdt',v_order.filled_value,
    'fee_usdt',v_order.fee_usdt,'fee_conversion_complete',v_order.fee_conversion_complete,
    'status',v_order.status);
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
     where f.run_id=p_run_id and o.purpose='EXIT' and o.position_id is not null
       and f.applied_at is null and f.fee_usdt is not null
     order by f.executed_at,f.id;
end;
$function$;

revoke all on function public.bybit_demo_worker_record_fill(uuid,text,numeric,numeric,numeric,numeric,text,numeric,timestamptz,text) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_pending_exit_fills(uuid) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_record_fill(uuid,text,numeric,numeric,numeric,numeric,text,numeric,timestamptz,text) to service_role;
grant execute on function public.bybit_demo_worker_pending_exit_fills(uuid) to service_role;
