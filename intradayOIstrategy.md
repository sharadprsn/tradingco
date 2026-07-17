# Intraday OI (Open Interest) Trading Strategy

## Overview

This strategy analyzes Nifty & Sensex option chain data to determine intraday market direction based on Open Interest (OI) changes. It runs on a 6-minute cycle during market hours (9:30 AM - 3:30 PM IST, Mon-Fri), computes a 10 AM market prediction, and monitors positions for exit signals with multiple loss-mitigation layers.

### Index Routing

| Day | Index | Strike Interval | Lot Size |
|-----|-------|-----------------|----------|
| Mon, Tue, Fri | NIFTY | 50 | 65 |
| Wed, Thu | SENSEX | 100 | 20 |

The index is resolved automatically via `resolveIndexForDay()` using `LocalDate.now().getDayOfWeek()`.

---

## Core Architecture

### Data Flow

```
NSE Option Chain API
        |
        v
  OptionChainClient (fetches raw data)
        |
        v
  OiAnalysisService (processes, analyzes, monitors)
        |
        v
  TelegramService (sends alerts)
```

### Scheduler (`IntradayOiScheduler`)

| Time | Event |
|------|-------|
| 9:00 AM | Daily reset — clear state, send initialization message |
| 9:15 AM - 3:30 PM (every 6 min) | Fetch OI data, record snapshots, check stability to notify Calendar Spread alert, check exit signals |
| 4:00 PM | Market close summary, retrain ML model, reset state |

---

## OI Data Fetching (`fetchAndRecordOi`)

1. Fetch the full option chain from NSE/Kite API
2. Parse expiry dates
3. Filter strikes within ±5 strikes of ATM (250-point range for 50-point intervals)
4. Aggregate total PE and CE OI and OI changes for near-ATM strikes
5. Compute Put-Call Ratio (PCR) = Total PE OI / Total CE OI
6. Sort strikes by OI change (descending), keep top 5
7. Store as a timestamped `OiDataSnapshot`
8. Keep max 100 snapshots in memory
9. When a position is active, update highest/lowest price watermarks for trailing stop

### Key Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `STRIKE_INTERVAL_NIFTY` | 50 | Nifty strike spacing |
| `STRIKE_INTERVAL_SENSEX` | 100 | Sensex strike spacing |
| `NEAR_STRIKE_RANGE` | 5 | Number of strikes above/below ATM to include |
| `TOP_STRIKES_COUNT` | 5 | Number of top OI buildup strikes to track |
| `PCR_BULLISH_THRESHOLD` | 1.2 | PCR above this is considered bullish |
| `PCR_BEARISH_THRESHOLD` | 0.8 | PCR below this is considered bearish |
| `LOT_SIZE_NIFTY` | 65 | Nifty lot size |
| `LOT_SIZE_SENSEX` | 20 | Sensex lot size |
| `EXIT_OI_SURGE_PCT` | 0.03 (3%) | OI surge exit trigger fraction of total OI |

---

## Market Prediction (`analyzeAndPredict`)

Runs at 10 AM (and on demand) to determine the day's directional bias.

### Direction Logic

Compare the first snapshot vs latest snapshot:

- **BULLISH**: PE OI change > CE OI change AND PE change > 60% of total OI change
- **BEARISH**: CE OI change > PE OI change AND CE change > 60% of total OI change
- **NEUTRAL**: Balanced OI buildup

### Confidence

- BULLISH/BEARISH: The dominant percentage (capped at 90%)
- NEUTRAL: 50%

### VIX & Day Range

- VIX is fetched from NSE (`/api/quote-indices?indices=INDIA%20VIX`) using the existing `fetchIndexQuote("VIX")` method
- Day Range is computed as: `open ± (open × vix / 1600)` — the expected daily price range based on implied volatility

### Strategy Mapping

| Direction | Strategy |
|-----------|----------|
| BULLISH | DIRECTIONAL PUT SELLING |
| BEARISH | DIRECTIONAL CALL SELLING |
| NEUTRAL | SHORT IRON CONDOR |

### Strike Selection

Strikes are selected based on **premium (last price)** rather than fixed OTM offsets:

