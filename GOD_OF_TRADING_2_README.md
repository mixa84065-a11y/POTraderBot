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
- Added a separate in-app BYBIT terminal entry and screen; it does not open the Bybit website or alter the existing binary-options demo workflow.\n- The Bybit screen reads public Bybit Spot tickers and one-minute candles, displays a price chart, and runs a transparent EMA 9/21 + RSI(14) + momentum heuristic.\n- Local paper-trading mode starts with 20,000 USDT virtual cash. Optional auto-demo uses a fixed 100 USDT notional, one open position at a time, a 0.7% stop, a 1.4% target, closed candles only, and a 200 USDT daily realised-loss stop. The simulated ledger stays on this device and is not a Bybit account.\n- The real-account control is intentionally locked. No API secret is requested or stored in the Android app, and no real orders can be sent from this first terminal increment.\n- Android login and registration against Supabase Auth.
- Access/refresh tokens are stored encrypted using Android Keystore (the app does not store the user's password).
- Wallet and trade-history reads are scoped by RLS to the authenticated user.
- Server-only demo trade opening validates the user's session, market configuration, wallet balance, expiry and quote freshness, then debits the wallet atomically.
- Server-side settlement uses the first timestamped quote at/after expiry; if no valid quote arrives within the grace window, the trade is cancelled and the stake is returned.
- Every account's Twelve Data key is stored separately in Supabase Vault. Market snapshots read the key only inside the server function, fetch the user's own quote, and store quotes/candles under a user-specific source. Demo trades open and settle only against that user's quote feed.
- Market snapshot and demo-trade Edge Functions are deployed. The market snapshot now performs user-scoped quote refresh and triggers settlement of due demo trades.
- Android CI runs unit tests before building and uploading the APK artifact.

## External configuration still required before real end-to-end testing
- The app requests the authenticated user's market snapshot. The server refreshes that user's quote no more often than every 15 seconds and backfills up to 120 one-minute candles when the account first connects.
- The per-user quote refresh uses a private server-side Postgres connection to read the matching Vault secret; the decrypted key is never returned to the app or logged.
- EUR/USD demo mode is enabled with an 80% simulated payout, a 30-second maximum entry quote age, and a 60-second settlement grace window. These are demo-only settings and can be tuned before wider testing.
- Supply the Telegram bot URL.
- Create the admin/tester accounts and complete device-level UI/network QA.

## Safety and correctness boundaries
- The original GOD OF TRADING 2.0 currency-pair module remains demo-only. The Bybit terminal's first increment is also paper-only; no broker integration or real-money execution is enabled.\n- The Bybit auto strategy is a rule-based experimental heuristic, not a trained machine-learning model, not backtested proof of edge, and not a guarantee of profit. Its score is not a probability.
- Never store Pocket Option/Binarium passwords, cookies, or session tokens.
- A candle must be built from a named, timestamped provider feed. Stale/missing data is shown as unavailable.
- Client-side writes to wallet, trades, quote, and market-config tables are denied.
- A model-strength percentage is a heuristic direction score, not a calibrated probability or promise of profit.

## Bybit integration roadmap\n- Next: add a server-side credential vault and authenticated Bybit Demo Trading API integration. The official Demo Trading account uses its own API key and `https://api-demo.bybit.com`; its API feature set is not identical to mainnet.\n- Then: verify order acceptance and final execution status, balances, positions, cancel/close flows, stop-loss/take-profit handling, rate limits, and recovery after network interruption on the demo account.\n- Real trading remains disabled until demo validation, account/region eligibility, least-privilege API permissions, hard risk limits, and an explicit in-app live-mode confirmation are complete.\n\n## Build\nGitHub Actions runs `testDebugUnitTest` before `assembleDebug` on every push to this branch. APK artifacts are for internal engineering verification only until the external configuration and device QA checklist above are complete.
