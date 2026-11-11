-- GOD OF TRADING 2.0 multi-user demo ledger foundation.
-- Applied to Supabase project GOD-OF-TRADING on 2026-11-11.
-- Client users can read only their own wallet/trades; trusted server logic owns writes.

create table if not exists public.demo_wallets (
  user_id uuid primary key references auth.users(id) on delete cascade,
  balance numeric(14,2) not null default 20000.00 check (balance >= 0),
  currency text not null default 'RUB' check (currency = 'RUB'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.demo_trades (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  symbol text not null,
  direction text not null check (direction in ('UP','DOWN')),
  stake numeric(14,2) not null check (stake > 0),
  expiry_seconds integer not null check (expiry_seconds in (180,240,300)),
  entry_price numeric(20,10) not null check (entry_price > 0),
  exit_price numeric(20,10),
  status text not null default 'OPEN' check (status in ('OPEN','WIN','LOSS','DRAW','CANCELLED')),
  pnl numeric(14,2),
  opened_at timestamptz not null default now(),
  expires_at timestamptz not null,
  closed_at timestamptz,
  quote_source text,
  quote_time timestamptz,
  created_at timestamptz not null default now(),
  constraint demo_trade_expiry_after_open check (expires_at > opened_at)
);

create index if not exists demo_trades_user_opened_idx on public.demo_trades(user_id, opened_at desc);
create index if not exists demo_trades_open_expiry_idx on public.demo_trades(status, expires_at) where status = 'OPEN';

alter table public.demo_wallets enable row level security;
alter table public.demo_trades enable row level security;

drop policy if exists "Users can view own demo wallet" on public.demo_wallets;
create policy "Users can view own demo wallet" on public.demo_wallets for select to authenticated using (auth.uid() = user_id);

drop policy if exists "Users can view own demo trades" on public.demo_trades;
create policy "Users can view own demo trades" on public.demo_trades for select to authenticated using (auth.uid() = user_id);

create or replace function public.create_demo_wallet_for_new_user()
returns trigger language plpgsql security definer set search_path = ''
as $$
begin
  insert into public.demo_wallets(user_id, balance, currency)
  values (new.id, 20000.00, 'RUB')
  on conflict (user_id) do nothing;
  return new;
end;
$$;

drop trigger if exists create_demo_wallet_after_profile on public.profiles;
create trigger create_demo_wallet_after_profile after insert on public.profiles
for each row execute function public.create_demo_wallet_for_new_user();

insert into public.demo_wallets(user_id, balance, currency)
select p.id, 20000.00, 'RUB' from public.profiles p
on conflict (user_id) do nothing;

comment on table public.demo_wallets is 'Per-user isolated virtual RUB balance for GOD OF TRADING 2.0. Client read-only.';
comment on table public.demo_trades is 'Per-user virtual trades. Client read-only; trusted server logic owns writes and settlement.';