- **BULLISH**: Find OTM PE strike with premium ₹30-40 (sell), find lower PE strike with premium ~₹10 (buy hedge)
- **BEARISH**: Find OTM CE strike with premium ₹30-40 (sell), find higher CE strike with premium ~₹10 (buy hedge)
- **NEUTRAL**: Find OTM PE and CE strikes with premium ₹30-40

The `findStrikeByPremium()` method scans the live `strikePremiums` list (stored in each `OiDataSnapshot`) and picks the strike closest to the mid-point of the target premium range.

### Position Sizing

Position sizing is dynamic and confidence-weighted:

- **Capital Base**: `DEPLOYED_CAPITAL` = ₹10,00,000
- **Margin Per Lot**: `MARGIN_PER_LOT` = ₹60,000
- **Max Lots**: `maxLots` = ₹10,00,000 / ₹60,000 = 16 lots (Nifty lot size = 65, Sensex = 20)
- **Sizing Rule**: `lots` = `maxLots × confidence%` (capped at 95% confidence blended, minimum 1 lot)

### Trade Recommendation Format

```
SELL {strike} PE (expiry)
BUY {hedge} PE (expiry) [Hedge] | Spread: {width} pts
Lots: {lots} (Qty: {qty}) | Dynamic Position Sizing
OI Reasoning: Max PE OI at {strike}. Max CE OI at {strike}. PCR: {pcr} | Confidence: {confidence}%
Market Range: {lower} - {upper}
```

---

## Exit Strategy (`notifyExitIfNeeded`)

The exit system is a multi-layered assessment pipeline with three categories of protection:

1. **Price-based stops** (defensive) — hard stop-loss, trailing stop-loss, loss cap
2. **OI-based signals** (predictive) — PCR shift, direction reversal, SuperTrend reversal, OI surge
3. **Confidence scoring** — confirmation streaks, time-based tightening, price confirmation boost

### Exit Signal Types (`ExitSignal` enum)

| Signal | Category | Description |
|--------|----------|-------------|
| `NONE` | — | No exit warranted |
| `HARD_STOP` | Price-based | Net premium doubles (2.0x) or sold leg premium reaches 2.5x → exit immediately |
| `TRAILING_STOP` | Price-based | 0.5% pullback of underlying price from best watermark (when in profit) → exit immediately |
| `STRIKE_BREACH` | Price-based | Spot price crosses the short/sold strike → exit immediately |
| `PROFIT_TARGET` | Price-based | Net premium decays to 20% or less of entry net premium (80% decay) → exit immediately |
| `TIME_SQOFF` | Time-based | Time matches or exceeds 3:10 PM IST → exit immediately |
| `PCR_SHIFT` | OI-based | PCR has moved significantly since position entry |
| `DIRECTION_REVERSAL` | OI-based | Market direction inferred from OI has reversed |
| `SUPERTREND_REVERSAL` | OI-based | 30-min SuperTrend flipped direction (period 5, multiplier 3.0) |
| `OI_SURGE` | OI-based | Total absolute OI change in snapshot exceeds 3% of total open interest |

### Assessment Pipeline (`computeExitAssessment`)

The pipeline runs in this strict order — earlier checks take priority and bypass later checks:

