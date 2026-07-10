import pandas as pd
from nsepython import fobhav, nse_index_history
from datetime import datetime, timedelta
import yfinance as yf

# Try to get F&O bhavcopy using nsepython library
today = datetime.now()
for days_ago in [3, 4, 5, 7, 10, 14]:
    d = today - timedelta(days=days_ago)
    if d.weekday() >= 5:
        continue
    date_str = d.strftime("%d%m%Y")
    print(f"Trying fobhav for {date_str} ({d.strftime('%A')})...")
    try:
        df = fobhav(date_str)
        if df is not None and len(df) > 0:
            print(f"Columns: {list(df.columns)}")
            print(f"Rows: {len(df)}")
            print(df.head(3))
            # Save it
            df.to_csv(f"backtest/fo_{date_str}.csv", index=False)
            print(f"Saved backtest/fo_{date_str}.csv")
            break
        else:
            print(f"Empty result")
    except Exception as e:
        print(f"Error: {type(e).__name__}: {e}")

# Also try to get Nifty index history
print("\n\nFetching Nifty history via yfinance...")
try:
    nifty = yf.download("^NSEI", period="6mo", interval="1d")
    print(f"Nifty data shape: {nifty.shape}")
    print(nifty.tail(5))
    nifty.to_csv("backtest/nifty_history.csv")
    print("Saved backtest/nifty_history.csv")
except Exception as e:
    print(f"yfinance error: {e}")

# Also try NSE index history via nsepython
print("\n\nFetching Nifty via nse_index_history...")
try:
    nifty2 = nse_index_history("NIFTY 50", today - timedelta(days=180), today)
    if nifty2 is not None and len(nifty2) > 0:
        print(f"Nifty2 shape: {nifty2.shape}")
        print(nifty2.tail(5))
except Exception as e:
    print(f"nse_index_history error: {e}")
