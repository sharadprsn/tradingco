import requests
import json

session = requests.Session()
session.headers.update({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept": "application/json, text/plain, */*",
    "Referer": "https://www.nseindia.com/",
})

# First get cookies from NSE homepage
r1 = session.get("https://www.nseindia.com", timeout=15)
print(f"Homepage: {r1.status_code}, cookies: {len(session.cookies)}")

# Now try the option chain API
url = "https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY"
r2 = session.get(url, timeout=15)
print(f"\nOption chain API: {r2.status_code}")
if r2.status_code == 200:
    data = r2.json()
    records = data.get("records", {})
    underlying = records.get("underlyingValue")
    print(f"Underlying: {underlying}")
    
    all_data = records.get("data", [])
    print(f"Strikes: {len(all_data)}")
    
    if all_data:
        keys = list(all_data[0].keys())
        print(f"Sample keys: {keys}")
        
        # Aggregate OI
        pe_total_oi = 0
        ce_total_oi = 0
        pe_total_chg = 0
        ce_total_chg = 0
        
        for strike in all_data:
            pe = strike.get("PE")
            ce = strike.get("CE")
            if pe:
                pe_total_oi += pe.get("openInterest", 0) or 0
                pe_total_chg += pe.get("changeinOpenInterest", 0) or 0
            if ce:
                ce_total_oi += ce.get("openInterest", 0) or 0
                ce_total_chg += ce.get("changeinOpenInterest", 0) or 0
        
        print(f"\nPE Total OI: {pe_total_oi}")
        print(f"CE Total OI: {ce_total_oi}")
        print(f"PE OI Change: {pe_total_chg}")
        print(f"CE OI Change: {ce_total_chg}")
        
        if ce_total_oi > 0:
            pcr = pe_total_oi / ce_total_oi
            print(f"PCR (OI): {pcr:.4f}")
        
        total_chg = abs(pe_total_chg) + abs(ce_total_chg)
        if total_chg > 0:
            pe_pct = pe_total_chg / total_chg * 100
            print(f"PE OI Change %: {pe_pct:.1f}%")
            
            if pe_pct > 60:
                print(f"\n>>> DIRECTION: BULLISH (PE buildup {pe_pct:.1f}%)")
                print(f">>> Strategy: DIRECTIONAL PUT SELLING")
            elif pe_pct < 40:
                print(f"\n>>> DIRECTION: BEARISH (CE buildup {100-pe_pct:.1f}%)")
                print(f">>> Strategy: DIRECTIONAL CALL SELLING")
            else:
                print(f"\n>>> DIRECTION: NEUTRAL")
                print(f">>> Strategy: SHORT STRANGLE")
else:
    print(f"Response: {r2.text[:500]}")
