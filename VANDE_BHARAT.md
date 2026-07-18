# Vande Bharat Strategy

Intraday breakout + inside candle + trailing stop strategy for F&O stocks, scanning ~130 stocks pre-market via OI change.

## Strategy Flow

```
Pre-market (9:10 AM)     → OI scan → top 5 stocks
         ↓
Breakout (close > PDH)   → detects setup
         ↓
Inside candle (≤3 vol)   → confirms range consolidation
         ↓
Break of inside candle   → ENTRY signal
         ↓
Trail / Partial / EMA10  → EXIT signal
```

## Phases

### Phase 0 — Pre-market OI Scan (9:10 AM)
- Scans `MASTER_FNO_LIST` (130 stocks)
- Fetches equity option chain per symbol via `parallelStream()`
- Sums `|PE OI change| + |CE OI change|`
- Sorts descending by total absolute OI change
- Selects **top 5** → replaces `states` + clears old signals
- Sends Telegram summary

### Phase 1 — Breakout Detection
- **PDH** = previousClose × 1.01 (upper 1% band)
- **PDL** = previousClose × 0.99 (lower 1% band)
- LONG: close > PDH AND close ≤ PDH × 1.02 (filters over-extended moves)
- SHORT: close < PDL AND close ≥ PDL × 0.98
- Sets `breakoutCandle` + `breakoutDirection` + resets inside-candle counter

### Phase 2 — Inside Candle Search
Looks for a candle that:
- Is **inside** the breakout candle's range (`high ≤ breakout.high`, `low ≥ breakout.low`)
- Has **volume ≤ breakout volume**
- Up to **6 candles** (30 minutes) before breakout resets

### Phase 3 — Entry Trigger
Once inside candle is detected, waits for a subsequent candle where:
- **LONG**: `close > insideCandle.high` AND `candle.volume > insideCandle.volume`
- **SHORT**: `close < insideCandle.low` AND `candle.volume > insideCandle.volume`
- Generates signal with `entryPrice = insideCandle.high/low`, `stopLoss = insideCandle.low/high`

### Phase 4 — Trade Management (after `enterTrade()`)

| Feature | Trigger | Action |
|---|---|---|
| Partial exit | Price reaches `entry ± 2 × stopDistance` | Books 50% via Telegram alert |
| Trailing stop | `highestPrice - stopDistance` (LONG) | Exits when close trails below |
| EMA 10 | Close crosses below/above 10-period EMA | Exits remaining position |

## Configuration

| Constant | Value | Description |
|---|---|---|
| `DEFAULT_RANGE_PCT` | 0.01 (1%) | PDH/PDL band width |
| `MAX_BREAKOUT_MULTIPLIER` | 1.02 | Max breakout extension above PDH |
| `INSIDE_CANDLE_LIMIT` | 6 | Max candles to find inside candle (30 min) |
| `MAX_CONCURRENT_TRADES` | 2 | Max simultaneous active trades |
| `TRAIL_MULTIPLIER` | 1.0 | Trailing stop distance multiplier |
| `PARTIAL_EXIT_MULTIPLIER` | 2.0 | 1:2 RR partial exit target |
| `PARTIAL_EXIT_PCT` | 50 | Percentage booked at partial exit |
| `CAPITAL` | ₹1,000,000 | Capital base for position sizing |
| `RISK_PER_TRADE_PCT` | 0.5% | Risk per trade (% of capital) |

## Position Sizing

```
maxLossPerTrade  = CAPITAL × (RISK_PER_TRADE_PCT / 100)
stopDistance     = |entryPrice - stopLoss|
quantity         = maxLossPerTrade / stopDistance  (rounded to 0 decimals)
```

## Entry / Exit Signals

### Entry Signal
```
🟡 VANDE BHARAT SIGNAL
Stock: RELIANCE
Direction: LONG
Entry: 3105.00
Stop Loss: 3098.00
PDH: 3125.00 | PDL: 3075.00
Current: 3102.00
Suggested Qty: 714
```

### Partial Exit
```
💰 VANDE BHARAT PARTIAL EXIT
Stock: RELIANCE
Direction: LONG
Target Hit: 3119.00
Booked: 50% at 1:2 RR
Rest running with trailing stop & EMA 10
```

### Full Exit (Trailing / EMA 10)
```
⚠️ VANDE BHARAT EXIT
Stock: RELIANCE
Direction: LONG
Exit Price: 3085.00
Reason: TRAILING STOP
```

## Stock Lists

### Default Watchlist (fallback)
`RELIANCE`, `TCS`, `HDFCBANK`, `INFY`, `ICICIBANK`

### Master F&O List (~130 stocks)
Full list in `VandeBharatStrategyService.MASTER_FNO_LIST` — all liquid F&O equities on NSE.

## Dependencies

- **`NseOptionChainClient`** — fetches equity option chain, equity quotes
- **`TelegramService`** — sends real-time alerts
- **Clock** — injectable clock (IST default, test-friendly)

## Scheduling

| Time | Method | Action |
|---|---|---|
| 09:10 | `preMarketScan()` | OI scan → top 5 |
| 09:15–15:30 | `analyze()` | 5-min analysis ticks |
| 09:00 daily | `resetDaily()` | Clear states + signals |

## Testing

Run the strategy service tests:
```bash
./gradlew test --tests "com.kite.trading.service.VandeBharatStrategyServiceTest"
./gradlew test --tests "com.kite.trading.scheduler.VandeBharatStrategySchedulerTest"
```
