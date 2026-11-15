alter table private.bybit_demo_runs
  add column if not exists worker_lease_token uuid,
  add column if not exists worker_lease_until timestamptz;

create index if not exists bybit_demo_runs_worker_lease_idx
  on private.bybit_demo_runs(status, worker_lease_until)
  where status in ('RUNNING','STOPPING');

create or replace function public.bybit_demo_worker_claim_run(p_run_id uuid, p_lease_token uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_claimed uuid;
begin
  if current_setting('request.jwt.claim.role', true) is distinct from 'service_role' then
    raise exception 'forbidden';
  end if;
  if p_run_id is null or p_lease_token is null then
    raise exception 'invalid lease';
  end if;
  update private.bybit_demo_runs
     set worker_lease_token = p_lease_token,
         worker_lease_until = now() + interval '90 seconds',
         updated_at = now()
   where id = p_run_id
     and status in ('RUNNING','STOPPING')
     and (worker_lease_until is null or worker_lease_until < now() or worker_lease_token = p_lease_token)
  returning id into v_claimed;
  return v_claimed is not null;
end;
$$;

create or replace function public.bybit_demo_worker_release_run(p_run_id uuid, p_lease_token uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if current_setting('request.jwt.claim.role', true) is distinct from 'service_role' then
    raise exception 'forbidden';
  end if;
  update private.bybit_demo_runs
     set worker_lease_token = null,
         worker_lease_until = null,
         updated_at = now()
   where id = p_run_id and worker_lease_token = p_lease_token;
end;
$$;

revoke all on function public.bybit_demo_worker_claim_run(uuid,uuid) from public, anon, authenticated;
revoke all on function public.bybit_demo_worker_release_run(uuid,uuid) from public, anon, authenticated;
grant execute on function public.bybit_demo_worker_claim_run(uuid,uuid) to service_role;
grant execute on function public.bybit_demo_worker_release_run(uuid,uuid) to service_role;
