-- Secure server-owned quote and demo-trade lifecycle for GOD OF TRADING 2.0.
-- No client can write quotes, balances, or trade outcomes.

create table if not exists public.demo_market_config (
  symbol text primary key,
  enabled boolean not null default false,
  payout_rate numeric(6,5) not null check (payout_rate >= 0 and payout_rate <= 1),
  max_quote_age_seconds integer not null default 15 check (max_quote_age_seconds between 1 and 120),
  expiry_grace_seconds integer not null default 30 check (expiry_grace_seconds between 1 and 120),
  updated_at timestamptz not null default now()
);

create table if not exists public.market_quotes (
  id bigint generated always as identity primary key,
  symbol text not null,
  price numeric(20,10) not null check (price > 0),
  source text not null,
  observed_at timestamptz not null,
  received_at timestamptz not null default now(),
  unique(symbol, source, observed_at)
);
create index if not exists market_quotes_latest_idx
  on public.market_quotes(symbol, observed_at desc);

alter table public.demo_market_config enable row level security;
alter table public.market_quotes enable row level security;
revoke all on public.demo_market_config from anon, authenticated;
revoke all on public.market_quotes from anon, authenticated;
grant select, insert, update, delete on public.demo_market_config to service_role;
grant select, insert, update, delete on public.market_quotes to service_role;
grant usage, select on sequence public.market_quotes_id_seq to service_role;

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
  if p_user_id is null or p_symbol is null or p_direction not in ('UP','DOWN')
     or p_stake is null or p_stake <= 0 or p_stake > 20000
     or p_expiry_seconds not in (180,240,300) then
    raise exception 'Некорректные параметры демо-сделки' using errcode = '22023';
  end if;

  select * into v_cfg from public.demo_market_config
   where symbol = p_symbol and enabled = true;
  if not found then
    raise exception 'Инструмент пока не включён для демо-торговли' using errcode = '55000';
  end if;

  select * into v_quote from public.market_quotes
   where symbol = p_symbol
   order by observed_at desc limit 1;
  if not found or v_quote.observed_at > v_now
     or v_now - v_quote.observed_at > make_interval(secs => v_cfg.max_quote_age_seconds) then
    raise exception 'Котировка отсутствует или устарела; сделка не открыта' using errcode = '55000';
  end if;

  select * into v_wallet from public.demo_wallets
   where user_id = p_user_id for update;
  if not found then
    raise exception 'Виртуальный счёт пользователя не найден' using errcode = 'P0002';
  end if;
  if v_wallet.balance < p_stake then
    raise exception 'Недостаточно средств на виртуальном счёте' using errcode = '22003';
  end if;

  update public.demo_wallets
     set balance = balance - p_stake, updated_at = v_now
   where user_id = p_user_id;

  insert into public.demo_trades(
    user_id, symbol, direction, stake, expiry_seconds, entry_price,
    status, opened_at, expires_at, quote_source, quote_time
  ) values (
    p_user_id, p_symbol, p_direction, p_stake, p_expiry_seconds, v_quote.price,
    'OPEN', v_now, v_now + make_interval(secs => p_expiry_seconds),
    v_quote.source, v_quote.observed_at
  ) returning * into v_trade;

  return jsonb_build_object(
    'trade_id', v_trade.id,
    'symbol', v_trade.symbol,
    'direction', v_trade.direction,
    'stake', v_trade.stake,
    'expiry_seconds', v_trade.expiry_seconds,
    'entry_price', v_trade.entry_price,
    'opened_at', v_trade.opened_at,
    'expires_at', v_trade.expires_at,
    'balance_after', v_wallet.balance - p_stake,
    'quote_source', v_quote.source,
    'quote_time', v_quote.observed_at
  );
end;
$$;

create or replace function public.demo_settle_due_trades(p_limit integer default 100)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
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
     order by expires_at
     for update skip locked
     limit p_limit
  loop
    select * into v_cfg from public.demo_market_config where symbol = v_trade.symbol;
    select * into v_quote from public.market_quotes
     where symbol = v_trade.symbol
       and observed_at >= v_trade.expires_at
       and observed_at <= least(v_now, v_trade.expires_at + make_interval(secs => coalesce(v_cfg.expiry_grace_seconds,30)))
     order by observed_at asc limit 1;

    if not found then
      if v_now > v_trade.expires_at + make_interval(secs => coalesce(v_cfg.expiry_grace_seconds,30)) then
        update public.demo_trades
           set status='CANCELLED', pnl=0, closed_at=v_now
         where id=v_trade.id;
        update public.demo_wallets
           set balance=balance+v_trade.stake, updated_at=v_now
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
           closed_at=v_now, quote_source=coalesce(v_quote.source,quote_source),
           quote_time=v_quote.observed_at
     where id=v_trade.id;
    if v_credit > 0 then
      update public.demo_wallets
         set balance=balance+v_credit, updated_at=v_now
       where user_id=v_trade.user_id;
    end if;
    v_settled := v_settled + 1;
  end loop;

  return jsonb_build_object('settled',v_settled,'cancelled',v_cancelled);
end;
$$;

revoke all on function public.demo_open_trade(uuid,text,text,numeric,integer) from public, anon, authenticated;
revoke all on function public.demo_settle_due_trades(integer) from public, anon, authenticated;
grant execute on function public.demo_open_trade(uuid,text,text,numeric,integer) to service_role;
grant execute on function public.demo_settle_due_trades(integer) to service_role;

comment on table public.demo_market_config is 'Server-only symbol settings. A symbol remains disabled until its feed and payout simulation are explicitly configured.';
comment on table public.market_quotes is 'Server-ingested timestamped market quotes. Client writes and reads are disabled.';
