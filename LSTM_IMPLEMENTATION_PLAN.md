# LSTM Direction Prediction Model — Implementation Plan

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                  Java App (existing)                 │
│                                                      │
│  OiAnalysisService.analyzeAndPredict()               │
│         │                                            │
│         ├── Rule-based logic (current) → direction   │
│         │                                            │
│         └── HTTP call → Python sidecar → LSTM dir    │
│                                                      │
│  Compare both → pick with higher confidence          │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP (port 8000)
┌──────────────────────┴──────────────────────────────┐
│              Python Sidecar (FastAPI)                │
│                                                      │
│  GET /predict?features=[...] → {direction, conf}    │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │ Preproc  │→ │ LSTM     │→ │ Postproc         │   │
│  │ (window) │  │ (ONNX)   │  │ (softmax→label)  │   │
│  └──────────┘  └──────────┘  └──────────────────┘   │
└──────────────────────────────────────────────────────┘
```

## Phase 1: Data Collection & Persistence ✅ (Implemented)

**Goal:** Stop losing historical data. Snapshots are now persisted to H2 database.

### 1.1 H2 Database (embedded, file-based)

- `spring-boot-starter-data-jpa` + `h2` runtime dependency added to `build.gradle`
- `OiSnapshotEntity` JPA entity with all relevant columns + JSON CLOBs for complex fields
- `OiSnapshotRepository` with find by timestamp range and index name queries
- H2 configured in `application.properties`:
  - `jdbc:h2:file:./data/oi_snapshots;AUTO_SERVER=TRUE`
  - `spring.jpa.hibernate.ddl-auto=update` (auto-creates schema)
  - H2 console available at `/h2-console` for manual inspection
- Docker volume `./data:/app/data` mounted to persist DB files on host (same pattern as logs)

### 1.2 VIX + index quote persisted

- `fetchVix()` called inline in `persistSnapshot()` → stored in entity
- `fetchIndexQuote(currentIndex)` called in `persistSnapshot()` → open/high/low stored
- All data captured together atomically per 6-min tick

### 1.3 CSV export REST endpoint

- `GET /api/v1/data/export/csv` → downloads all snapshots as CSV with feature-ready columns
- `GET /api/v1/data/stats` → shows total and weekly snapshot counts
- CSV columns: `timestamp, indexName, underlyingValue, totalPeOi, totalCeOi, totalPeOiChange, totalCeOiChange, pcr, largestPeOiStrike, largestCeOiStrike, vix, indexOpen, indexHigh, indexLow`

## Phase 2: Feature Engineering ✅ (Implemented in `ml-sidecar/features.py`)

### 2.1 Features per snapshot (15 features)

All computed in `features.py::engineer_features()`:

| # | Feature | Computation |
|---|---------|-------------|
| 1 | pcr | snapshot.pcr |
| 2 | pcr_change_1 | pcr.diff(1) |
| 3 | pcr_change_3 | pcr.diff(3) |
| 4 | pe_oi_change | totalPeOiChange |
| 5 | ce_oi_change | totalCeOiChange |
| 6 | oi_change_ratio | PEΔ / (PEΔ + CEΔ) |
| 7 | oi_change_dominance_pct | max(PE%, CE%) of abs change |
| 8 | vix | snapshot.vix |
| 9 | underlying_return_1 | pct_change(1) |
| 10 | underlying_return_3 | pct_change(3) |
| 11 | distance_to_max_pe_oi | (spot - maxPeStrike) / spot |
| 12 | distance_to_max_ce_oi | (maxCeStrike - spot) / spot |
| 13 | hour_of_day | timestamp.hour + minute/60 |
| 14 | day_of_week | timestamp.dayofweek |
| 15 | spread_pe_ce_max_oi | (maxCeStrike - maxPeStrike) / spot |

### 2.2 Labels (target)

`features.py::generate_labels()`: 30-min lookahead (5 snapshots × 6 min):
- **BULLISH** if return ≥ +0.3%
- **BEARISH** if return ≤ -0.3%
- **NEUTRAL** otherwise

### 2.3 Sequence windowing

`features.py::create_sequences()`: Each sample = 10 timesteps (60 min lookback), output shape `(samples, 10, 15)`.

## Phase 3: LSTM Model Training ✅ (Implemented in `ml-sidecar/train.py`)

### 3.1 Model architecture (`train.py::build_model()`)

```python
model = Sequential([
    LSTM(64, return_sequences=True, input_shape=(10, 15)),
    Dropout(0.2),
    LSTM(32, return_sequences=False),
    Dropout(0.2),
    Dense(16, activation='relu'),
    Dense(3, activation='softmax')
])
```

- Loss: `categorical_crossentropy`, Optimizer: `Adam(0.001)`
- Early stopping: patience=10, restore_best_weights
- ReduceLROnPlateau: factor=0.5, patience=5
- Train/val split: 85/15 (sequential, no shuffle)

### 3.2 Training workflow

Triggered by `POST /train` → fetches CSV from Java app's `/api/v1/data/export/csv` → engineers features → generates labels → creates sequences → trains → exports ONNX to `/app/models/lstm_model.onnx`

## Phase 4: Python Sidecar API ✅ (Implemented)

### 4.1 Files created

| File | Purpose |
|---|---|
| `ml-sidecar/main.py` | FastAPI app: `POST /predict`, `POST /train`, `GET /health` |
| `ml-sidecar/features.py` | `engineer_features()`, `generate_labels()`, `create_sequences()`, `build_latest_sequence()` |
| `ml-sidecar/train.py` | `build_model()`, `train()` — builds LSTM, trains, exports ONNX |
| `ml-sidecar/requirements.txt` | All Python dependencies (tensorflow, onnxruntime, fastapi, etc.) |
| `ml-sidecar/Dockerfile` | Python 3.11-slim with healthcheck |
| `ml-sidecar/.dockerignore` | Excludes cache/venv |

### 4.2 API Endpoints

| Endpoint | Method | Input | Output |
|---|---|---|---|
| `/predict` | POST | `{snapshots: [10 snapshots with OI data]}` | `{direction, confidence, probabilities}` |
| `/train` | POST | (none) | `{status, val_accuracy, val_loss, epochs, samples}` |
| `/health` | GET | (none) | `{model_loaded, model_path}` |

### 4.3 Graceful behavior

- If no model exists → `/predict` returns 503
- If data < 50 rows → `/train` returns `{status: "skipped"}`
- Model auto-loaded at startup and after each successful training

## Phase 5: Java Integration ✅ (Implemented)

### 5.1 LstmPredictionClient.java

`service/LstmPredictionClient.java` — Spring service using `WebClient`:
- `predict(List<OiDataSnapshot>)` → sends last 10 snapshots to sidecar, returns `LstmPredictionResponse`
- `triggerTraining()` → calls `POST /train` on sidecar, returns `LstmTrainResponse`
- Configurable via `ml.sidecar.url` and `ml.sidecar.enabled`
- 5-second timeout for predict, 5-minute timeout for training
- 1 retry on failure, graceful null return on any error

### 5.2 DTOs created

| File | Purpose |
|---|---|
| `dto/LstmPredictionRequest.java` | Request DTO with `LstmSnapshot` list |
| `dto/LstmPredictionResponse.java` | `{direction, confidence, probabilities}` |
| `dto/LstmTrainResponse.java` | `{status, val_accuracy, val_loss, epochs, samples, reason}` |

### 5.3 analyzeAndPredict() integration

After rule-based direction + strike selection, if LSTM is enabled and strikes exist:
1. Calls `lstmClient.predict(snapshots)`
2. If LSTM confidence × 100 > rule confidence → uses LSTM direction
3. Reasoning updated with which source was chosen and at what confidence
4. If LSTM unavailable → rules continue unchanged

### 5.4 Scheduler integration

`IntradayOiScheduler.marketCloseSummary()` at 4:00 PM:
1. Sends market summary via Telegram (existing)
2. Calls `lstmClient.triggerTraining()`
3. If success → sends Telegram with accuracy + sample count
4. If skipped → logs reason

## Phase 6: Validation & Gradual Rollout

### 6.1 Prerequisites

1. Build the Docker images: `docker compose build`
2. Start with `ML_SIDECAR_ENABLED=false` — data collection runs, LSTM disabled
3. Wait 2-4 weeks until H2 has sufficient data (target: >1000 snapshots)
4. Manually trigger training: `curl -X POST http://ml-sidecar:8000/train`
5. If training succeeds → set `ML_SIDECAR_ENABLED=true`

