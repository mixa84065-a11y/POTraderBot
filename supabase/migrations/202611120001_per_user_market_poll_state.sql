create table if not exists private.user_market_poll_state (
  user_id uuid not null references auth.users(id) on delete cascade,
  symbol text not null,
  last_attempt_at timestamptz,
  history_attempted_at timestamptz,
  updated_at timestamptz not null default now(),
  primary key (user_id, symbol)
);
alter table private.user_market_poll_state enable row level security;
revoke all on table private.user_market_poll_state from public, anon, authenticated;
grant all on table private.user_market_poll_state to service_role;
