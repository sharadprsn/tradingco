"""
Final attempt: try all possible NSE data endpoints
"""
from nsepython import nsefetch, nse_index, security_wise_archive, derivative_history, nse_fno

# 1. Try nsefetch for historical index data
print("1. Historical index data...")
try:
    url = "https://www.nseindia.com/api/historical/indices?index=NIFTY%2050&from=01-01-2026&to=10-07-2026"
    r = nsefetch(url)
    if r:
        print(f"   Got response, type={type(r).__name__}")
        if isinstance(r, dict):
            print(f"   Keys: {list(r.keys())[:5]}")
            print(f"   Sample: {str(r)[:500]}")
    else:
        print("   None")
except Exception as e:
    print(f"   Error: {e}")

# 2. Try derivative_history
print("\n2. derivative_history...")
try:
    from datetime import datetime
    r = derivative_history("NIFTY", "01-01-2026", "10-07-2026")
    print(f"   Result: {type(r).__name__}")
    if r is not None:
        print(f"   {str(r)[:500]}")
except Exception as e:
    print(f"   Error: {e}")

# 3. Try security_wise_archive with symbol
print("\n3. security_wise_archive...")
try:
    r = security_wise_archive("03072026", "FO", "NIFTY")
    print(f"   Result: {type(r).__name__}")
    if r is not None:
        print(f"   {str(r)[:500]}")
except Exception as e:
    print(f"   Error: {e}")

# 4. Try nse_index differently
print("\n4. nse_index...")
try:
    idx = nse_index()
    print(f"   Result: {type(idx).__name__}")
    if idx is not None:
        if isinstance(idx, dict):
            print(f"   Keys: {list(idx.keys())[:5]}")
        elif hasattr(idx, 'columns'):
            print(f"   Shape: {idx.shape}, Columns: {list(idx.columns)}")
except Exception as e:
    print(f"   Error: {e}")

# 5. Try fnolist
print("\n5. nse_fno...")
try:
    r = nse_fno()
    print(f"   Result: {type(r).__name__}")
    if r:
        print(f"   {str(r)[:500]}")
except Exception as e:
    print(f"   Error: {e}")
