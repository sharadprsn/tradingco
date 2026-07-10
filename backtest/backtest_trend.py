import yfinance as yf
import pandas as pd
import numpy as np
from datetime import datetime
import warnings
warnings.filterwarnings("ignore")

print("=" * 72)
print("  IMPROVED DIRECTIONAL OPTION SELLING - TREND FILTER TEST")
print("=" * 72)

# -- 1. Fetch Data --
nifty = yf.download("^NSEI", period="1y", interval="1d")
nifty.columns = ["Close", "High", "Low", "Open", "Volume"]
nifty.index = pd.to_datetime(nifty.index)

df = nifty.copy()
df["Ret"] = df["Close"].pct_change() * 100
df["Vol_MA20"] = df["Volume"].rolling(20).mean()
df["Vol_Ratio"] = df["Volume"] / df["Vol_MA20"]
df["EMA20"] = df["Close"].ewm(span=20, adjust=False).mean()

# Baseline signal function
def baseline_signal_fn(row):
    ret = row["Ret"]
    vr = row["Vol_Ratio"]
    if pd.isna(ret) or pd.isna(vr):
        return "NEUTRAL", 50
    if ret > 0.3 and (vr > 0.8 or ret > 0.5):
        conf = min(abs(ret) * 25 + 50, 92)
        return "BULLISH", round(conf)
    if ret < -0.3 and (vr > 0.8 or ret < -0.5):
        conf = min(abs(ret) * 25 + 50, 92)
        return "BEARISH", round(conf)
    return "NEUTRAL", 50

# Apply baseline
signals = df.apply(baseline_signal_fn, axis=1, result_type="expand")
df["Signal"] = signals[0].values
df["Confidence"] = signals[1].values

# Dynamic Trend Signal Function (Improved)
def trend_filtered_signal(row):
    sig = row["Signal"]
    conf = row["Confidence"]
    close = row["Close"]
    ema = row["EMA20"]
    
    if sig == "BULLISH" and close < ema:
        # Filter out bullish trade in a bearish trend
        return "NEUTRAL", 50
    if sig == "BEARISH" and close > ema:
        # Filter out bearish trade in a bullish trend
        return "NEUTRAL", 50
    return sig, conf

filtered_signals = df.apply(trend_filtered_signal, axis=1, result_type="expand")
df["Filtered_Signal"] = filtered_signals[0].values
df["Filtered_Confidence"] = filtered_signals[1].values

def run_simulation(signal_col, conf_col):
    results = []
    for i in range(len(df) - 1):
        row = df.iloc[i]
        next_ret = df.iloc[i + 1]["Ret"]
        sig = row[signal_col]
        conf = row[conf_col]
        
        if sig == "BULLISH":
            correct = next_ret > 0
            magnitude = next_ret
        elif sig == "BEARISH":
            correct = next_ret < 0
            magnitude = -next_ret
        else:
            correct = abs(next_ret) < 0.5
            magnitude = 0.5 - abs(next_ret)
            
        results.append({
            "Correct": correct,
            "Magnitude": magnitude,
            "Signal": sig,
            "Confidence": conf
        })
    return pd.DataFrame(results)

# Run simulations
baseline_bt = run_simulation("Signal", "Confidence")
filtered_bt = run_simulation("Filtered_Signal", "Filtered_Confidence")

# Output results
print(f"Total Trading Days: {len(df) - 1}")
print("\n[Baseline Strategy Performance]")
print(f"  Overall Accuracy: {baseline_bt['Correct'].mean()*100:.2f}%")
print(f"  BULLISH Accuracy: {baseline_bt[baseline_bt['Signal'] == 'BULLISH']['Correct'].mean()*100:.2f}% (Count: {len(baseline_bt[baseline_bt['Signal'] == 'BULLISH'])})")
print(f"  BEARISH Accuracy: {baseline_bt[baseline_bt['Signal'] == 'BEARISH']['Correct'].mean()*100:.2f}% (Count: {len(baseline_bt[baseline_bt['Signal'] == 'BEARISH'])})")
print(f"  NEUTRAL Accuracy: {baseline_bt[baseline_bt['Signal'] == 'NEUTRAL']['Correct'].mean()*100:.2f}% (Count: {len(baseline_bt[baseline_bt['Signal'] == 'NEUTRAL'])})")

print("\n[Trend-Filtered Strategy Performance (Filter: 20-Day EMA)]")
print(f"  Overall Accuracy: {filtered_bt['Correct'].mean()*100:.2f}%")
print(f"  BULLISH Accuracy: {filtered_bt[filtered_bt['Signal'] == 'BULLISH']['Correct'].mean()*100:.2f}% (Count: {len(filtered_bt[filtered_bt['Signal'] == 'BULLISH'])})")
print(f"  BEARISH Accuracy: {filtered_bt[filtered_bt['Signal'] == 'BEARISH']['Correct'].mean()*100:.2f}% (Count: {len(filtered_bt[filtered_bt['Signal'] == 'BEARISH'])})")
print(f"  NEUTRAL Accuracy: {filtered_bt[filtered_bt['Signal'] == 'NEUTRAL']['Correct'].mean()*100:.2f}% (Count: {len(filtered_bt[filtered_bt['Signal'] == 'NEUTRAL'])})")

# Confidence Filter Compare
print("\n[Confidence Filtering Comparison]")
print("Threshold | Baseline Acc (Count) | Filtered Acc (Count)")
print("-" * 55)
for thresh in [50, 60, 70, 80]:
    b_sub = baseline_bt[baseline_bt["Confidence"] >= thresh]
    f_sub = filtered_bt[filtered_bt["Confidence"] >= thresh]
    print(f"  >= {thresh:2d}  |   {b_sub['Correct'].mean()*100:5.2f}% ({len(b_sub):4d})   |   {f_sub['Correct'].mean()*100:5.2f}% ({len(f_sub):4d})")
