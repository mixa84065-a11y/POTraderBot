-- Lock the worker's active-run RPC to the server role only and index report/ownership joins.
-- Applied to the linked Supabase project on 2026-11-15.
revoke all on function public.bybit_demo_worker_active_runs() from public, anon, authenticated;
grant execute on function public.bybit_demo_worker_active_runs() to service_role;

create index if not exists bybit_demo_events_run_id_idx
  on private.bybit_demo_events(run_id);
create index if not exists bybit_demo_positions_user_id_idx
  on private.bybit_demo_positions(user_id);
