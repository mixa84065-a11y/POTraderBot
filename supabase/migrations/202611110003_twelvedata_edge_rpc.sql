-- Expose narrowly scoped RPC wrappers to Edge Functions only.
create or replace function public.save_twelvedata_key(p_user_id uuid,p_api_key text)
returns void language sql security definer set search_path=''
as $$ select private.save_twelvedata_key(p_user_id,p_api_key); $$;
create or replace function public.has_twelvedata_key(p_user_id uuid)
returns boolean language sql stable security definer set search_path=''
as $$ select private.has_twelvedata_key(p_user_id); $$;
create or replace function public.delete_twelvedata_key(p_user_id uuid)
returns void language sql security definer set search_path=''
as $$ select private.delete_twelvedata_key(p_user_id); $$;
revoke all on function public.save_twelvedata_key(uuid,text) from public,anon,authenticated;
revoke all on function public.has_twelvedata_key(uuid) from public,anon,authenticated;
revoke all on function public.delete_twelvedata_key(uuid) from public,anon,authenticated;
grant execute on function public.save_twelvedata_key(uuid,text) to service_role;
grant execute on function public.has_twelvedata_key(uuid) to service_role;
grant execute on function public.delete_twelvedata_key(uuid) to service_role;