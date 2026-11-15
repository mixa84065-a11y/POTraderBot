-- Durable, idempotent order and execution ledger for the Bybit Demo worker.
create table if not exists private.bybit_demo_orders (
  id uuid primary key default gen_random_uuid(),
  run_id uuid not null references private.bybit_demo_runs(id),
  user_id uuid not null references auth.users(id),
  position_id uuid null references private.bybit_demo_positions(id),
  symbol text not null check (symbol ~ '^[A-Z0-9]{3,20}$'),
  side text not null check (side in ('Buy','Sell')),
  purpose text not null check (purpose in ('ENTRY','EXIT')),
  order_link_id text not null check (length(order_link_id) between 1 and 36),
  exchange_order_id text null,
  requested_qty numeric(30,12) not null check (requested_qty > 0),
  qty_unit text not null check (qty_unit in ('baseCoin','quoteCoin')),
  status text not null default 'INTENT'
    check (status in ('INTENT','SUBMITTED','PARTIALLY_FILLED','FILLED','CANCELLED','REJECTED','UNKNOWN')),
  filled_qty numeric(30,12) not null default 0 check (filled_qty >= 0),
  filled_value numeric(30,12) not null default 0 check (filled_value >= 0),
  fee_usdt numeric(30,12) not null default 0 check (fee_usdt >= 0),
  fee_conversion_complete boolean not null default true,
  last_reconciled_at timestamptz null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(run_id, order_link_id)
);

create unique index if not exists bybit_demo_orders_exchange_order_uidx
  on private.bybit_demo_orders(run_id, exchange_order_id)
  where exchange_order_id is not null;
create index if not exists bybit_demo_orders_pending_idx
  on private.bybit_demo_orders(run_id, status, created_at)
  where status in ('INTENT','SUBMITTED','PARTIALLY_FILLED','UNKNOWN');

create table if not exists private.bybit_demo_fills (
  id bigint generated always as identity primary key,
  order_id uuid not null references private.bybit_demo_orders(id),
  run_id uuid not null references private.bybit_demo_runs(id),
  user_id uuid not null references auth.users(id),
  exec_id text not null check (length(exec_id) between 1 and 128),
  symbol text not null,
  side text not null check (side in ('Buy','Sell')),
  qty numeric(30,12) not null check (qty > 0),
  price numeric(30,12) not null check (price > 0),
  value_usdt numeric(30,12) not null check (value_usdt > 0),
  fee_amount numeric(30,12) not null default 0 check (fee_amount >= 0),
  fee_currency text not null,
  fee_usdt numeric(30,12) null check (fee_usdt >= 0),
  executed_at timestamptz not null,
  created_at timestamptz not null default now(),
  unique(order_id, exec_id)
);
create index if not exists bybit_demo_fills_run_time_idx
  on private.bybit_demo_fills(run_id, executed_at desc);

alter table private.bybit_demo_orders enable row level security;
alter table private.bybit_demo_fills enable row level security;
revoke all on private.bybit_demo_orders, private.bybit_demo_fills from public, anon, authenticated;
grant select, insert, update, delete on private.bybit_demo_orders, private.bybit_demo_fills to service_role;
grant usage, select on sequence private.bybit_demo_fills_id_seq to service_role;

create or replace function public.bybit_demo_worker_create_order_intent(
  p_run_id uuid, p_symbol text, p_side text, p_purpose text,
  p_order_link_id text, p_requested_qty numeric, p_qty_unit text,
  p_position_id uuid default null
) returns uuid
language plpgsql security definer set search_path to ''
as $function$
declare v_user uuid; v_id uuid; v_existing private.bybit_demo_orders%rowtype;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_run_id is null or p_symbol !~ '^[A-Z0-9]{3,20}$'
     or p_side not in ('Buy','Sell') or p_purpose not in ('ENTRY','EXIT')
     or p_order_link_id is null or length(p_order_link_id) not between 1 and 36
     or p_requested_qty is null or p_requested_qty <= 0
     or p_qty_unit not in ('baseCoin','quoteCoin') then
    raise exception 'invalid order intent' using errcode='22023';
  end if;
  select user_id into v_user from private.bybit_demo_runs
   where id=p_run_id and (status='RUNNING' or (status='STOPPING' and p_purpose='EXIT'))
   for update;
  if v_user is null then raise exception 'run not active for requested order purpose'; end if;
  if p_position_id is not null and not exists (
    select 1 from private.bybit_demo_positions
     where id=p_position_id and run_id=p_run_id and user_id=v_user and status='OPEN'
  ) then raise exception 'position not open for this run'; end if;

  insert into private.bybit_demo_orders(
    run_id,user_id,position_id,symbol,side,purpose,order_link_id,requested_qty,qty_unit
  ) values (
    p_run_id,v_user,p_position_id,p_symbol,p_side,p_purpose,p_order_link_id,p_requested_qty,p_qty_unit
  ) on conflict(run_id,order_link_id) do nothing returning id into v_id;

  if v_id is null then
    select * into v_existing from private.bybit_demo_orders
     where run_id=p_run_id and order_link_id=p_order_link_id for update;
    if v_existing.symbol<>p_symbol or v_existing.side<>p_side or v_existing.purpose<>p_purpose
       or v_existing.requested_qty<>p_requested_qty or v_existing.qty_unit<>p_qty_unit
       or v_existing.position_id is distinct from p_position_id then
      raise exception 'orderLinkId reused with different payload' using errcode='23505';
    end if;
    return v_existing.id;
  end if;
  return v_id;
