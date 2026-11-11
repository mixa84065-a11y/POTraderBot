create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;
create table if not exists private.user_market_provider_secrets (
 user_id uuid primary key references auth.users(id) on delete cascade,
 secret_id uuid not null unique,
 provider text not null default 'twelvedata' check (provider='twelvedata'),
 updated_at timestamptz not null default now()
);
revoke all on private.user_market_provider_secrets from public, anon, authenticated;
grant select,insert,update,delete on private.user_market_provider_secrets to service_role;

create or replace function private.save_twelvedata_key(p_user_id uuid,p_api_key text)
returns void language plpgsql security definer set search_path=''
as $$
declare v_id uuid; v_name text := 'twelvedata-user-'||p_user_id::text;
begin
 if p_user_id is null or p_api_key is null or length(trim(p_api_key))<8 or length(p_api_key)>256 then raise exception 'Invalid API key'; end if;
 select secret_id into v_id from private.user_market_provider_secrets where user_id=p_user_id for update;
 if v_id is null then
  v_id:=vault.create_secret(p_api_key,v_name,'Per-user Twelve Data API key for GOD OF TRADING 2.0');
  insert into private.user_market_provider_secrets(user_id,secret_id) values(p_user_id,v_id);
 else
  perform vault.update_secret(v_id,p_api_key,v_name,'Per-user Twelve Data API key for GOD OF TRADING 2.0',null);
  update private.user_market_provider_secrets set updated_at=now() where user_id=p_user_id;
 end if;
end $$;

create or replace function private.has_twelvedata_key(p_user_id uuid)
returns boolean language sql stable security definer set search_path=''
as $$ select exists(select 1 from private.user_market_provider_secrets where user_id=p_user_id and provider='twelvedata'); $$;

create or replace function private.delete_twelvedata_key(p_user_id uuid)
returns void language plpgsql security definer set search_path=''
as $$
declare v_id uuid;
begin
 delete from private.user_market_provider_secrets where user_id=p_user_id returning secret_id into v_id;
 if v_id is not null then perform vault.update_secret(v_id,'','twelvedata-user-'||p_user_id::text,'Removed per-user Twelve Data API key',null); end if;
end $$;

revoke all on function private.save_twelvedata_key(uuid,text) from public,anon,authenticated;
revoke all on function private.has_twelvedata_key(uuid) from public,anon,authenticated;
revoke all on function private.delete_twelvedata_key(uuid) from public,anon,authenticated;
grant execute on function private.save_twelvedata_key(uuid,text) to service_role;
grant execute on function private.has_twelvedata_key(uuid) to service_role;
grant execute on function private.delete_twelvedata_key(uuid) to service_role;