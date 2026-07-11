"""FastAPI sidecar for LSTM-based direction prediction + daily retraining."""

import io
import logging
import os

import httpx
import numpy as np
import onnxruntime as ort
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from features import engineer_features, build_latest_sequence, FEATURE_COLS, SEQ_LENGTH
from sentiment import get_market_sentiment
from train import train, MODEL_PATH

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(title="LSTM Direction Predictor", version="1.0.0")

JAVA_APP_URL = os.environ.get("JAVA_APP_URL", "http://kite-trading:443")
JAVA_CSV_URL = f"{JAVA_APP_URL}/api/v1/data/export/csv"
TRAIN_TIMEOUT_S = int(os.environ.get("TRAIN_TIMEOUT_S", "300"))

session = None
_model_lock = False


class SnapshotData(BaseModel):
    underlyingValue: float
    totalPeOi: float
    totalCeOi: float
    totalPeOiChange: float
    totalCeOiChange: float
    pcr: float
    largestPeOiStrike: float
    largestCeOiStrike: float
    vix: float | None = None
    indexOpen: float | None = None
    indexHigh: float | None = None
    indexLow: float | None = None
    marketSentiment: float | None = None


class PredictRequest(BaseModel):
    snapshots: list[SnapshotData]


class PredictResponse(BaseModel):
    direction: str
    confidence: float
    probabilities: list[float]


class TrainResponse(BaseModel):
    status: str
    val_accuracy: float | None = None
    val_loss: float | None = None
    epochs: int | None = None
    samples: int | None = None
    reason: str | None = None


class SentimentResponse(BaseModel):
    score: float
    label: str
    headlines: list[str]
    cached: bool


def _load_model():
    global session
    if not os.path.exists(MODEL_PATH):
        logger.warning("No model found at %s", MODEL_PATH)
        session = None
        return
    try:
        session = ort.InferenceSession(MODEL_PATH)
        logger.info("Model loaded from %s", MODEL_PATH)
    except Exception as e:
        logger.error("Failed to load model: %s", e)
        session = None


def _snapshots_to_dataframe(snapshots: list[dict]) -> pd.DataFrame:
    records = []
    for i, snap in enumerate(snapshots):
        records.append({
            "timestamp": f"T{i}",
            "underlyingValue": snap["underlyingValue"],
            "totalPeOi": snap["totalPeOi"],
            "totalCeOi": snap["totalCeOi"],
            "totalPeOiChange": snap["totalPeOiChange"],
            "totalCeOiChange": snap["totalCeOiChange"],
            "pcr": snap["pcr"],
            "largestPeOiStrike": snap.get("largestPeOiStrike", snap["underlyingValue"]),
            "largestCeOiStrike": snap.get("largestCeOiStrike", snap["underlyingValue"]),
            "vix": snap.get("vix", 15.0),
            "indexOpen": snap.get("indexOpen"),
            "indexHigh": snap.get("indexHigh"),
            "indexLow": snap.get("indexLow"),
            "marketSentiment": snap.get("marketSentiment", 0.0),
        })
    df = pd.DataFrame(records)
    return engineer_features(df)


@app.on_event("startup")
def startup():
    _load_model()


@app.get("/health")
def health():
    return {"model_loaded": session is not None, "model_path": MODEL_PATH}


@app.get("/sentiment", response_model=SentimentResponse)
def sentiment(force_refresh: bool = False):
    result = get_market_sentiment(force_refresh=force_refresh)
    return SentimentResponse(**result)


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    if session is None:
        raise HTTPException(status_code=503, detail="Model not loaded. Train first via /train")

    snap_dicts = [s.model_dump() for s in req.snapshots]
    if len(snap_dicts) < SEQ_LENGTH:
        raise HTTPException(
            status_code=400,
            detail=f"Need at least {SEQ_LENGTH} snapshots, got {len(snap_dicts)}",
        )

    df = _snapshots_to_dataframe(snap_dicts)
    seq = build_latest_sequence(df)
    if seq is None:
        raise HTTPException(status_code=400, detail="Failed to build feature sequence")

    input_name = session.get_inputs()[0].name
    probs = session.run(None, {input_name: seq})[0][0]
    label_idx = int(np.argmax(probs))
    confidence = float(probs[label_idx])
    direction = ["BULLISH", "BEARISH", "NEUTRAL"][label_idx]

    return PredictResponse(
        direction=direction,
        confidence=round(confidence, 4),
        probabilities=[round(float(p), 4) for p in probs],
    )


@app.post("/train", response_model=TrainResponse)
def train_endpoint():
    global _model_lock
    if _model_lock:
        return TrainResponse(status="skipped", reason="Training already in progress")

    _model_lock = True
    try:
        logger.info("Fetching data from %s", JAVA_CSV_URL)
        response = httpx.get(JAVA_CSV_URL, timeout=TRAIN_TIMEOUT_S)
        response.raise_for_status()

        df = pd.read_csv(io.StringIO(response.text))
        if len(df) < 50:
            return TrainResponse(
                status="skipped",
                reason=f"Insufficient data: {len(df)} rows < 50",
            )

        result = train(df)
        if result["status"] == "success":
            _load_model()

        return TrainResponse(**result)
    except Exception as e:
        logger.error("Training failed: %s", e)
        return TrainResponse(status="error", reason=str(e))
    finally:
        _model_lock = False