```
1. Guard Checks
   - Position must be entered
   - At least 2 snapshots must exist
   - Entry snapshot must be available

2. Time-Based Square-off Check (Price-Based — skips cooldown)
   - If time is 3:10 PM IST or later → TIME_SQOFF (100% exit)
   - Fires immediately, no confirmation needed

3. HARD STOP CHECK (Price-Based — skips cooldown)
   - If net premium doubles (2.0x) OR sold leg premium >= 2.5x entry sold premium → HARD_STOP (100% exit)
   - Fires immediately, no confirmation needed

4. TRAILING STOP CHECK (Price-Based — skips cooldown)
   - If BULLISH and in profit: track highest price since entry. If price pulls back >= 0.5% → TRAILING_STOP (100% exit)
   - If BEARISH and in profit: track lowest price since entry. If price rallies >= 0.5% → TRAILING_STOP (100% exit)
   - Fires immediately, no confirmation needed

5. STRIKE BREACH CHECK (Price-Based — skips cooldown)
   - If spot price crosses the sold strike (below PE for Bullish, above CE for Bearish) → STRIKE_BREACH (100% exit)
   - If Iron Condor: spot price crosses either short leg → STRIKE_BREACH (100% exit)
   - Fires immediately, no confirmation needed

6. PROFIT TARGET CHECK (Price-Based — skips cooldown)
   - If current net premium decays to <= 20% of entry net premium → PROFIT_TARGET (100% exit)
   - Fires immediately, no confirmation needed

7. Cooldown Check
   - Only reached if none of the price-based/time-based stops triggered
   - If an OI-based exit fired within the last 15 minutes, return NONE
   - Hard/trailing/breach/profit stops do NOT set cooldown and skip cooldown

8. Afternoon Threshold Tightening
   - After 2:30 PM IST, the PCR threshold is multiplied by 0.5
   - Accounts for accelerated theta decay and fading intraday trends

9. Signal Detection (priority order)
   a. Dynamic PCR Shift → PCR_SHIFT
   b. Rolling Direction Reversal → DIRECTION_REVERSAL
   c. SuperTrend Reversal → SUPERTREND_REVERSAL
   d. OI Volume Surge → OI_SURGE
   e. No signal → reset streak, return NONE

10. Strong Signal Fast-Track
    - If PCR_SHIFT + DIRECTION_REVERSAL fire together → requires only 1 confirmation
    - If PCR_SHIFT + SUPERTREND_REVERSAL fire together → requires only 1 confirmation
    - If PCR_SHIFT ratio > 2× threshold → requires only 1 confirmation
    - Otherwise → requires 2 confirmations (standard)

11. Confirmation Lag
    - First detection: log, send EARLY WARNING via Telegram, set streak=1, return NONE
    - Consecutive same signal: increment streak
    - If streak < required: return NONE (waiting for confirmation)
    - If streak >= required: proceed (confirmed signal)

12. Confidence Computation
    - PCR_SHIFT: 30% + (ratio × 40%), capped at 80%
    - DIRECTION_REVERSAL: 70%
    - SUPERTREND_REVERSAL: 65%
    - OI_SURGE: 55%
    - Boosts: +15% if multiple signals, +10% if price confirmed (>=0.5% move), +10% after 2:30 PM, +10% if loss > 0.5%
    - Overall cap: 95%

13. Exit Fraction (scaling)
    - HARD_STOP / TRAILING_STOP / STRIKE_BREACH / PROFIT_TARGET / TIME_SQOFF: exit 100%
    - PCR_SHIFT with ratio < 1.5×: exit 50% of position
    - PCR_SHIFT with ratio >= 1.5×: exit 100%
    - SUPERTREND_REVERSAL: exit 50%
    - All other signals: exit 100%

14. Fire Exit
    - Send Telegram alert via `buildExitReport`
    - For OI-based exits: record `lastExitFiredAt` for cooldown
    - For price-based/time-based exits: skip cooldown
    - Reset confirmation state, early warning flag
```

### Enhanced Detection Methods

#### Dynamic PCR Threshold (`computeDynamicPcrThreshold`)

Instead of a static 0.3 threshold, compute the average absolute PCR change over the last 8 snapshot-to-snapshot transitions, multiply by 2:

```
threshold = max(avg(|ΔPCR|) × 2, 0.1, EXIT_PCR_SHIFT)
```

This adapts to market volatility — quieter markets get tighter thresholds, volatile markets get wider thresholds.

#### Time Decay Dampening (`getTimeDecayFactor`)

Within 2 days of expiry, PCR shifts become less reliable due to theta decay. The threshold is multiplied by 1.5:

```
effectiveThreshold = dynamicThreshold × (daysToExpiry <= 2 ? 1.5 : 1.0)
```

#### Rolling Window Direction (`computeRollingDirection`)

Instead of computing direction from a single snapshot's OI change, sum the OI changes across the last 3 snapshots to smooth out noise:

```
sumPeChange = Σ totalPeOiChange(last 3 snapshots)
sumCeChange = Σ totalCeOiChange(last 3 snapshots)
```

