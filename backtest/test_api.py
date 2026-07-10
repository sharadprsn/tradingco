from nsepython import security_wise_archive, nsefetch
from datetime import datetime

# Try security_wise_archive for FO segment
d = datetime(2026, 7, 3)
date_str = d.strftime("%d%m%Y")
print(f"Trying security_wise_archive for {date_str}...")
try:
    swa = security_wise_archive(date_str, "FO")
    if swa is not None:
        print(f"Type: {type(swa)}")
        if hasattr(swa, "shape"):
            print(f"Shape: {swa.shape}")
            print(f"Columns: {list(swa.columns)}")
            print(swa.head(5))
    else:
        print("Result is None")
except Exception as e:
    print(f"Error: {type(e).__name__}: {e}")

# Try nsefetch for option chain
print("\nTrying nsefetch for option chain...")
try:
    url = "https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY"
    resp = nsefetch(url)
    if resp:
        print(f"Type: {type(resp)}")
        if isinstance(resp, dict):
            print(f"Keys: {list(resp.keys())[:5]}")
            if "records" in resp and resp["records"]:
                uv = resp["records"].get("underlyingValue")
                print(f"Underlying: {uv}")
                data = resp["records"].get("data", [])
                print(f"Records: {len(data)}")
                if data:
                    keys = list(data[0].keys())
                    print(f"Sample keys: {keys}")
                    rec = data[0]
                    strike = rec.get("strikePrice")
                    print(f"First record: strikePrice={strike}")
                    pe = rec.get("PE") or {}
                    ce = rec.get("CE") or {}
                    pe_oi = pe.get("openInterest")
                    pe_chg = pe.get("changeinOpenInterest")
                    ce_oi = ce.get("openInterest")
                    ce_chg = ce.get("changeinOpenInterest")
                    print(f"  PE: OI={pe_oi}, Chg={pe_chg}")
                    print(f"  CE: OI={ce_oi}, Chg={ce_chg}")
                    
                    # Aggregate totals
                    pe_total_oi = 0
                    ce_total_oi = 0
                    pe_total_chg = 0
                    ce_total_chg = 0
                    for strike_data in data:
                        p = strike_data.get("PE")
                        c = strike_data.get("CE")
                        if p:
                            pe_total_oi += p.get("openInterest", 0) or 0
                            pe_total_chg += p.get("changeinOpenInterest", 0) or 0
                        if c:
                            ce_total_oi += c.get("openInterest", 0) or 0
                            ce_total_chg += c.get("changeinOpenInterest", 0) or 0
                    
                    print(f"\nPE Total OI: {pe_total_oi}")
                    print(f"CE Total OI: {ce_total_oi}")
                    print(f"PE OI Change: {pe_total_chg}")
                    print(f"CE OI Change: {ce_total_chg}")
                    pcr = pe_total_oi / ce_total_oi if ce_total_oi > 0 else 0
                    print(f"PCR: {pcr:.4f}")
                    total_chg = abs(pe_total_chg) + abs(ce_total_chg)
                    if total_chg > 0:
                        pe_pct = pe_total_chg / total_chg * 100
                        print(f"PE OI Change %: {pe_pct:.1f}%")
                        if pe_pct > 60:
                            print(f">>> DIRECTION: BULLISH (PE buildup {pe_pct:.1f}%)")
                        elif pe_pct < 40:
                            print(f">>> DIRECTION: BEARISH (CE buildup {100-pe_pct:.1f}%)")
                        else:
                            print(f">>> DIRECTION: NEUTRAL")
except Exception as e:
    print(f"Error: {type(e).__name__}: {e}")
