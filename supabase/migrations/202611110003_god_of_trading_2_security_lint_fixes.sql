-- Tighten trigger-function execution and make RLS auth checks initplan-safe.
revoke all on function public.create_demo_wallet_for_new_user() from public, anon, authenticated;

drop policy if exists "Users can view own demo wallet" on public.demo_wallets;
create policy "Users can view own demo wallet" on public.demo_wallets
  for select to authenticated using ((select auth.uid()) = user_id);

drop policy if exists "Users can view own demo trades" on public.demo_trades;
create policy "Users can view own demo trades" on public.demo_trades
  for select to authenticated using ((select auth.uid()) = user_id);
