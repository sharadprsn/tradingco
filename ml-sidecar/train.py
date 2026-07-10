"""LSTM model training and ONNX export."""

import os
import logging

import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.model_selection import train_test_split
import tf2onnx

from features import engineer_features, generate_labels, create_sequences, FEATURE_COLS, SEQ_LENGTH

logger = logging.getLogger(__name__)

MODEL_DIR = "/app/models"
MODEL_PATH = os.path.join(MODEL_DIR, "lstm_model.onnx")


def build_model(num_features: int) -> tf.keras.Model:
    """Build a 2-layer LSTM classification model."""
    model = tf.keras.Sequential([
        tf.keras.layers.LSTM(64, return_sequences=True, input_shape=(SEQ_LENGTH, num_features)),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.LSTM(32, return_sequences=False),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(3, activation="softmax"),
    ])
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def train(df: pd.DataFrame) -> dict:
    """Train the LSTM model on historical data.

    Returns training metrics.
    """
    logger.info("Starting LSTM training with %d rows", len(df))

    df_feat = engineer_features(df)
    labels = generate_labels(df_feat)
    X, y = create_sequences(df_feat, labels)

    if len(X) < 50:
        logger.warning("Only %d samples available, need at least 50", len(X))
        return {"status": "skipped", "reason": f"Insufficient data: {len(X)} samples < 50"}

    X_train, X_val, y_train, y_val = train_test_split(
        X, y, test_size=0.15, shuffle=False
    )

    model = build_model(X.shape[2])

    early_stop = tf.keras.callbacks.EarlyStopping(
        monitor="val_loss", patience=10, restore_best_weights=True
    )
    reduce_lr = tf.keras.callbacks.ReduceLROnPlateau(
        monitor="val_loss", factor=0.5, patience=5, min_lr=1e-6
    )

    history = model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=100,
        batch_size=32,
        callbacks=[early_stop, reduce_lr],
        verbose=0,
    )

    val_loss, val_acc = model.evaluate(X_val, y_val, verbose=0)

    os.makedirs(MODEL_DIR, exist_ok=True)

    spec = (tf.TensorSpec((None, SEQ_LENGTH, X.shape[2]), tf.float32, name="input"),)
    model_proto, _ = tf2onnx.convert.from_keras(model, input_signature=spec)
    with open(MODEL_PATH, "wb") as f:
        f.write(model_proto.SerializeToString())

    epochs_run = len(history.history["loss"])
    logger.info(
        "Training complete: val_acc=%.4f, val_loss=%.4f, epochs=%d, samples=%d",
        val_acc, val_loss, epochs_run, len(X),
    )

    return {
        "status": "success",
        "val_accuracy": float(val_acc),
        "val_loss": float(val_loss),
        "epochs": epochs_run,
        "samples": len(X),
        "model_path": MODEL_PATH,
    }
