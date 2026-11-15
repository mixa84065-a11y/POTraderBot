-- GOD OF TRADING 2.0: Bybit Demo server execution hardening.
-- Applied to the linked Supabase project on 2026-11-15.
-- Keep one active run per user, including STOPPING runs, and one open position
-- per symbol per run. Server-only RPC validates all position parameters.

drop index if exists private.bybit_demo_one_active_run_per_user;
create unique index bybit_demo_one_active_run_per_user
  on private.bybit_demo_runs(user_id)
  where status in ('READY','RUNNING','STOPPING');

create unique index if not exists bybit_demo_one_open_position_per_symbol
  on private.bybit_demo_positions(run_id,symbol)
  where status='OPEN';

create unique index if not exists bybit_demo_one_open_trade_per_entry_order
  on private.bybit_demo_trades(run_id,entry_order_id)
  where status in ('OPEN','CLOSED');

create or replace function public.bybit_demo_worker_open_position(
 p_run_id uuid,p_symbol text,p_qty numeric,p_entry_price numeric,p_order_id text,
 p_stop numeric,p_target numeric,p_score integer,p_reason text
) returns uuid
language plpgsql security definer set search_path=''
as $function$
declare v_user uuid; v_pos uuid; v_symbols text[];
begin
 if current_setting('request.jwt.claim.role',true) <> 'service_role' then
   raise exception 'forbidden';
 end if;
 if p_run_id is null or p_symbol is null or p_qty is null or p_qty<=0
    or p_entry_price is null or p_entry_price<=0
    or p_stop is null or p_stop<=0 or p_stop>=p_entry_price
    or p_target is null or p_target<=p_entry_price
    or p_order_id is null or length(p_order_id)<1 or length(p_order_id)>64 then
   raise exception 'invalid position parameters' using errcode='22023';
 end if;
 select user_id,symbols into v_user,v_symbols
   from private.bybit_demo_runs
   where id=p_run_id and status='RUNNING'
   for update;
 if v_user is null then raise exception 'run not active'; end if;
 if not (p_symbol=any(v_symbols)) then
   raise exception 'symbol not enabled for this run';
 end if;
 if exists(select 1 from private.bybit_demo_positions
           where run_id=p_run_id and symbol=p_symbol and status='OPEN') then
   raise exception 'open position already exists for symbol';
 end if;
 insert into private.bybit_demo_positions(
   run_id,user_id,symbol,qty,entry_price,entry_order_id,stop_price,target_price
 ) values (
   p_run_id,v_user,p_symbol,p_qty,p_entry_price,p_order_id,p_stop,p_target
 ) returning id into v_pos;
 insert into private.bybit_demo_trades(
   run_id,user_id,symbol,side,qty,entry_price,entry_order_id,strategy_score,entry_reason
 ) values (
   p_run_id,v_user,p_symbol,'BUY',p_qty,p_entry_price,p_order_id,p_score,left(p_reason,500)
 );
 return v_pos;
end
$function$;
