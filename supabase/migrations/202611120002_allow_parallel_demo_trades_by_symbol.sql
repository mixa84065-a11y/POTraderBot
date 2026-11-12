-- Permit concurrent demo positions on different symbols while preventing
-- duplicate OPEN positions for the same user and symbol.
create or replace function public.demo_open_trade(
  p_user_id uuid,
  p_symbol text,
  p_direction text,
  p_stake numeric,
  p_expiry_seconds integer
) returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_cfg public.demo_market_config%rowtype;
  v_quote public.market_quotes%rowtype;
  v_wallet public.demo_wallets%rowtype;
  v_trade public.demo_trades%rowtype;
  v_now timestamptz := clock_timestamp();
begin
  if p_user_id is null or p_symbol is null or p_direction is null
     or p_direction not in ('UP','DOWN') or p_stake is null
     or p_stake <= 0 or p_stake > 20000 or p_expiry_seconds is null
     or p_expiry_seconds not in (180,240,300) then
    raise exception 'Некорректные параметры демо-сделки' using errcode = '22023';
  end if;

  select * into v_cfg from public.demo_market_config
   where symbol=p_symbol and enabled=true;
  if not found then
    raise exception 'Инструмент пока не включён для демо-торговли' using errcode='55000';
  end if;

  select * into v_quote from public.market_quotes
   where symbol=p_symbol order by observed_at desc limit 1;
  if not found or v_quote.observed_at>v_now
     or v_now-v_quote.observed_at>make_interval(secs=>v_cfg.max_quote_age_seconds) then
    raise exception 'Котировка отсутствует или устарела; сделка не открыта' using errcode='55000';
  end if;

  -- Lock the user's wallet first. This serializes their open requests, so
  -- the same symbol cannot be opened twice concurrently by this account.
  select * into v_wallet from public.demo_wallets
   where user_id=p_user_id for update;
  if not found then
    raise exception 'Виртуальный счёт пользователя не найден' using errcode='P0002';
  end if;

  if exists (
    select 1 from public.demo_trades
     where user_id=p_user_id and symbol=p_symbol and status='OPEN'
  ) then
    raise exception 'По этой валютной паре уже есть открытая сделка' using errcode='23505';
  end if;

  if v_wallet.balance<p_stake then
    raise exception 'Недостаточно средств на виртуальном счёте' using errcode='22003';
  end if;

  update public.demo_wallets
     set balance=balance-p_stake,updated_at=v_now
   where user_id=p_user_id;

  insert into public.demo_trades(
    user_id,symbol,direction,stake,expiry_seconds,entry_price,
    status,opened_at,expires_at,quote_source,quote_time
  ) values (
    p_user_id,p_symbol,p_direction,p_stake,p_expiry_seconds,v_quote.price,
    'OPEN',v_now,v_now+make_interval(secs=>p_expiry_seconds),
    v_quote.source,v_quote.observed_at
  ) returning * into v_trade;

  return jsonb_build_object(
    'trade_id',v_trade.id,'symbol',v_trade.symbol,'direction',v_trade.direction,
    'stake',v_trade.stake,'expiry_seconds',v_trade.expiry_seconds,
    'entry_price',v_trade.entry_price,'opened_at',v_trade.opened_at,
    'expires_at',v_trade.expires_at,'balance_after',v_wallet.balance-p_stake,
    'quote_source',v_quote.source,'quote_time',v_quote.observed_at
  );
end;
$$;

revoke all on function public.demo_open_trade(uuid,text,text,numeric,integer) from public,anon,authenticated;
grant execute on function public.demo_open_trade(uuid,text,text,numeric,integer) to service_role;
