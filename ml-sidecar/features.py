"""Feature engineering and label generation for LSTM model."""

import numpy as np
import pandas as pd

FEATURE_COLS = [
    "pcr",
    "pcr_change_1",
    "pcr_change_3",
    "pe_oi_change",
    "ce_oi_change",
    "oi_change_ratio",
    "oi_change_dominance_pct",
    "vix",
    "underlying_return_1",
    "underlying_return_3",
    "distance_to_max_pe_oi",
    "distance_to_max_ce_oi",
    "hour_of_day",
    "day_of_week",
    "spread_pe_ce_max_oi",
    "market_sentiment",
]

SEQ_LENGTH = 10
LOOKAHEAD = 5  # ~30 min ahead (5 snapshots * 6 min)
BULLISH_THRESHOLD = 0.003
BEARISH_THRESHOLD = -0.003


def engineer_features(df: pd.DataFrame) -> pd.DataFrame:
    """Add all derived feature columns to the dataframe.

    Expects columns: timestamp, underlyingValue, totalPeOi, totalCeOi,
    totalPeOiChange, totalCeOiChange, pcr, largestPeOiStrike, largestCeOiStrike,
    vix, indexOpen, indexHigh, indexLow, marketSentiment
    """
    result = df.copy().sort_values("timestamp").reset_index(drop=True)

    # Core features
    result["pe_oi_change"] = result["totalPeOiChange"].fillna(0)
    result["ce_oi_change"] = result["totalCeOiChange"].fillna(0)
    result["pcr"] = result["pcr"].fillna(1.0)
    result["vix"] = result["vix"].fillna(15.0)

    # Market sentiment (-1 to 1)
    result["market_sentiment"] = result["marketSentiment"].fillna(0.0)

    # PCR changes
    result["pcr_change_1"] = result["pcr"].diff(1).fillna(0)
    result["pcr_change_3"] = result["pcr"].diff(3).fillna(0)

    # OI change ratio
    total_change = (
        result["pe_oi_change"].abs() + result["ce_oi_change"].abs()
    )
    result["oi_change_ratio"] = np.where(
        total_change > 0,
        result["pe_oi_change"] / total_change,
        0.5,
    )

    # OI change dominance percentage
    pe_pct = np.where(
        total_change > 0,
        result["pe_oi_change"].abs() / total_change * 100,
        50.0,
    )
    result["oi_change_dominance_pct"] = np.where(
        pe_pct >= 50, pe_pct, 100 - pe_pct
    )

    # Underlying returns
    result["underlying_return_1"] = (
        result["underlyingValue"].pct_change(1).fillna(0)
    )
    result["underlying_return_3"] = (
        result["underlyingValue"].pct_change(3).fillna(0)
    )

    # Distance to max OI strikes
    result["distance_to_max_pe_oi"] = (
        result["underlyingValue"] - result["largestPeOiStrike"].fillna(result["underlyingValue"])
    ) / result["underlyingValue"]
    result["distance_to_max_ce_oi"] = (
        result["largestCeOiStrike"].fillna(result["underlyingValue"]) - result["underlyingValue"]
    ) / result["underlyingValue"]
    result["spread_pe_ce_max_oi"] = (
        result["largestCeOiStrike"].fillna(0) - result["largestPeOiStrike"].fillna(0)
    ) / result["underlyingValue"]

    # Time features
    result["hour_of_day"] = pd.to_datetime(result["timestamp"]).dt.hour + (
        pd.to_datetime(result["timestamp"]).dt.minute / 60.0
    )
    result["day_of_week"] = pd.to_datetime(result["timestamp"]).dt.dayofweek

    return result


def generate_labels(df: pd.DataFrame) -> pd.Series:
    """Generate 3-class labels based on 30-min ahead price movement.

    Returns: 0=BULLISH, 1=BEARISH, 2=NEUTRAL
    """
    future_price = df["underlyingValue"].shift(-LOOKAHEAD)
    returns = (future_price / df["underlyingValue"] - 1).fillna(0)

    labels = np.full(len(returns), 2)  # default NEUTRAL
    labels[returns >= BULLISH_THRESHOLD] = 0  # BULLISH
    labels[returns <= BEARISH_THRESHOLD] = 1  # BEARISH

    return pd.Series(labels, index=df.index)


def create_sequences(
    df: pd.DataFrame, labels: pd.Series = None
) -> tuple[np.ndarray, np.ndarray | None]:
    """Create (X, y) sequences for LSTM training.

    X shape: (num_samples, SEQ_LENGTH, num_features)
    y shape: (num_samples, 3)  one-hot, or None if labels not provided
    """
    feature_values = df[FEATURE_COLS].values
    num_features = len(FEATURE_COLS)

    X, y = [], []
    for i in range(len(df) - SEQ_LENGTH - LOOKAHEAD):
        X.append(feature_values[i : i + SEQ_LENGTH])
        if labels is not None:
            label = labels.iloc[i + SEQ_LENGTH]
            one_hot = np.zeros(3, dtype=np.float32)
            one_hot[int(label)] = 1.0
            y.append(one_hot)

    X_arr = np.array(X, dtype=np.float32)
    y_arr = np.array(y, dtype=np.float32) if y else None
    return X_arr, y_arr


def build_latest_sequence(df: pd.DataFrame) -> np.ndarray:
    """Build the latest sequence from the most recent data for inference.

    Returns array of shape (1, SEQ_LENGTH, num_features)
    """
    if len(df) < SEQ_LENGTH:
        return None

    feature_values = df[FEATURE_COLS].values
    seq = feature_values[-SEQ_LENGTH:]
    return np.expand_dims(seq, axis=0).astype(np.float32)
