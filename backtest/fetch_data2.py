import pandas as pd
from nsepython import get_bhavcopy, nse_index, option_chain
from datetime import datetime, timedelta
import yfinance as yf

today = datetime.now()

# 1. Fetch Nifty historical prices via yfinance
print("=" * 60)
print("Fetching Nifty historical prices via yfinance...")
try:
    nifty = yf.download("^NSEI", period="1y", interval="1d")
    print(f"Nifty shape: {nifty.shape}")
    print(nifty.tail(3))
    nifty.to_csv("backtest/nifty_history.csv")
    print("Saved backtest/nifty_history.csv")
except Exception as e:
    print(f"yfinance error: {e}")

# 2. Try to get bhavcopy via nsepython
print("\n" + "=" * 60)
print("Trying get_bhavcopy for recent dates...")
for days_ago in [3, 4, 5, 7, 10, 14]:
    d = today - timedelta(days=days_ago)
    if d.weekday() >= 5:
        continue
    date_str = d.strftime("%d%m%Y")
    print(f"\nTrying get_bhavcopy for {date_str} ({d.strftime('%A')})...")
    try:
        df = get_bhavcopy(date_str, segment="FO")
        if df is not None and len(df) > 0:
            print(f"Columns: {list(df.columns)}")
            print(f"Rows: {len(df)}")
            # Filter for NIFTY options
            nifty_opts = df[df["SYMBOL"] == "NIFTY"] if "SYMBOL" in df.columns else df
            print(f"NIFTY options: {len(nifty_opts)} rows")
            print(nifty_opts.head(5))
            nifty_opts.to_csv(f"backtest/fo_{date_str}.csv", index=False)
            print(f"Saved backtest/fo_{date_str}.csv")
            break
        else:
            print("Empty or None result")
    except Exception as e:
        print(f"Error: {type(e).__name__}: {e}")

# 3. Try option_chain for current NIFTY data
print("\n" + "=" * 60)
print("Fetching NIFTY option chain...")
try:
    oc = option_chain("NIFTY")
    if oc is not None:
        print(f"Type: {type(oc)}")
        if isinstance(oc, dict):
            print(f"Keys: {list(oc.keys())[:10]}")
        elif isinstance(oc, pd.DataFrame):
            print(f"Shape: {oc.shape}")
            print(f"Columns: {list(oc.columns)}")
            print(oc.head(3))
except Exception as e:
    print(f"option_chain error: {e}")

print("\nDone.")
