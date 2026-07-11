"""Model training using Random Forest classifier (lightweight alternative to LSTM)."""

import os
import logging

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split

from features import engineer_features, generate_labels, create_sequences, FEATURE_COLS, SEQ_LENGTH

logger = logging.getLogger(__name__)

MODEL_DIR = "/app/models"
MODEL_PATH = os.path.join(MODEL_DIR, "model.joblib")


def train(df: pd.DataFrame) -> dict:
    """Train a Random Forest classifier on historical data.

    Returns training metrics.
    """
    logger.info("Starting Random Forest training with %d rows", len(df))

    df_feat = engineer_features(df)
    labels = generate_labels(df_feat)
    X, y = create_sequences(df_feat, labels)

    if len(X) < 50:
        logger.warning("Only %d samples available, need at least 50", len(X))
        return {"status": "skipped", "reason": f"Insufficient data: {len(X)} samples < 50"}

    # Flatten sequences (SEQ_LENGTH x num_features) -> 1D for sklearn
    X_flat = X.reshape(X.shape[0], -1)

    X_train, X_val, y_train, y_val = train_test_split(
        X_flat, y, test_size=0.15, shuffle=False
    )

    # Convert one-hot to class labels
    y_train_labels = np.argmax(y_train, axis=1)
    y_val_labels = np.argmax(y_val, axis=1)

    model = RandomForestClassifier(
        n_estimators=200,
        max_depth=15,
        min_samples_leaf=5,
        n_jobs=1,
        random_state=42,
        class_weight="balanced",
    )
    model.fit(X_train, y_train_labels)

    val_acc = model.score(X_val, y_val_labels)

    os.makedirs(MODEL_DIR, exist_ok=True)
    joblib.dump(model, MODEL_PATH)

    logger.info(
        "Training complete: val_acc=%.4f, samples=%d",
        val_acc, len(X),
    )

    return {
        "status": "success",
        "val_accuracy": float(val_acc),
        "val_loss": 1.0 - float(val_acc),
        "epochs": 200,
        "samples": len(X),
        "model_path": MODEL_PATH,
    }
