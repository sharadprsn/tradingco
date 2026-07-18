# Kite Trading — OI-Based Intraday Monitor (Nifty & Sensex)

A Spring Boot application that monitors NSE Nifty/Sensex option chain data in real time, computes Put-Call Ratio (PCR) and Open Interest (OI) buildup, alerts a Calendar Spread recommendation via Telegram once the OI has stabilized, and monitors positions with a multi-layered exit strategy. Data is persisted to an embedded H2 database.

## Features

- **Dual Index Routing** — Mon/Tue/Fri → Nifty, Wed/Thu → Sensex (auto-detected)
- **OI Analysis** — Fetches NSE option chain every 6 minutes, filters near ATM strikes
- **Direction Prediction** — Bullish/Bearish/Neutral based on PE vs CE OI change dominance (60% threshold)
- **Delta-Based Strike Selection** — Primary: Black-Scholes delta 0.15; fallback: ₹30-40 premium range
- **Position Sizing** — Based on ₹10L capital, dynamic lot allocation based on confidence and margins
- **Multi-Layer Exit Strategy** — Hard stop (2× net premium or 2.5× sold leg), trailing stop (0.5% pullback of underlying), profit target (80% decay), PCR shift (dynamic threshold), direction reversal (rolling window), SuperTrend (period 5), OI surge (3% of total open interest), strike breach, time square-off (3:10 PM)
- **H2 Database Persistence** — All snapshots persisted with VIX + index OHLC, stored on host in `./data/`
- **CSV Data Export** — REST endpoint for data download
- **Telegram Notifications** — Calendar Spread recommendation (when stable), OI updates (threshold-gated), exit alerts
- **Startup Health Check** — Verifies NSE connectivity and Telegram bot on startup
- **Docker** — Multi-stage build, JRE Alpine, non-root user, health check (HTTPS support)

## Prerequisites

