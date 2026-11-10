-- GOD OF TRADING: private, opt-in, two-way signal sharing.
-- Apply in Supabase SQL Editor; user accounts use email and password.

create extension if not exists pgcrypto;

create table if not exists public.trading_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  created_at timestamptz not null default now()
);

create or replace function public.handle_new_trading_user()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
  insert into public.trading_profiles (user_id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'name', new.email))
  on conflict (user_id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created_trading_profile on auth.users;
create trigger on_auth_user_created_trading_profile
after insert on auth.users
for each row execute procedure public.handle_new_trading_user();

create table if not exists public.signal_connections (
  id uuid primary key default gen_random_uuid(),
  requester_id uuid not null references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted','rejected','blocked')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint signal_connection_not_self check (requester_id <> recipient_id),
  constraint signal_connection_unique unique (requester_id, recipient_id)
);

create table if not exists public.trade_signals (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  connection_id uuid not null references public.signal_connections(id) on delete cascade,
  pair text not null,
  direction text not null check (direction in ('CALL','PUT','WAIT')),
  timeframe text not null default '1m',
  expiry_minutes integer not null check (expiry_minutes between 1 and 60),
  rationale text not null default '',
  source_signal_id uuid references public.trade_signals(id) on delete set null,
  status text not null default 'sent' check (status in ('sent','accepted','rejected','repeated')),
  created_at timestamptz not null default now(),
  responded_at timestamptz,
  constraint trade_signal_not_self check (sender_id <> recipient_id)
);

create index if not exists signal_connections_requester_idx on public.signal_connections(requester_id, status);
create index if not exists signal_connections_recipient_idx on public.signal_connections(recipient_id, status);
create index if not exists trade_signals_recipient_created_idx on public.trade_signals(recipient_id, created_at desc);
create index if not exists trade_signals_sender_created_idx on public.trade_signals(sender_id, created_at desc);

alter table public.trading_profiles enable row level security;
alter table public.signal_connections enable row level security;
alter table public.trade_signals enable row level security;

drop policy if exists "Users read own profile" on public.trading_profiles;
create policy "Users read own profile" on public.trading_profiles
for select to authenticated using (user_id = auth.uid());

drop policy if exists "Users update own profile" on public.trading_profiles;
create policy "Users update own profile" on public.trading_profiles
for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "Users read own connections" on public.signal_connections;
create policy "Users read own connections" on public.signal_connections
for select to authenticated using (requester_id = auth.uid() or recipient_id = auth.uid());

drop policy if exists "Users request connections" on public.signal_connections;
create policy "Users request connections" on public.signal_connections
for insert to authenticated with check (requester_id = auth.uid() and status = 'pending');

drop policy if exists "Recipients respond to connection requests" on public.signal_connections;
create policy "Recipients respond to connection requests" on public.signal_connections
for update to authenticated using (recipient_id = auth.uid())
with check (recipient_id = auth.uid() and status in ('accepted','rejected','blocked'));

drop policy if exists "Users read shared signals" on public.trade_signals;
create policy "Users read shared signals" on public.trade_signals
for select to authenticated using (sender_id = auth.uid() or recipient_id = auth.uid());

drop policy if exists "Send signals to accepted contacts" on public.trade_signals;
create policy "Send signals to accepted contacts" on public.trade_signals
for insert to authenticated with check (
  sender_id = auth.uid()
  and exists (
    select 1 from public.signal_connections c
    where c.id = connection_id
      and c.status = 'accepted'
      and (
        (c.requester_id = auth.uid() and c.recipient_id = trade_signals.recipient_id)
        or (c.recipient_id = auth.uid() and c.requester_id = trade_signals.recipient_id)
      )
  )
);

drop policy if exists "Recipients respond to shared signals" on public.trade_signals;
create policy "Recipients respond to shared signals" on public.trade_signals
for update to authenticated using (recipient_id = auth.uid())
with check (recipient_id = auth.uid() and status in ('accepted','rejected','repeated'));

grant select, update on public.trading_profiles to authenticated;
grant select, insert, update on public.signal_connections to authenticated;
grant select, insert, update on public.trade_signals to authenticated;
