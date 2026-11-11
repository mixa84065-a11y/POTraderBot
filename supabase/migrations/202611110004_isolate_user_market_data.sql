create index if not exists market_quotes_user_latest_idx
  on public.market_quotes (symbol, source, observed_at desc);
create index if not exists market_candles_user_recent_idx
  on public.market_candles (symbol, interval_seconds, source, open_time desc);

create or replace function public.demo_open_trade(
  p_user_id uuid,
  p_symbol text,
  p_direction text,
  p_stake numeric,
  p_expiry_seconds integer
) returns jsonb
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
  v_cfg public.demo_market_config%rowtype;
  v_quote public.market_quotes%rowtype;
  v_wallet public.demo_wallets%rowtype;
  v_trade public.demo_trades%rowtype;
  v_now timestamptz := clock_timestamp();
  v_source text;
begin
  if p_user_id is null or p_symbol is null or p_direction is null
     or p_direction not in ('UP','DOWN') or p_stake is null
     or p_stake <= 0 or p_stake > 20000 or p_expiry_seconds is null
     or p_expiry_seconds not in (180,240,300) then
    raise exception 'Некорректные параметры демо-сделки' using errcode = '22023';
  end if;

  v_source := 'Twelve Data:' || p_user_id::text;

  select * into v_cfg from public.demo_market_config
   where symbol = upper(p_symbol) and enabled = true;
  if not found then
    raise exception 'Инструмент пока не включён для демо-торговли' using errcode='55000';
  end if;

  select * into v_quote from public.market_quotes
   where symbol = upper(p_symbol) and source = v_source
   order by observed_at desc limit 1;
  if not found or v_quote.observed_at > v_now
     or v_now - v_quote.observed_at > make_interval(secs => v_cfg.max_quote_age_seconds) then
    raise exception 'Котировка отсутствует или устарела; сделка не открыта' using errcode='55000';
  end if;

  select * into v_wallet from public.demo_wallets
   where user_id = p_user_id for update;
  if not found then
    raise exception 'Виртуальный счёт пользователя не найден' using errcode='P0002';
  end if;
  if v_wallet.balance < p_stake then
    raise exception 'Недостаточно средств на виртуальном счёте' using errcode='22003';
  end if;

  update public.demo_wallets set balance = balance - p_stake, updated_at = v_now
   where user_id = p_user_id;

  insert into public.demo_trades(
    user_id,symbol,direction,stake,expiry_seconds,entry_price,status,
    opened_at,expires_at,quote_source,quote_time
  )
  values(
    p_user_id,upper(p_symbol),p_direction,p_stake,p_expiry_seconds,
    v_quote.price,'OPEN',v_now,
    v_now + make_interval(secs => p_expiry_seconds),
    v_source,v_quote.observed_at
  )
  returning * into v_trade;

  return jsonb_build_object(
    'trade_id',v_trade.id,'symbol',v_trade.symbol,'direction',v_trade.direction,
    'stake',v_trade.stake,'expiry_seconds',v_trade.expiry_seconds,
    'entry_price',v_trade.entry_price,'opened_at',v_trade.opened_at,
    'expires_at',v_trade.expires_at,'balance_after',v_wallet.balance-p_stake,
    'quote_source',v_trade.quote_source,'quote_time',v_trade.quote_time
  );
end;
$function$;

create or replace function public.demo_settle_due_trades(p_limit integer default 100)
returns jsonb
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
  v_trade public.demo_trades%rowtype;
  v_cfg public.demo_market_config%rowtype;
  v_quote public.market_quotes%rowtype;
  v_status text;
  v_pnl numeric(14,2);
  v_credit numeric(14,2);
  v_settled integer := 0;
  v_cancelled integer := 0;
  v_now timestamptz := clock_timestamp();
begin
  if coalesce(auth.role(), '') <> 'service_role' then
    raise exception 'Недостаточно прав' using errcode = '42501';
  end if;
  if p_limit is null or p_limit < 1 or p_limit > 500 then
    raise exception 'Некорректный лимит обработки' using errcode = '22023';
  end if;

  for v_trade in
    select * from public.demo_trades
     where status = 'OPEN' and expires_at <= v_now
     order by expires_at for update skip locked limit p_limit
  loop
    select * into v_cfg from public.demo_market_config where symbol = v_trade.symbol;

    select * into v_quote from public.market_quotes
     where symbol = v_trade.symbol
       and source = v_trade.quote_source
       and observed_at >= v_trade.expires_at
       and observed_at <= least(
         v_now,
         v_trade.expires_at + make_interval(secs => coalesce(v_cfg.expiry_grace_seconds,30))
       )
     order by observed_at asc limit 1;

    if not found then
      if v_now > v_trade.expires_at
          + make_interval(secs => coalesce(v_cfg.expiry_grace_seconds,30)) then
        update public.demo_trades set status='CANCELLED', pnl=0, closed_at=v_now
         where id=v_trade.id;
        update public.demo_wallets set balance=balance+v_trade.stake, updated_at=v_now
         where user_id=v_trade.user_id;
        v_cancelled := v_cancelled + 1;
      end if;
      continue;
    end if;

    if v_quote.price = v_trade.entry_price then
      v_status := 'DRAW'; v_pnl := 0; v_credit := v_trade.stake;
    elsif (v_trade.direction='UP' and v_quote.price > v_trade.entry_price)
       or (v_trade.direction='DOWN' and v_quote.price < v_trade.entry_price) then
      v_status := 'WIN'; v_pnl := round(v_trade.stake * v_cfg.payout_rate, 2);
      v_credit := v_trade.stake + v_pnl;
    else
      v_status := 'LOSS'; v_pnl := -v_trade.stake; v_credit := 0;
    end if;

    update public.demo_trades
       set status=v_status, exit_price=v_quote.price, pnl=v_pnl,
           closed_at=v_now, quote_source=v_quote.source, quote_time=v_quote.observed_at
     where id=v_trade.id;
    if v_credit > 0 then
      update public.demo_wallets set balance=balance+v_credit, updated_at=v_now
       where user_id=v_trade.user_id;
    end if;
    v_settled := v_settled + 1;
  end loop;

  return jsonb_build_object('settled',v_settled,'cancelled',v_cancelled);
end;
$function$;