Apply the same >60% dominance rule on the aggregated values.

#### OI Surge Detection

If the total absolute OI change (|PE change| + |CE change|) in a single snapshot divided by the total open interest (PE + CE) exceeds 3% (`EXIT_OI_SURGE_PCT = 0.03`), it triggers an `OI_SURGE` signal — indicating aggressive institutional repositioning.

#### SuperTrend Reversal (`checkSuperTrendReversal`)

A 30-min SuperTrend (5 snapshots) using ATR-based bands. When the trend direction flips, it triggers `SUPERTREND_REVERSAL` — a 50% partial exit signal.

#### Hard Stop-Loss (`isHardStopTriggered`)

Exits the position immediately if options pricing reaches defensive thresholds (checked at every tick, skips cooldown):

- **Spread Stop**: If current net premium of the spread has doubled (>= 2.0x entry net premium).
- **Leg Stop**: If the sold leg premium independently reaches >= 2.5x of the entry sold premium.
- **Iron Condor Leg Stop**: Checks both sold legs independently; if either sold leg reaches >= 2.5x entry premium.

#### Trailing Stop-Loss (`isTrailingStopTriggered`)

Once the position is in profit (underlying price is favorable compared to entry price), tracks the best underlying price (highest for BULLISH, lowest for BEARISH). If the price pulls back 0.5% from that extreme, exits to lock in gains:

- BULLISH: stop if current price < peak × (1 - 0.005)
- BEARISH: stop if current price > trough × (1 + 0.005)

#### Loss Cap

An unused config constant `LOSS_CAP_PCT` (2.0%) exists in the codebase but is currently superseded by the premium-based hard stop-loss and underlying trailing stop-loss checks.

#### Early Warning (`sendEarlyWarning`)

On the first detection of any OI-based signal (before confirmation), sends a Telegram alert:

> ⚠️ EARLY WARNING: PCR shift detected (pending confirmation). Monitor closely.
> Estimated loss: 0.8%
> Current Nifty: 24150

Only sent once per entry (tracked via `earlyWarningSent` flag).

#### Afternoon Tightening

After 2:30 PM IST, all PCR thresholds are tightened by 50% (`AFTERNOON_THRESHOLD_MULTIPLIER = 0.5`) to account for:
- Accelerated theta decay in options
- Weakening intraday trends
- Reduced time for trade recovery

#### Price Confirmation (`isPriceConfirmed`)

Changed from a gate (blocking exits) to a confidence booster (+10). The 0.5% price-move requirement is still computed but no longer suppresses signals — it only adds confidence.

#### Cooldown (`isInCooldown`)

- Reduced from 30 to 15 minutes
- Only applies to OI-based exits (PCR shift, direction reversal, SuperTrend, OI surge)
- Hard/trailing/breach/profit stops skip cooldown entirely

### Position Lifecycle

| Method | Effect |
|--------|--------|
| `markPositionEntered()` | Sets `positionEntered=true`, records entry snapshot, entry price, computed entry direction, initializes price watermarks (`highestPriceSinceEntry` / `lowestPriceSinceEntry`), resets `earlyWarningSent` |
| `markPositionExited()` | Clears `positionEntered`, clears entry snapshot, resets watermarks and early warning flag |
| `reset()` | Full state reset including watermarks and early warning flag (daily) |

### Exit Alert Format

```
⚠️ EXIT SIGNAL DETECTED ⚠️

HARD STOP-LOSS triggered. Price moved beyond stop-loss threshold. EXIT IMMEDIATELY.
Confidence: 95%

Current PCR: 1.15
Current Nifty: 23900.00
```

Or for OI-based exits with new recommendation:

```
⚠️ EXIT SIGNAL DETECTED ⚠️

PCR has shifted significantly since entry.
Confidence: 72%
Suggested Exit: 50% of position

Current PCR: 1.15
Current Nifty: 24350.00

🔄 New Recommendation:
🟢 Direction: BULLISH (75%)
Strategy: DIRECTIONAL PUT SELLING
SELL 24300 PE (05-JUN) | HEDGE: BUY 24200 PE (12-JUN)
```

