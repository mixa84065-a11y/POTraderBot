-- Per-account analysis preferences for GOD OF TRADING.
create table if not exists public.trading_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  selected_pairs text[] not null default array['EUR/USD']::text[],
  expiry_minutes integer not null default 3 check (expiry_minutes in (3,5)),
  sharing_enabled boolean not null default false,
  updated_at timestamptz not null default now(),
  constraint selected_pairs_count check (cardinality(selected_pairs) between 1 and 10)
);

alter table public.trading_settings enable row level security;
drop policy if exists "Users manage own trading settings" on public.trading_settings;
create policy "Users manage own trading settings" on public.trading_settings
for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

grant select, insert, update on public.trading_settings to authenticated;

create or replace function public.handle_new_trading_user()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
  insert into public.trading_profiles (user_id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'name', new.email))
  on conflict (user_id) do nothing;
  insert into public.trading_settings (user_id)
  values (new.id)
  on conflict (user_id) do nothing;
  return new;
end;
$$;