end;
$function$;

create or replace function public.bybit_demo_worker_set_order_state(
  p_order_id uuid, p_exchange_order_id text, p_status text
) returns void
language plpgsql security definer set search_path to ''
as $function$
declare v_status text;
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_order_id is null or p_status not in ('SUBMITTED','PARTIALLY_FILLED','FILLED','CANCELLED','REJECTED','UNKNOWN')
     or (p_exchange_order_id is not null and length(p_exchange_order_id)>128) then
    raise exception 'invalid order state' using errcode='22023';
  end if;
  select status into v_status from private.bybit_demo_orders where id=p_order_id for update;
  if v_status is null then raise exception 'order intent not found'; end if;
  if v_status in ('FILLED','CANCELLED','REJECTED') and p_status not in (v_status) then
    raise exception 'terminal order state cannot be downgraded';
  end if;
  update private.bybit_demo_orders set
    exchange_order_id=coalesce(exchange_order_id,p_exchange_order_id),
    status=p_status,last_reconciled_at=now(),updated_at=now()
   where id=p_order_id;
end;
$function$;

create or replace function public.bybit_demo_worker_record_fill(
  p_order_id uuid, p_exec_id text, p_qty numeric, p_price numeric,
  p_value_usdt numeric, p_fee_amount numeric, p_fee_currency text,
  p_fee_usdt numeric, p_executed_at timestamptz, p_order_status text
) returns jsonb
language plpgsql security definer set search_path to ''
as $function$
declare v_order private.bybit_demo_orders%rowtype; v_inserted integer;
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
  if v_order.status in ('REJECTED','CANCELLED') and p_order_status='PARTIALLY_FILLED' then
    raise exception 'cannot add a partial fill to a terminal order';
  end if;

  insert into private.bybit_demo_fills(
    order_id,run_id,user_id,exec_id,symbol,side,qty,price,value_usdt,
    fee_amount,fee_currency,fee_usdt,executed_at
  ) values (
    v_order.id,v_order.run_id,v_order.user_id,p_exec_id,v_order.symbol,v_order.side,
    p_qty,p_price,p_value_usdt,p_fee_amount,p_fee_currency,p_fee_usdt,p_executed_at
  ) on conflict(order_id,exec_id) do nothing;
  get diagnostics v_inserted = row_count;

  if v_inserted=1 then
    update private.bybit_demo_orders set
      filled_qty=filled_qty+p_qty,
      filled_value=filled_value+p_value_usdt,
      fee_usdt=fee_usdt+coalesce(p_fee_usdt,0),
      fee_conversion_complete=fee_conversion_complete and p_fee_usdt is not null,
      status=p_order_status,last_reconciled_at=now(),updated_at=now()
     where id=p_order_id;
  else
    update private.bybit_demo_orders set
      status=case when status='FILLED' then status else p_order_status end,
      last_reconciled_at=now(),updated_at=now()
     where id=p_order_id;
  end if;

  select * into v_order from private.bybit_demo_orders where id=p_order_id;
  return jsonb_build_object(
    'inserted',v_inserted=1,
    'filled_qty',v_order.filled_qty,
    'filled_value_usdt',v_order.filled_value,
    'fee_usdt',v_order.fee_usdt,
    'fee_conversion_complete',v_order.fee_conversion_complete,
    'status',v_order.status
  );
end;
$function$;

revoke all on function public.bybit_demo_worker_create_order_intent(uuid,text,text,text,text,numeric,text,uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_set_order_state(uuid,text,text) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_record_fill(uuid,text,numeric,numeric,numeric,numeric,text,numeric,timestamptz,text) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_create_order_intent(uuid,text,text,text,text,numeric,text,uuid) to service_role;
grant execute on function public.bybit_demo_worker_set_order_state(uuid,text,text) to service_role;
grant execute on function public.bybit_demo_worker_record_fill(uuid,text,numeric,numeric,numeric,numeric,text,numeric,timestamptz,text) to service_role;