---

## Change Notification (`notifyOiUpdate`)

OI updates are sent only when significant changes are detected:

- PCR change > 0.05
- PE or CE OI change > 5% of total open interest

---

## Configuration Parameters

### Entry/Prediction Constants

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `PREDICTION_TIME` | 10:00 AM IST | Time for daily prediction |
| `PCR_BULLISH_THRESHOLD` | 1.2 | PCR above this is bullish |
| `PCR_BEARISH_THRESHOLD` | 0.8 | PCR below this is bearish |
| `TOP_STRIKES_COUNT` | 5 | Top OI buildup strikes to track |
| `STRIKE_INTERVAL_NIFTY` | 50 | Nifty strike spacing |
| `STRIKE_INTERVAL_SENSEX` | 100 | Sensex strike spacing |

### Exit Strategy Constants

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `EXIT_PCR_SHIFT` | 0.3 | Floor for PCR shift threshold |
| `EXIT_OI_SURGE_PCT` | 0.03 (3%) | OI surge trigger percentage of total open interest |
| `CONFIRMATION_CONSECUTIVE` | 2 | Standard consecutive checks needed |
| `STRONG_SIGNAL_CONFIRMATION_REQUIRED` | 1 | Fast-track when multiple signals combine |
| `PCR_VOLATILITY_WINDOW` | 8 | Snapshot window for avg ΔPCR |
| `PCR_VOLATILITY_MULTIPLIER` | 2.0 | Multiplier for dynamic threshold |
| `DIRECTION_ROLLING_WINDOW` | 3 | Snapshots for rolling direction |
| `SUPERTREND_PERIOD` | 5 | Snapshots for SuperTrend (30 min) |
| `SUPERTREND_MULTIPLIER` | 3.0 | ATR multiplier for SuperTrend bands |
| `EXIT_COOLDOWN_MINUTES` | 15 | Cooldown after OI-based exit |
| `PRICE_CONFIRMATION_PCT` | 0.5 | Min price move percentage (now confidence booster) |
| `DAYS_TO_EXPIRY_DAMPENING` | 2 | Expiry proximity threshold |
| `TIME_DECAY_FACTOR` | 1.5 | PCR threshold multiplier near expiry |
| `HARD_STOP_LOSS_PCT` | 1.0 | Hard stop-loss % from entry |
| `TRAILING_STOP_PCT` | 0.5 | Trailing stop pullback % from peak |
| `LOSS_CAP_PCT` | 2.0 | Unused loss cap parameter (declared but superseded) |
| `AFTERNOON_THRESHOLD_MULTIPLIER` | 0.5 | PCR threshold tightener after 2:30 PM |

### Scheduler Constants

| Parameter | Value |
|-----------|-------|
| `MARKET_START` | 9:15 AM IST |
| `PREDICTION_TIME` | 10:00 AM IST |
| `MARKET_CLOSE` | 3:30 PM IST |
| `SIX_MINUTES_MS` | 360,000 ms |

---

## Key Data Structures

### `OiDataSnapshot` (record)
```
timestamp: LocalDateTime
underlyingValue: BigDecimal
totalPeOi: BigDecimal
totalCeOi: BigDecimal
totalPeOiChange: BigDecimal
totalCeOiChange: BigDecimal
pcr: BigDecimal
topOiBuildUp: List<OiStrikeInfo>
largestPeOiStrike: BigDecimal
largestCeOiStrike: BigDecimal
strikePremiums: List<StrikePremium>
marketSentiment: BigDecimal
```

### `OiStrikeInfo` (record, nested in `OiDataSnapshot`)
```
strikePrice: BigDecimal
optionType: String ("PE" or "CE")
openInterest: BigDecimal
changeInOi: BigDecimal
pchangeInOi: BigDecimal
```

### `StrikePremium` (record, nested in `OiDataSnapshot`)
```
strikePrice: BigDecimal
pePremium: BigDecimal
cePremium: BigDecimal
peIv: BigDecimal
ceIv: BigDecimal
```

