# GOD OF TRADING 2.0 — isolated development branch

This is a separate 2.0 workstream on `feature/god-of-trading-2.0`. The default `main` branch remains the original application.

## Agreed product scope
- Dark navy terminal UI with the large chart as the main visual.
- UP / DOWN demo controls, compact model-strength strip, and trade history below the controls.
- ANALYSIS workspace that expands the chart and explains the direction using EMA 9/21, RSI(14), and recent momentum.
- Expiry choices: 3, 4, and 5 minutes.
- Portrait and landscape layouts.
- Settings menu with Telegram hand-off (bot URL is not configured yet).
- Multi-user test: each registered account receives its own 20,000 RUB virtual wallet and private trade history.

## Implemented
- Android login and registration against Supabase Auth.
- Access/refresh tokens are stored encrypted using Android Keystore (the app does not store the user's password).
- Wallet and trade-history reads are scoped by RLS to the authenticated user.
- Server-only demo trade opening validates the user's session, market configuration, wallet balance, expiry and quote freshness, then debits the wallet atomically.
- Server-side settlement uses the first timestamped quote at/after expiry; if no valid quote arrives within the grace window, the trade is cancelled and the stake is returned.
- Market quote ingestion is restricted to a server secret. One-minute OHLC candles are aggregated from ingested ticks; the app never fabricates candles.
- Market snapshot and demo-trade Edge Functions are deployed.
- Android CI runs unit tests before building and uploading the APK artifact.

## External configuration still required before real end-to-end testing
- Choose a verified market-data provider and configure its API credentials on the server. No API secret is embedded in the APK.
- Configure the market-ingestion secret and start the provider feed worker.
- Configure the EUR/USD demo market payout rate (server-side simulation setting); until enabled, order buttons remain disabled.
- Supply the Telegram bot URL.
- Create the admin/tester accounts and complete device-level UI/network QA.

## Safety and correctness boundaries
- Demo-only ledger; no broker integration and no real-money execution.
- Never store Pocket Option/Binarium passwords, cookies, or session tokens.
- A candle must be built from a named, timestamped provider feed. Stale/missing data is shown as unavailable.
- Client-side writes to wallet, trades, quote, and market-config tables are denied.
- A model-strength percentage is a heuristic direction score, not a calibrated probability or promise of profit.

## Build
GitHub Actions runs `testDebugUnitTest` before `assembleDebug` on every push to this branch. APK artifacts are for internal engineering verification only until the external configuration and device QA checklist above are complete.
