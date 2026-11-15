create or replace function public.bybit_demo_worker_is_service_role()
returns boolean language sql stable security definer set search_path = ''
as $$
  select coalesce(
    current_setting('request.jwt.claim.role', true),
    nullif(current_setting('request.jwt.claims', true), '')::jsonb->>'role'
  ) = 'service_role';
$$;
revoke all on function public.bybit_demo_worker_is_service_role() from public, anon, authenticated;
grant execute on function public.bybit_demo_worker_is_service_role() to service_role;

do $$
declare r record; v_def text;
begin
  for r in
    select p.oid from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='public' and p.proname in (
      'bybit_demo_worker_active_runs','bybit_demo_worker_credentials',
      'bybit_demo_worker_open_positions','bybit_demo_worker_heartbeat',
      'bybit_demo_worker_heartbeat_v2','bybit_demo_worker_log',
      'bybit_demo_worker_close_position','bybit_demo_worker_open_position',
      'bybit_demo_worker_claim_run','bybit_demo_worker_release_run'
    )
  loop
    v_def := pg_get_functiondef(r.oid);
    v_def := replace(v_def,
      'current_setting(''request.jwt.claim.role'',true) is distinct from ''service_role''',
      'not public.bybit_demo_worker_is_service_role()');
    v_def := replace(v_def,
      'current_setting(''request.jwt.claim.role'', true) is distinct from ''service_role''',
      'not public.bybit_demo_worker_is_service_role()');
    execute v_def;
  end loop;
end;
$$;

create or replace function public.bybit_demo_worker_authorize(p_secret text)
returns boolean language sql security definer set search_path = ''
as $$
  select public.bybit_demo_worker_is_service_role()
    and p_secret is not null
    and exists (
      select 1 from vault.decrypted_secrets
      where name='bybit_demo_worker_secret' and decrypted_secret=p_secret
    );
$$;
revoke all on function public.bybit_demo_worker_authorize(text) from public, anon, authenticated;
grant execute on function public.bybit_demo_worker_authorize(text) to service_role;