### `OiAnalysisResult` (record)
```
direction: String ("BULLISH" | "BEARISH" | "NEUTRAL")
confidence: BigDecimal
pcr: BigDecimal
suggestedStrategy: String
suggestedStrikes: List<BigDecimal>
reasoning: String
tradeRecommendation: String
vix: BigDecimal
indexOpen: BigDecimal
largestPeOiStrike: BigDecimal
largestCeOiStrike: BigDecimal
marketSentiment: BigDecimal
```

### `ExitSignal` (enum)
```
NONE, PCR_SHIFT, DIRECTION_REVERSAL, SUPERTREND_REVERSAL, OI_SURGE,
HARD_STOP, TRAILING_STOP, STRIKE_BREACH, PROFIT_TARGET, TIME_SQOFF
```

### `ExitAssessment` (record, nested in `OiAnalysisService`)
```
signal: ExitSignal
confidence: BigDecimal
exitFraction: BigDecimal  (0.0-1.0, e.g., 0.5 = exit 50%)
```

---

## Usage

### Entry flow:
1. User decides to enter a trade based on the 10 AM prediction (or other analysis)
2. Calls `oiAnalysisService.markPositionEntered()` to begin exit monitoring
3. Scheduler checks `isPositionEntered()` every 6 minutes and runs exit assessment

### Exit flow:
1. Scheduler calls `notifyExitIfNeeded()` every 6 minutes
2. Price-based stops (hard stop, trailing stop, loss cap) are checked first — fire immediately
3. OI-based signals go through confirmation lag and cooldown
4. If a confirmed exit signal is detected, a Telegram alert is sent
5. User manually exits the position and calls `markPositionExited()`

### Monitoring:
- OI updates are sent automatically on significant changes
- Early warning alerts sent on first OI signal detection (before confirmation)
- Exit alerts include current market data and a fresh recommendation
- Market close summary is sent at 4 PM

---

## Improvement History

### Initial Exit Strategy
- Static PCR threshold (0.3)
- Single-snapshot direction comparison
- No OI surge detection
- No confirmation/cooldown/weighting

### Enhanced Exit Strategy (previous)
- Dynamic PCR threshold based on recent volatility
- Time-decay dampening near expiry
- Rolling window direction computation
- OI surge detection (EXIT_OI_SURGE)
- Price confirmation gate (0.5% minimum move)
- 30-minute cooldown after exit
- Signal weighting with confidence scores
- Trailing/scaling exit (50% or 100% based on severity)
- Confirmation lag (2 consecutive checks required)

### Loss Minimization Improvements
- **Hard stop-loss (premium doubled or sold leg >= 2.5×)**: Checked before OI signals, skips cooldown
- **Trailing stop-loss (0.5% pullback of underlying)**: Locks in profits, only fires when in profit
- **Loss cap (2%)**: Unused config constant, superseded by premium-based hard stop and underlying trailing stop
- **Price confirmation → confidence booster**: No longer blocks exits, adds +10 confidence
- **Early warning alerts**: Telegram on first OI signal detection (pre-confirmation)
- **Strong signal fast-track**: Combined signals skip to 1 confirmation
- **SuperTrend reversal**: New exit signal type (50% partial exit, period 5)
- **Afternoon tightening**: PCR thresholds halved after 2:30 PM
- **Cooldown reduced**: 30 → 15 minutes, skipped entirely for price-based stops

### Dual Index & Premium-Based Strike Selection (latest)
- **Day-of-week routing**: Mon/Tue/Fri → Nifty (50-pt strikes), Wed/Thu → Sensex (100-pt strikes)
- **Premium-based strikes**: Sell at ₹30-40 premium, hedge at ~₹10 (Directional Put/Call Selling or Iron Condor)
- **VIX integration**: Fetched from NSE, displayed with day range (open ± open × vix / 1600)
- **Largest OI strikes**: Max PE/CE OI levels shown in prediction
- **Position sizing**: ₹10L capital, dynamic lot allocation based on confidence and margins (lot size 65 for Nifty, 20 for Sensex)
- **StrikePremium data**: Per-strike PE/CE premiums stored in snapshots for live selection
- **Build configuration cache**: Enabled for faster rebuilds
