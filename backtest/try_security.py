"""
Try security_wise_archive for NIFTY
"""
from nsepython import security_wise_archive
import pandas as pd

# Try various symbol formats
symbols = ["NIFTY", "NIFTY 50", "NIFTY50", "^NSEI"]
for sym in symbols:
    print(f"\nTrying security_wise_archive for symbol='{sym}'...")
    try:
        df = security_wise_archive("03-07-2026", "10-07-2026", sym)
        if df is not None and len(df) > 0:
            print(f"  SUCCESS! Shape: {df.shape}")
            print(f"  Columns: {list(df.columns)}")
            print(df.to_string())
            break
        else:
            print(f"  Empty: {df}")
    except Exception as e:
        print(f"  Error: {e}")

# Also try derivative_history
print("\n\n=== derivative_history ===")
from nsepython import derivative_history
try:
    sig = inspect.signature(derivative_history)
    print(f"Signature: {sig}")
except Exception as e:
    import inspect
    sig = inspect.signature(derivative_history)
    print(f"Signature: {sig}")
