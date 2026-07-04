# Kite Trading — OI-Based Nifty Intraday Monitor

A Spring Boot application that monitors NSE Nifty option chain data in real time, computes Put-Call Ratio (PCR) and Open Interest (OI) buildup, predicts intraday direction at 10 AM, and sends trade recommendations via Telegram.

## Features

- **OI Analysis** — Fetches NSE option chain every 6 minutes, filters ATM ± 5 strikes
- **Direction Prediction** — Bullish/Bearish/Neutral based on PE vs CE OI change dominance (60% threshold)
- **Trade Recommendations** — Suggests sell strike, expiry, and hedge leg (calendar/diagonal spread)
- **Exit Signals** — Monitors PCR shift (> 0.3) and direction reversal since entry
- **Telegram Notifications** — 10 AM prediction, periodic OI updates (threshold-gated), exit alerts
- **Startup Health Check** — Verifies NSE connectivity and Telegram bot on startup
- **Scheduler** — Weekday only (9:30 AM–3:30 PM), 6-minute interval, auto-reset daily
- **Docker** — Multi-stage build, JRE Alpine, non-root user, health check

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
```

### 2. Run

```bash
./gradlew bootRun
```

The app starts on port 8080. Check the logs — you should see a Telegram startup message.

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
│   └── ...
├── dto/
│   ├── OiDataSnapshot.java              # OI snapshot (PCR, totals, buildup)
│   ├── OiAnalysisResult.java            # Prediction result + trade recommendation
│   ├── OptionChainData.java             # NSE API response DTO
│   └── ...
├── scheduler/
│   └── IntradayOiScheduler.java         # 6-min fixed rate + cron reset/summary
└── service/
    ├── OiAnalysisService.java           # Core OI analysis engine
    ├── NseOptionChainClient.java        # NSE API client
    ├── TelegramService.java             # Telegram messaging (interface + impl)
    ├── ZerodhaApiClient.java            # Kite trade API (order/quote)
    └── ...
```

### Data Flow

```
NSE API ──► NseOptionChainClient ──► OiAnalysisService ──► TelegramService
         (every 6 min)                  │                        │
                                        ├─ snapshots (in-memory)  ├─ OI updates
                                        ├─ direction prediction   ├─ 10 AM prediction
                                        ├─ exit signal check      ├─ exit alerts
                                        └─ trade recommendation
```

## Telegram Messages

| Trigger | Content |
|---------|---------|
| **Startup** | Application initialized — NSE & Telegram OK |
| **10 AM Prediction** | Direction, confidence %, PCR, strategy, strikes, trade recommendation with expiries |
| **OI Update** (6 min) | Current PCR, PE/CE OI + change, top buildup strikes (skipped if change < thresholds) |
| **Exit Signal** | Reason (PCR shift / direction reversal), current PCR/Nifty, **fresh recommendation** |
| **Market Close** | Summary of the day's OI data |

### Thresholds (noise reduction)

- PCR must change by ≥ 0.05
- PE or CE OI must change by ≥ 5%
- Both must be exceeded for a periodic OI update notification

## Scheduler Schedule

| Time | Action |
|------|--------|
| 9:00 AM | Reset daily state, clear snapshots |
| 9:30 AM | `shouldRun()` enables — health check, Telegram startup message |
| 9:30 AM – 10:00 AM | OI snapshots recorded every 6 minutes |
| 10:00 AM | `analyzeAndPredict()` → direction prediction sent via Telegram |
| 10:00 AM – 3:30 PM | OI snapshots + exit monitoring every 6 minutes |
| 3:30 PM | Market close summary |
| Weekends | `shouldRun()` returns `false`, no activity |

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/auth/login-url` | Kite Connect login URL |
| GET | `/api/v1/auth/callback` | OAuth redirect handler |
| POST | `/api/v1/auth/session` | Exchange `request_token` for session |
| GET | `/api/v1/positions` | All active positions |
| GET | `/api/v1/positions/nifty/intraday` | NIFTY intraday positions |
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
| `KITE_REDIRECT_URL` | No | `http://localhost:8080/api/v1/auth/callback` | OAuth redirect |
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
docker build -t kite-trading:latest .

# Tag for your registry
docker tag kite-trading:latest sharadprsn/kite-trading:latest
docker tag kite-trading:latest sharadprsn/kite-trading:1.0.0

# Push to registry
docker push sharadprsn/kite-trading:latest
docker push sharadprsn/kite-trading:1.0.0

# Run from registry (after push)
docker run -d --name kite-trading -p 8080:8080 --env-file .env sharadprsn/kite-trading:latest
```

> **Note**: Replace `your-registry` with your actual Docker registry (e.g., `docker.io/username`, `ghcr.io/username`, or a private registry). Login first with `docker login` if required.

### Tests (32 tests)

| Test file | Tests | Covers |
|-----------|-------|--------|
| `OiAnalysisServiceTest` | 17 | PCR, ATM filter, direction prediction, thresholds, position lifecycle, reset |
| `IntradayOiSchedulerTest` | 5 | shouldRun guard, reset, close summary, error handling |
| `StartupHealthCheckTest` | 4 | NSE/Telegram success and failure paths |
| `TelegramServiceTest` | 1 | Bot connectivity (manual) |
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
| `EXIT_PCR_SHIFT` | 0.3 | PCR shift from entry triggers exit |
| `MIN_PCR_CHANGE_FOR_NOTIFICATION` | 0.05 | Min PCR change to send Telegram update |
| `MIN_OI_CHANGE_FRACTION` | 0.05 | Min OI change fraction (5%) |
| `NEAR_STRIKE_RANGE` | 5 | ATM ± 5 strikes (11 strikes total) |
| `STRIKE_INTERVAL` | 50 | Nifty strike interval (50 points) |
| `TOP_STRIKES_COUNT` | 5 | Top OI buildup strikes to track |
