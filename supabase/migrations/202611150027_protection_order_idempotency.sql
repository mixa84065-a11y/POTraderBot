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
    select * into v_existing from private.bybit_demo_orders
     where run_id=p_run_id and order_link_id=p_order_link_id for update;
    if v_existing.id is null or v_existing.purpose<>'PROTECTION'
       or v_existing.position_id is distinct from p_position_id
       or v_existing.symbol<>v_pos.symbol or v_existing.side<>'Sell'
       or v_existing.requested_qty<>p_requested_qty
       or v_existing.qty_unit<>'baseCoin'
       or v_existing.trigger_price is distinct from p_trigger_price then
      raise exception 'protection orderLinkId reused with different payload' using errcode='23505';
    end if;
    if v_existing.status not in ('INTENT','SUBMITTED','PARTIALLY_FILLED','UNKNOWN') then
      raise exception 'protection orderLinkId already belongs to a terminal order; create a new ID';
    end if;
    return v_existing.id;
  end if;
  return v_id;
end;
$function$;
revoke all on function public.bybit_demo_worker_create_protection_intent(uuid,uuid,text,numeric,numeric) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_create_protection_intent(uuid,uuid,text,numeric,numeric) to service_role;