- Java 21
- Gradle 9.x (bundled wrapper)
- Docker (optional, for containerised deployment)
- Telegram bot token (from [@BotFather](https://t.me/BotFather))
- Zerodha Kite API credentials (optional — for order placement endpoints)

## Quick Start

### 1. Clone & Configure

```bash
cp .env.example .env
```

Edit `.env` with your credentials:

```env
KITE_API_KEY=your-api-key
KITE_API_SECRET=your-api-secret
TELEGRAM_BOT_TOKEN=your-bot-token
TELEGRAM_CHAT_ID=your-chat-id
APP_BASE_URL=https://80.225.215.99
```

### 2. Run

```bash
./gradlew bootRun
```

The app starts on port 443 (HTTPS). Check the logs — you should see a Telegram startup message.

### 3. Docker

```bash
docker compose up --build
```

## Architecture

```
src/main/java/com/kite/trading/
├── KiteTradingApplication.java          # @EnableScheduling entry point
├── config/
│   ├── NseConfig.java                   # NSE API WebClient
│   ├── TelegramConfig.java              # Telegram API WebClient
│   ├── StartupHealthCheck.java          # Startup connectivity checks
│   ├── KiteConfig.java                  # Kite API credentials
│   ├── WebClientConfig.java             # Shared WebClient configuration
│   └── LoggingConfig.java               # HTTP log forwarding
├── controller/
│   ├── AuthController.java              # Kite Connect OAuth endpoints
│   ├── OptionChainController.java       # Option chain query endpoints
│   ├── PositionController.java          # Position query endpoints
│   └── DataExportController.java        # CSV export + stats
├── dto/
│   ├── OiDataSnapshot.java              # OI snapshot record (PCR, totals, buildup, premiums)
│   ├── OiAnalysisResult.java            # Prediction result + trade recommendation
│   ├── OptionChainData.java             # NSE API response DTO
│   ├── IndexQuote.java                  # Index OHLC quote
├── entity/
│   └── OiSnapshotEntity.java            # JPA entity for H2 persistence
├── repository/
│   └── OiSnapshotRepository.java        # Spring Data JPA repository
├── scheduler/
│   └── IntradayOiScheduler.java         # 6-min fixed rate + cron reset/summary
├── exception/
│   └── GlobalExceptionHandler.java      # REST error handling
└── service/
    ├── OiAnalysisService.java           # Core OI analysis engine + H2 persist
    ├── NseOptionChainClient.java        # NSE API client (primary)
    ├── BseOptionChainClient.java            # BSE option chain client
    ├── FallbackOptionChainClient.java    # @Primary orchestrator with retry
    ├── TelegramService.java             # Telegram messaging
    └── ZerodhaApiClient.java            # Kite trade API (order/quote)
```

```
NSE API ──► NseOptionChainClient ──► OiAnalysisService ──► TelegramService
         (every 6 min)                  │                        │
                                        ├─ in-memory snapshots    ├─ Calendar Spread Alert
                                        ├─ H2 persistence         ├─ OI updates
                                        ├─ direction prediction   ├─ exit alerts
                                        └─ exit signal check
```

## Telegram Messages

| Trigger | Content |
|---------|---------|
| **Startup** | Application initialized — NSE & Telegram OK |
| **Calendar Spread Alert** | Direction, confidence %, PCR, VIX, day range, largest PE/CE OI, open, Calendar Spread strikes, trade recommendation, and stability logs |
| **OI Update** (6 min) | Current PCR, PE/CE OI + change, top buildup strikes (skipped if change < thresholds) |
| **Exit Signal** | Reason (HARD STOP / PROFIT TARGET / PCR SHIFT / DIRECTION REVERSAL / SUPERTREND / STRIKE BREACH / TIME SQOFF), confidence, exit fraction |
| **Early Warning** | Pre-confirmation alert when OI-based signal first detected |
| **Market Close** (4 PM) | Summary: 9:15 AM vs 3:30 PM index levels + movement |

### Thresholds (noise reduction)

- PCR must change by ≥ 0.05
- PE or CE OI must change by ≥ 5%
- Both must be exceeded for a periodic OI update notification

## Scheduler Schedule

| Time (IST) | Action |
|------------|--------|
| 9:00 AM | Reset daily state — clear in-memory snapshots, flags |
| 9:15 AM – 3:30 PM | Polls every 6 minutes: fetches option chain, records snapshots, checks stability to notify Calendar Spread alert, and checks exit signals if position is active |
| 3:10 PM | Time-based square-off guard (inside exit assessment) |
| 4:00 PM | Market close summary + daily reset |
| Weekends | `shouldRun()` returns `false`, no activity |


## API Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/api/v1/auth/login-url` | Kite Connect login URL |
| GET | `/api/v1/auth/callback` | OAuth redirect handler |
| POST | `/api/v1/auth/session` | Exchange `request_token` for session |
| GET | `/api/v1/positions` | All active positions |
| GET | `/api/v1/positions/nifty/intraday` | NIFTY intraday positions |
| GET | `/api/v1/data/export/csv` | Download all snapshots as CSV |
| GET | `/api/v1/data/stats` | Snapshot counts (total + last 7 days) |
| GET | `/h2-console` | H2 database console (dev access) |
| GET | `/actuator/health` | Health check |

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `KITE_API_KEY` | Yes | — | Zerodha API key |
| `KITE_API_SECRET` | Yes | — | Zerodha API secret |
| `TELEGRAM_BOT_TOKEN` | Yes | — | Telegram bot token (from @BotFather) |
| `TELEGRAM_CHAT_ID` | Yes | — | Target chat/group ID |
| `NSE_OPTION_CHAIN_URL` | No | `https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY` | NSE API URL |
| `NSE_HOME_URL` | No | `https://www.nseindia.com` | NSE homepage (for cookie) |
| `KITE_BASE_URL` | No | `https://api.kite.trade` | Kite REST API base |
| `KITE_LOGIN_URL` | No | `https://kite.zerodha.com/connect/login` | Kite Connect login |
| `APP_BASE_URL` | No | `https://localhost:443` | Server base URL (Telegram login link) |
| `KITE_REDIRECT_URL` | No | `https://localhost:443/api/v1/auth/callback` | OAuth redirect |
| `SERVER_SSL_KEY_STORE` | No | `keystore.p12` | SSL keystore path |
| `SERVER_SSL_KEY_STORE_PASSWORD` | No | `changeme` | SSL keystore password |
| `SERVER_SSL_KEY_STORE_TYPE` | No | `PKCS12` | SSL keystore type |
| `SERVER_SSL_KEY_ALIAS` | No | `tomcat` | SSL certificate alias |
| `LOG_HTTP_URL` | No | *(empty)* | HTTP log forwarding endpoint |
| `LOG_HTTP_LEVEL` | No | `WARN` | Min level for forwarded logs |

## Development

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run locally
./gradlew bootRun

# Docker Compose (local dev)
docker compose up --build

# Build image manually
docker build -t sharadprsn/kite-trading:latest .
# Tag for your registry
docker tag sharadprsn/kite-trading:latest sharadprsn/kite-trading:1.0.0

# Push to registry
docker push sharadprsn/kite-trading:latest
docker push sharadprsn/kite-trading:1.0.0

# Run from registry (after push)
docker run -d --name kite-trading -p 443:443 --env-file .env sharadprsn/kite-trading:latest
```

> **Note**: Replace `your-registry` with your actual Docker registry (e.g., `docker.io/username`, `ghcr.io/username`, or a private registry). Login first with `docker login` if required.

### Tests

| Test file | Tests | Covers |
|-----------|-------|--------|
| `OiAnalysisServiceTest` | 35 | PCR, ATM filter, direction prediction, thresholds, position lifecycle, reset, day-of-week routing, strikes, exit signals, delta calculation, stability gate logic |
| `IntradayOiSchedulerTest` | 5 | shouldRun guard, reset, close summary, error handling |
| `StartupHealthCheckTest` | 4 | NSE/Telegram success and failure paths |
| `NseConnectivityTest` | 3 | NSE config URLs, option chain data validation |
| `BseConnectivityTest` | 1 | BSE configuration and SENSEX option chain fetch validation |
| `KiteTradingApplicationTests` | 1 | Context load |

## Project Conventions

- Java 21, Spring Boot 3.4.x, Gradle 9.x
- Constructor injection, immutable DTOs (Java records)
- JUnit 5 + Mockito for tests (≥80% coverage target)
- Structured logging via SLF4J
- No `var` keyword, no heavy dependencies without explicit need


## oiAnalysisService Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `PCR_BULLISH_THRESHOLD` | 1.2 | PCR above this is bullish |
| `PCR_BEARISH_THRESHOLD` | 0.8 | PCR below this is bearish |
| `STRIKE_INTERVAL_NIFTY` | 50 | Nifty strike spacing |
| `STRIKE_INTERVAL_SENSEX` | 100 | Sensex strike spacing |
| `LOT_SIZE_NIFTY` | 65 | Nifty lot size (incl. buffer) |
| `LOT_SIZE_SENSEX` | 20 | Sensex lot size (incl. buffer) |
| `DEPLOYED_CAPITAL` | ₹10,00,000 | Position sizing base |
| `EXIT_PCR_SHIFT` | 0.3 | Floor PCR shift threshold |
| `EXIT_OI_SURGE_PCT` | 0.03 (3%) | OI surge exit trigger fraction of total OI |
| `PCR_VOLATILITY_WINDOW` | 8 | Snapshot window for dynamic PCR threshold |
| `PCR_VOLATILITY_MULTIPLIER` | 2.0 | Multiplier for dynamic PCR threshold |
| `DIRECTION_ROLLING_WINDOW` | 3 | Snapshots for rolling direction |
| `CONFIRMATION_CONSECUTIVE` | 2 | Required confirmations for OI-based exits |
| `EXIT_COOLDOWN_MINUTES` | 15 | Cooldown after OI-based exit |
| `HARD_STOP_LOSS_PCT` | 1.0 | Hard stop % (triggers when net premium doubles or sold leg premium reaches 2.5×) |
| `TRAILING_STOP_PCT` | 0.5 | Trailing stop pullback % from best price |
| `AFTERNOON_THRESHOLD_MULTIPLIER` | 0.5 | PCR thresholds halved after 2:30 PM |
| `MIN_PCR_CHANGE_FOR_NOTIFICATION` | 0.05 | Min PCR change to send Telegram update |
| `MIN_OI_CHANGE_FRACTION` | 0.05 | Min OI change fraction (5%) |
| `NEAR_STRIKE_RANGE` | 5 | ATM ± 5 strikes for OI aggregation |
| `TOP_STRIKES_COUNT` | 5 | Top OI buildup strikes to track |
| `MARGIN_PER_LOT` | ₹60,000 | Margin required per lot |
| `SUPERTREND_PERIOD` | 5 | ATR period (30 min) |
| `SUPERTREND_MULTIPLIER` | 3.0 | ATR multiplier |
| `OI_STABILITY_SNAPSHOTS` | 3 | Min consecutive reads before stability confirmed |
| `PCR_STABILITY_MAX_VAR` | 0.05 | Max PCR swing across stability window |
| `OI_SLOWDOWN_RATIO` | 0.50 | Latest OI change must be < 50% of first snapshot |
| `OI_VELOCITY_MAX_PCT` | 30.0% | Max percent change in OI at any single strike |
| `HIGH_CONFIDENCE_THRESHOLD` | 80% | Min confidence for Calendar Spread alert |
| `CALENDAR_ALERT_COOLDOWN_MIN` | 30 | Cooldown (minutes) between Calendar Spread alerts |