### 6.2 Shadow mode

While `ML_SIDECAR_ENABLED=false`, the sidecar still collects data and can be trained. The `POST /train` endpoint is callable independently. Use this to validate accuracy before enabling.

### 6.3 Override only on high confidence

Once enabled, the LSTM only overrides when its confidence exceeds the rule-based confidence. You can monitor this in logs:
```
LSTM overrides (BULLISH, 82.3%)
Rules prevail (NEUTRAL, 50.0%) over LSTM (BULLISH, 45.0%)
```

### 6.4 Monitoring

- `GET /api/v1/data/stats` — check snapshot count
- `GET /ml-sidecar:8000/health` — check model loaded
- Telegram receives training results automatically at 4 PM

## Configuration

### application.properties
```properties
ml.sidecar.url=http://ml-sidecar:8000
ml.sidecar.enabled=${ML_SIDECAR_ENABLED:false}
```

### .env
```
ML_SIDECAR_ENABLED=false
```

Set to `true` only after first successful training.

## Complete File Map

```
kite-java/
├── ml-sidecar/
│   ├── main.py              # FastAPI app (predict/train/health endpoints)
│   ├── features.py           # Feature engineering + label generation
│   ├── train.py              # LSTM model building + training + ONNX export
│   ├── requirements.txt      # Python dependencies
│   ├── Dockerfile            # Container build
│   └── .dockerignore
├── src/main/java/com/kite/trading/
│   ├── dto/
│   │   ├── LstmPredictionRequest.java
│   │   ├── LstmPredictionResponse.java
│   │   └── LstmTrainResponse.java
│   ├── entity/
│   │   └── OiSnapshotEntity.java
│   ├── repository/
│   │   └── OiSnapshotRepository.java
│   ├── service/
│   │   ├── LstmPredictionClient.java    # HTTP client to sidecar
│   │   └── OiAnalysisService.java       # Modified: persist + LSTM call
│   └── controller/
│       └── DataExportController.java     # CSV export + stats
└── docker-compose.yml                    # Added ml-sidecar service
```

## Data Flow Summary

```
Every 6 minutes:
1. fetchAndRecordOi() → snapshot → H2 persist → in-memory list
2. analyzeAndPredict():
   a. Rule-based direction (unchanged)
   b. If enabled: LSTM sidecar predicts from last 10 snapshots
   c. Higher confidence wins

4:00 PM:
1. Market summary via Telegram
2. POST /train → sidecar fetches CSV from Java → trains LSTM → saves ONNX
3. Sidecar auto-loads new model for next day
```

## Key Design Decisions

| Decision | Choice | Why |
|---|---|---|
| Sidecar language | Python | LSTM ecosystem (TensorFlow/Keras/ONNX) |
| Communication | HTTP REST | Simple, debuggable, no broker needed |
| Model format | ONNX | Fast inference, small, framework-agnostic |
| Persistence | H2 (file-based) | Zero extra services, same volume pattern as logs |
| Training trigger | 4 PM scheduled | Labels finalized, 12+ hours before next market open |
| LSTM override | Higher confidence wins | Natural guard: low-confidence LSTM won't override rules |
| Fallback | Rules always | LSTM failure/crash never blocks trading |
