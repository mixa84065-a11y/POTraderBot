-- Explicit deny policies document that market tables are service-role only.
drop policy if exists "No client access to market quotes" on public.market_quotes;
create policy "No client access to market quotes" on public.market_quotes
  for all to anon, authenticated using (false) with check (false);
drop policy if exists "No client access to market config" on public.demo_market_config;
create policy "No client access to market config" on public.demo_market_config
  for all to anon, authenticated using (false) with check (false);
drop policy if exists "No client access to market candles" on public.market_candles;
create policy "No client access to market candles" on public.market_candles
  for all to anon, authenticated using (false) with check (false);
