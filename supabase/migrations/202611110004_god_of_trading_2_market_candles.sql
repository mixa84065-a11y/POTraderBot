-- Server-side one-minute OHLC candles derived only from ingested market ticks.
create table if not exists public.market_candles (
  symbol text not null,
  interval_seconds integer not null check (interval_seconds = 60),
  open_time timestamptz not null,
  open numeric(20,10) not null check (open > 0),
  high numeric(20,10) not null check (high > 0),
  low numeric(20,10) not null check (low > 0),
  close numeric(20,10) not null check (close > 0),
  tick_count integer not null default 0 check (tick_count >= 0),
  source text not null,
  updated_at timestamptz not null default now(),
  primary key(symbol, interval_seconds, open_time, source)
);
create index if not exists market_candles_recent_idx
  on public.market_candles(symbol, interval_seconds, open_time desc);
alter table public.market_candles enable row level security;
revoke all on public.market_candles from anon, authenticated;
grant select, insert, update, delete on public.market_candles to service_role;

create or replace function public.market_ingest_quotes(p_quotes jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_count integer;
  v_candles integer;
begin
  if coalesce(auth.role(), '') <> 'service_role' then
    raise exception 'Недостаточно прав' using errcode = '42501';
  end if;
  if p_quotes is null or jsonb_typeof(p_quotes) <> 'array' then
    raise exception 'Ожидался массив котировок' using errcode = '22023';
  end if;
  v_count := jsonb_array_length(p_quotes);
  if v_count < 1 or v_count > 500 then
    raise exception 'Размер пакета котировок должен быть от 1 до 500' using errcode = '22023';
  end if;

  if exists (
    select 1 from jsonb_to_recordset(p_quotes) as x(symbol text, price numeric, source text, observed_at timestamptz)
    where coalesce(trim(symbol),'') = '' or coalesce(trim(source),'') = ''
       or price is null or price <= 0 or observed_at is null
       or observed_at > clock_timestamp() + interval '5 seconds'
       or observed_at < clock_timestamp() - interval '10 minutes'
  ) then
    raise exception 'Пакет содержит некорректные или слишком старые котировки' using errcode = '22023';
  end if;

  insert into public.market_quotes(symbol,price,source,observed_at)
  select upper(trim(x.symbol)),x.price,trim(x.source),x.observed_at
  from jsonb_to_recordset(p_quotes) as x(symbol text, price numeric, source text, observed_at timestamptz)
  on conflict(symbol,source,observed_at) do nothing;

  with touched as (
    select distinct upper(trim(x.symbol)) as symbol, date_trunc('minute',x.observed_at) as open_time, trim(x.source) as source
    from jsonb_to_recordset(p_quotes) as x(symbol text, price numeric, source text, observed_at timestamptz)
  ), agg as (
    select q.symbol,60 as interval_seconds,date_trunc('minute',q.observed_at) as open_time,
      (array_agg(q.price order by q.observed_at asc))[1] as candle_open,
      max(q.price) as candle_high,min(q.price) as candle_low,
      (array_agg(q.price order by q.observed_at desc))[1] as candle_close,
      count(*)::integer as tick_count,q.source
    from public.market_quotes q
    join touched t on t.symbol=q.symbol and t.open_time=date_trunc('minute',q.observed_at) and t.source=q.source
    group by q.symbol,date_trunc('minute',q.observed_at),q.source
  )
  insert into public.market_candles(symbol,interval_seconds,open_time,open,high,low,close,tick_count,source,updated_at)
  select symbol,interval_seconds,open_time,candle_open,candle_high,candle_low,candle_close,tick_count,source,now()
  from agg
  on conflict(symbol,interval_seconds,open_time,source) do update set
    open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close,
    tick_count=excluded.tick_count, updated_at=now();

  get diagnostics v_candles = row_count;
  return jsonb_build_object('accepted',v_count,'candles_updated',v_candles);
end;
$$;
revoke all on function public.market_ingest_quotes(jsonb) from public, anon, authenticated;
grant execute on function public.market_ingest_quotes(jsonb) to service_role;
comment on table public.market_candles is 'One-minute OHLC candles aggregated from timestamped server-ingested ticks; no synthetic prices.';
