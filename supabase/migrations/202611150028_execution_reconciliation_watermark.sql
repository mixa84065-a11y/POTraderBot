-- Distinguish an exchange order's terminal status from a fully reconciled
-- execution ledger. New status transitions and new fills invalidate the watermark.
alter table private.bybit_demo_orders
  add column if not exists executions_complete boolean not null default false;

create or replace function private.bybit_demo_reset_order_execution_watermark()
returns trigger
language plpgsql set search_path to ''
as $function$
begin
  if new.status is distinct from old.status then
    new.executions_complete := false;
  end if;
  return new;
end;
$function$;

drop trigger if exists bybit_demo_order_status_resets_execution_watermark
  on private.bybit_demo_orders;
create trigger bybit_demo_order_status_resets_execution_watermark
before update of status on private.bybit_demo_orders
for each row execute function private.bybit_demo_reset_order_execution_watermark();

create or replace function private.bybit_demo_fill_resets_execution_watermark()
returns trigger
language plpgsql security definer set search_path to ''
as $function$
begin
  update private.bybit_demo_orders
     set executions_complete=false,updated_at=now()
   where id=new.order_id;
  return new;
end;
$function$;

drop trigger if exists bybit_demo_fill_resets_execution_watermark
  on private.bybit_demo_fills;
create trigger bybit_demo_fill_resets_execution_watermark
after insert on private.bybit_demo_fills
for each row execute function private.bybit_demo_fill_resets_execution_watermark();

create or replace function public.bybit_demo_worker_mark_executions_complete(p_order_id uuid)
returns boolean
language plpgsql security definer set search_path to ''
as $function$
begin
  if not public.bybit_demo_worker_is_service_role() then raise exception 'forbidden'; end if;
  if p_order_id is null then raise exception 'invalid order id' using errcode='22023'; end if;
  update private.bybit_demo_orders set
    executions_complete=true,last_reconciled_at=now(),updated_at=now()
   where id=p_order_id;
  return found;
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
         or (o.status in ('FILLED','CANCELLED') and not o.executions_complete)
         or (o.purpose='ENTRY' and o.position_id is null and o.status='FILLED')
         or (o.purpose='ENTRY' and o.position_id is null and o.filled_qty>0
             and o.status='CANCELLED')
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
         or (o.status='FILLED' and not o.executions_complete and exists (
               select 1 from private.bybit_demo_positions p
                where p.id=o.position_id and p.status='OPEN'
             ))
         or exists(select 1 from private.bybit_demo_fills f
                    where f.order_id=o.id and f.applied_at is null)
       )
     order by o.created_at;
end;
$function$;

revoke all on function public.bybit_demo_worker_mark_executions_complete(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_pending_orders(uuid) from public,anon,authenticated;
revoke all on function public.bybit_demo_worker_pending_protections(uuid) from public,anon,authenticated;
grant execute on function public.bybit_demo_worker_mark_executions_complete(uuid) to service_role;
grant execute on function public.bybit_demo_worker_pending_orders(uuid) to service_role;
grant execute on function public.bybit_demo_worker_pending_protections(uuid) to service_role;
