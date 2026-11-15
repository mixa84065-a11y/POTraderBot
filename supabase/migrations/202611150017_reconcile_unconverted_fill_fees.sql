-- Allow a previously unpriced third-token fee to be repaired exactly once.
create or replace function public.bybit_demo_worker_record_fill(
  p_order_id uuid, p_exec_id text, p_qty numeric, p_price numeric,
  p_value_usdt numeric, p_fee_amount numeric, p_fee_currency text,
  p_fee_usdt numeric, p_executed_at timestamptz, p_order_status text
) returns jsonb
language plpgsql security definer set search_path to ''
as $function$
declare
  v_order private.bybit_demo_orders%rowtype;
  v_fill private.bybit_demo_fills%rowtype;
  v_inserted integer;
  v_fee_delta numeric := 0;
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
  ) on conflict(order_id,exec_id) do nothing returning id into v_fill.id;
  get diagnostics v_inserted = row_count;

  if v_inserted=1 then
    v_fee_delta := coalesce(p_fee_usdt,0);
    update private.bybit_demo_orders set
      filled_qty=filled_qty+p_qty,filled_value=filled_value+p_value_usdt,
      fee_usdt=fee_usdt+v_fee_delta,
      fee_conversion_complete=fee_conversion_complete and p_fee_usdt is not null,
      status=case when status in ('FILLED','CANCELLED') then status else p_order_status end,
      last_reconciled_at=now(),updated_at=now()
     where id=p_order_id;
  else
    select * into v_fill from private.bybit_demo_fills
     where order_id=p_order_id and exec_id=p_exec_id for update;
    if v_fill.fee_usdt is null and p_fee_usdt is not null then
      v_fee_delta := p_fee_usdt;
      update private.bybit_demo_fills set fee_usdt=p_fee_usdt where id=v_fill.id;
      update private.bybit_demo_orders set fee_usdt=fee_usdt+v_fee_delta,
        last_reconciled_at=now(),updated_at=now() where id=p_order_id;
    elsif v_fill.fee_usdt is not null and p_fee_usdt is not null
          and abs(v_fill.fee_usdt-p_fee_usdt)>0.000000001 then
      raise exception 'fee conversion for execution is immutable once resolved';
    end if;
    update private.bybit_demo_orders set
      status=case when status in ('FILLED','CANCELLED','REJECTED') then status else p_order_status end,
      last_reconciled_at=now(),updated_at=now()
     where id=p_order_id;
  end if;

  update private.bybit_demo_orders o set fee_conversion_complete = not exists (
    select 1 from private.bybit_demo_fills f where f.order_id=o.id and f.fee_usdt is null
  ) where o.id=p_order_id;

  select * into v_order from private.bybit_demo_orders where id=p_order_id;
  if v_fill.id is null then
    select id into v_fill.id from private.bybit_demo_fills
     where order_id=p_order_id and exec_id=p_exec_id;
  end if;
  return jsonb_build_object('fill_id',v_fill.id,'inserted',v_inserted=1,
    'fee_repaired',v_fee_delta>0,'filled_qty',v_order.filled_qty,
    'filled_value_usdt',v_order.filled_value,'fee_usdt',v_order.fee_usdt,
    'fee_conversion_complete',v_order.fee_conversion_complete,'status',v_order.status);
end;
$function$;

revoke all on function public.bybit_demo_worker_record_fill(uuid,text,numeric,numeric,numeric,numeric,text,numeric,timestamptz,text) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_record_fill(uuid,text,numeric,numeric,numeric,numeric,text,numeric,timestamptz,text) to service_role;
