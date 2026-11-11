# GOD OF TRADING 2.0 — isolated development branch

This branch is the separate 2.0 workstream. The existing `main` branch and the earlier prototype are not changed.

## Agreed product scope
- Dark navy terminal UI with the large chart as the main visual.
- UP / DOWN demo controls and a compact risk/probability strip.
- Trade history directly below the trading controls.
- A dedicated ANALYSIS action that expands the chart and analysis into a full-screen workspace.
- Portrait and landscape layouts.
- Settings menu, including a Telegram bot hand-off (bot URL must be configured before enabling it).
- Multi-user test cohort: owner/admin plus two test accounts, each with an isolated virtual wallet and trade history.

## Current implementation status
- Android app scaffold created on this branch.
- Chart surface intentionally shows a waiting state; it does not draw synthetic candles.
- Demo order controls are gated until a verified live OHLC feed and server-side trade lifecycle are connected.
- Supabase project has isolated `demo_wallets` and `demo_trades` tables with per-user read policies. Client-side balance/trade writes are blocked; trusted server functions are still required for order creation and settlement.

## Important safety and correctness boundaries
- Demo-only ledger; no broker integration and no real-money execution.
- Do not store Pocket Option/Binarium passwords, cookies, or session tokens.
- A displayed candle must come from a named, timestamped data source. Stale or missing data must be shown as unavailable, never replaced with generated candles.
- Trade entry and settlement must be atomic server operations. The quote used for entry/expiry must be verified server-side before public testing.

## Build
GitHub Actions builds a debug APK when this branch receives a push. The artifact is named `GOD-OF-TRADING-2-debug`.
