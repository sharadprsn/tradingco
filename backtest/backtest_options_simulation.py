import yfinance as yf
import pandas as pd
import numpy as np
from datetime import datetime, timedelta
import math
import warnings
warnings.filterwarnings("ignore")

# --- Black-Scholes Pricing Model ---
def norm_cdf(x):
    return 0.5 * (1.0 + math.erf(x / 1.4142135623730951))

def black_scholes(S, K, T, r, sigma, option_type):
    if T <= 0.0001:
        if option_type == "CE":
            return max(0.0, S - K)
        else:
            return max(0.0, K - S)
    sigma = max(sigma, 0.01)
    d1 = (math.log(S / K) + (r + 0.5 * sigma**2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    if option_type == "CE":
        return S * norm_cdf(d1) - K * math.exp(-r * T) * norm_cdf(d2)
    else:
        return K * math.exp(-r * T) * norm_cdf(-d2) - S * norm_cdf(-d1)

def find_strike_for_premium(S, target_prem, T, r, sigma, option_type, strike_interval=50):
    """Find the OTM strike price that has premium closest to target_prem."""
    best_strike = None
    min_diff = float("inf")
    
    # Scan strikes around ATM
    atm = round(S / strike_interval) * strike_interval
    for i in range(-20, 21):
        strike = atm + i * strike_interval
        # Ensure it is OTM
        if option_type == "PE" and strike >= S:
            continue
        if option_type == "CE" and strike <= S:
            continue
            
        p = black_scholes(S, strike, T, r, sigma, option_type)
        diff = abs(p - target_prem)
        if diff < min_diff:
            min_diff = diff
            best_strike = strike
            
    return best_strike

# --- Load and Prepare Data ---
print("Fetching Nifty 50 and India VIX data...")
nifty = yf.download("^NSEI", period="1y", interval="1d")
nifty.columns = ["Close", "High", "Low", "Open", "Volume"]

# Calculate rolling historical volatility as a proxy for IV (in case VIX is missing)
nifty["Ret"] = nifty["Close"].pct_change()
nifty["Hist_Vol"] = nifty["Ret"].rolling(20).std() * math.sqrt(252)
nifty["EMA20"] = nifty["Close"].ewm(span=20, adjust=False).mean()

# Replace any missing Volatility with standard 15%
nifty["Hist_Vol"] = nifty["Hist_Vol"].fillna(0.15)
nifty["Hist_Vol"] = nifty["Hist_Vol"].apply(lambda x: max(x, 0.10))

# We will simulate trading on each day
# Signal is determined by previous day's return + volume
nifty["Prev_Ret"] = nifty["Ret"].shift(1) * 100
nifty["Vol_MA20"] = nifty["Volume"].rolling(20).mean()
nifty["Vol_Ratio"] = nifty["Volume"] / nifty["Vol_MA20"].shift(1)

def get_signal(row):
    ret = row["Prev_Ret"]
    vr = row["Vol_Ratio"]
    if pd.isna(ret) or pd.isna(vr):
        return "NEUTRAL"
    if ret > 0.3 and (vr > 0.8 or ret > 0.5):
        return "BULLISH"
    if ret < -0.3 and (vr > 0.8 or ret < -0.5):
        return "BEARISH"
    return "NEUTRAL"

nifty["Signal"] = nifty.apply(get_signal, axis=1)

# Simulating 4 strategies:
# 1. Baseline: Hold to close, no SL/TP
# 2. Index SL: Exit if index moves 1% against us
# 3. Premium SL/TP: Exit if sold option premium goes to 2.5x (Stop Loss) or decays to 20% (Take Profit)
# 4. Trend-Filtered Premium SL/TP: Same as 3, but filter BULLISH if Close < EMA20 and BEARISH if Close > EMA20

def run_simulation():
    r = 0.07  # Risk-free rate
    lot_size = 65  # Nifty lot size
    capital = 1000000  # Rs. 10,00,000
    margin_per_lot = 60000  # Approx margin for credit spread
    lots = int(capital / margin_per_lot)  # Max lots we can trade (around 16 lots)
    
    # We will track results for each strategy
    modes = ["Baseline", "Index_SL", "Premium_SL_TP", "Trend_Premium_SL_TP"]
    pnl = {m: [] for m in modes}
    stats = {m: {"wins": 0, "losses": 0, "drawdowns": [], "total_pnl": 0} for m in modes}
    
    # We skip first 20 days due to indicators
    trading_data = nifty.iloc[20:-1]
    
    for idx in range(len(trading_data)):
        row = trading_data.iloc[idx]
        next_row = nifty.iloc[nifty.index.get_loc(row.name) + 1]
        
        # Day of week (0=Mon, ..., 4=Fri)
        day_of_week = row.name.weekday()
        # Days to expiry approximation (weekly options expire on Thursday)
        # Mon=3, Tue=2, Wed=1, Thu=0 (same-day expiry), Fri=4 days to expiry
        days_to_expiry = (3 - day_of_week) % 7
        if days_to_expiry == 0:
            days_to_expiry = 0.1  # Expiry day, represent as 0.1 days to avoid division by zero
            
        T_start = days_to_expiry / 365.0
        # End of day is 6 hours later (0.25 days of trading)
        T_end = max(0.0001, (days_to_expiry - 0.25) / 365.0)
        
        sig = row["Signal"]
        trend_aligned = True
        if sig == "BULLISH" and row["Close"] < row["EMA20"]:
            trend_aligned = False
        elif sig == "BEARISH" and row["Close"] > row["EMA20"]:
            trend_aligned = False
            
        # Entry price (Next day open)
        S_entry = next_row["Open"]
        vol = row["Hist_Vol"]
        
        # Select options to trade
        # We sell OTM at target premium ₹35
        # We buy hedge at ₹10
        if sig == "BULLISH":
            sold_type, sold_strike_dir = "PE", "PE"
        elif sig == "BEARISH":
            sold_type, sold_strike_dir = "CE", "CE"
        else:
            # For Neutral, we sell a Strangle (both CE and PE)
            sold_type = "STRANGLE"
            
        # Helper to calculate PnL for a single option sell+hedge trade on a day
        def calc_trade_pnl(mode):
            # If trend-filtered and not aligned, we sit in cash (0 PnL)
            if mode == "Trend_Premium_SL_TP" and not trend_aligned and sig != "NEUTRAL":
                return 0.0
                
            # If NEUTRAL, we sell both CE and PE
            if sold_type == "STRANGLE":
                sell_pe_strike = find_strike_for_premium(S_entry, 35, T_start, r, vol, "PE")
                sell_ce_strike = find_strike_for_premium(S_entry, 35, T_start, r, vol, "CE")
                if not sell_pe_strike or not sell_ce_strike:
                    return 0.0
                
                # Entry premiums
                pe_entry_prem = black_scholes(S_entry, sell_pe_strike, T_start, r, vol, "PE")
                ce_entry_prem = black_scholes(S_entry, sell_ce_strike, T_start, r, vol, "CE")
                sold_prem = pe_entry_prem + ce_entry_prem
                
                # Simulating intraday path (worst case of High/Low)
                # We check if SL is hit during the day.
                # High will hurt CE, Low will hurt PE.
                S_high = next_row["High"]
                S_low = next_row["Low"]
                S_close = next_row["Close"]
                
                # Intraday worst-case option prices
                pe_max_val = black_scholes(S_low, sell_pe_strike, T_end, r, vol, "PE")
                ce_max_val = black_scholes(S_high, sell_ce_strike, T_end, r, vol, "CE")
                
                # End of day prices
                pe_close_val = black_scholes(S_close, sell_pe_strike, T_end, r, vol, "PE")
                ce_close_val = black_scholes(S_close, sell_ce_strike, T_end, r, vol, "CE")
                
                # Exit evaluation
                if mode == "Baseline":
                    # Exit at close
                    loss = (pe_close_val + ce_close_val) - sold_prem
                    return -loss * lots * lot_size
                    
                elif mode == "Index_SL":
                    # Exit if index moves 1% against us from Open
                    # Strangle: exit if index moves 1% in either direction
                    move_pct = max(S_high - S_entry, S_entry - S_low) / S_entry
                    if move_pct >= 0.01:
                        # Hard stop triggered. Assume we exit at the stop level
                        trigger_price = S_entry * 1.01 if S_high - S_entry > S_entry - S_low else S_entry * 0.99
                        pe_exit = black_scholes(trigger_price, sell_pe_strike, T_end, r, vol, "PE")
                        ce_exit = black_scholes(trigger_price, sell_ce_strike, T_end, r, vol, "CE")
                        loss = (pe_exit + ce_exit) - sold_prem
                        return -loss * lots * lot_size
                    else:
                        loss = (pe_close_val + ce_close_val) - sold_prem
                        return -loss * lots * lot_size
                        
                else: # Premium SL/TP
                    # Check if the combined premium hit 2.5x (SL) or decayed to 20% (TP)
                    # If PE max + CE max premium reaches 2.5x sold_prem, we trigger SL
                    # (In reality, we monitor continuously, so we check if the worst-case intraday total premium hits SL)
                    max_combined_prem = pe_max_val + ce_max_val
                    min_combined_prem = pe_close_val + ce_close_val # approximation of decay
                    
                    if max_combined_prem >= 2.0 * sold_prem:
                        # SL Hit: exit at 2.0x premium
                        return -(2.0 - 1.0) * sold_prem * lots * lot_size
                    elif min_combined_prem <= 0.2 * sold_prem:
                        # TP Hit: exit at 0.2x premium (80% profit booked)
                        return (1.0 - 0.2) * sold_prem * lots * lot_size
                    else:
                        loss = (pe_close_val + ce_close_val) - sold_prem
                        return -loss * lots * lot_size
            else:
                # Directional spread
                sell_strike = find_strike_for_premium(S_entry, 35, T_start, r, vol, sold_type)
                # Hedge strike
                hedge_type = "PE" if sold_type == "PE" else "CE"
                hedge_strike = S_entry - 150 if sold_type == "PE" else S_entry + 150
                hedge_strike = round(hedge_strike / 50) * 50
                
                if not sell_strike:
                    return 0.0
                    
                entry_sold = black_scholes(S_entry, sell_strike, T_start, r, vol, sold_type)
                entry_hedge = black_scholes(S_entry, hedge_strike, T_start, r, vol, hedge_type)
                net_credit = entry_sold - entry_hedge
                
                S_high = next_row["High"]
                S_low = next_row["Low"]
                S_close = next_row["Close"]
                
                # Check adverse price movements
                S_worst = S_low if sold_type == "PE" else S_high
                S_best = S_high if sold_type == "PE" else S_low
                
                worst_sold = black_scholes(S_worst, sell_strike, T_end, r, vol, sold_type)
                worst_hedge = black_scholes(S_worst, hedge_strike, T_end, r, vol, hedge_type)
                worst_net_premium = worst_sold - worst_hedge
                
                close_sold = black_scholes(S_close, sell_strike, T_end, r, vol, sold_type)
                close_hedge = black_scholes(S_close, hedge_strike, T_end, r, vol, hedge_type)
                close_net_premium = close_sold - close_hedge
                
                best_sold = black_scholes(S_best, sell_strike, T_end, r, vol, sold_type)
                best_hedge = black_scholes(S_best, hedge_strike, T_end, r, vol, hedge_type)
                best_net_premium = best_sold - best_hedge
                
                if mode == "Baseline":
                    # Exit at close
                    loss = close_net_premium - net_credit
                    return -loss * lots * lot_size
                    
                elif mode == "Index_SL":
                    # Exit if index moves 1% against us
                    adverse_move = (S_entry - S_low)/S_entry if sold_type == "PE" else (S_high - S_entry)/S_entry
                    if adverse_move >= 0.01:
                        trigger_price = S_entry * 0.99 if sold_type == "PE" else S_entry * 1.01
                        sold_exit = black_scholes(trigger_price, sell_strike, T_end, r, vol, sold_type)
                        hedge_exit = black_scholes(trigger_price, hedge_strike, T_end, r, vol, hedge_type)
                        loss = (sold_exit - hedge_exit) - net_credit
                        return -loss * lots * lot_size
                    else:
                        loss = close_net_premium - net_credit
                        return -loss * lots * lot_size
                        
                else: # Premium SL/TP
                    # If net premium rises to 2.5x entry net credit -> Stop Loss
                    if worst_net_premium >= 2.5 * net_credit:
                        return -(2.5 - 1.0) * net_credit * lots * lot_size
                    # If net premium drops to 20% of entry net credit -> Take Profit
                    elif best_net_premium <= 0.2 * net_credit:
                        return (1.0 - 0.2) * net_credit * lots * lot_size
                    else:
                        loss = close_net_premium - net_credit
                        return -loss * lots * lot_size
                        
        for mode in modes:
            trade_pnl = calc_trade_pnl(mode)
            pnl[mode].append(trade_pnl)
            
    # Calculate statistics
    for mode in modes:
        mode_pnl = np.array(pnl[mode])
        stats[mode]["total_pnl"] = np.sum(mode_pnl)
        stats[mode]["wins"] = np.sum(mode_pnl > 0)
        stats[mode]["losses"] = np.sum(mode_pnl < 0)
        
        # Cumulative PnL and Drawdown
        cum_pnl = np.cumsum(mode_pnl)
        max_cum = np.maximum.accumulate(cum_pnl)
        # Avoid division by zero
        drawdown = np.where(max_cum > 0, (max_cum - cum_pnl), 0)
        stats[mode]["max_drawdown"] = np.max(drawdown)
        stats[mode]["cum_pnl"] = cum_pnl
        
    return stats

stats = run_simulation()

print("\n" + "=" * 72)
print("  OPTION SELLING SIMULATION RESULTS (1 Year - Capital: Rs. 10,00,000)")
print("=" * 72)
for mode in stats:
    s = stats[mode]
    total_trades = s["wins"] + s["losses"]
    win_rate = (s["wins"] / total_trades * 100) if total_trades > 0 else 0
    profit_factor = (s["total_pnl"] / s["max_drawdown"]) if s["max_drawdown"] > 0 else float("inf")
    print(f"[{mode}]")
    print(f"  Total P&L:         Rs.{s['total_pnl']:+12,.2f} ({s['total_pnl']/1000000*100:+.2f}%)")
    print(f"  Win Rate:          {win_rate:6.1f}% (Wins: {s['wins']}, Losses: {s['losses']}, Trades: {total_trades})")
    print(f"  Max Drawdown:      Rs.{s['max_drawdown']:12,.2f}")
    print(f"  Return/Drawdown:   {profit_factor:11.2f}x")
    print("-" * 50)
