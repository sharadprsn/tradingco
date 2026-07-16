import yfinance as yf
import pandas as pd
import numpy as np
from datetime import datetime
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

def calculate_delta(S, K, iv, T, is_call):
    if T <= 0:
        if is_call:
            return 1.0 if S >= K else 0.0
        else:
            return -1.0 if S <= K else 0.0
    r = 0.07  # Risk free rate
    sigma = iv / 100.0 if iv > 0 else 0.15
    d1 = (math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * math.sqrt(T))
    cnd = norm_cdf(d1)
    return cnd if is_call else (cnd - 1.0)

def find_strike_for_premium(S, target_prem, T, r, sigma, option_type, strike_interval=50):
    best_strike = None
    min_diff = float("inf")
    atm = round(S / strike_interval) * strike_interval
    for i in range(-20, 21):
        strike = atm + i * strike_interval
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

def find_strike_for_delta(S, target_delta, T, r, sigma, option_type, strike_interval=50):
    best_strike = None
    min_diff = float("inf")
    atm = round(S / strike_interval) * strike_interval
    is_call = (option_type == "CE")
    for i in range(-20, 21):
        strike = atm + i * strike_interval
        if option_type == "PE" and strike >= S:
            continue
        if option_type == "CE" and strike <= S:
            continue
        delta = calculate_delta(S, strike, sigma * 100, T, is_call)
        diff = abs(abs(delta) - abs(target_delta))
        if diff < min_diff:
            min_diff = diff
            best_strike = strike
    return best_strike

# --- Fetch Data ---
print("Fetching Nifty 50 data...")
nifty = yf.download("^NSEI", period="1y", interval="1d")
nifty.columns = ["Close", "High", "Low", "Open", "Volume"]

# Indicators
nifty["Ret"] = nifty["Close"].pct_change()
nifty["Hist_Vol"] = nifty["Ret"].rolling(20).std() * math.sqrt(252)
nifty["EMA20"] = nifty["Close"].ewm(span=20, adjust=False).mean()
nifty["Hist_Vol"] = nifty["Hist_Vol"].fillna(0.15).apply(lambda x: max(x, 0.10))

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

def run_backtest_simulation():
    r = 0.07
    lot_size = 65
    capital = 1000000
    margin_per_lot = 60000
    lots = int(capital / margin_per_lot)
    
    strategies = [
        "Fixed_Premium_35",         # Sell ₹35, hedge ₹10
        "Fixed_Delta_0.15",         # Sell 0.15 delta, hedge 0.05 delta
        "Fixed_Delta_0.10",         # Sell 0.10 delta, hedge 0.03 delta
        "Trend_Filtered_Premium",   # Sell ₹35, hedge ₹10, no trade if counter-trend
        "Trend_Strangle_Premium",   # Sell ₹35 directional, if counter-trend sell Strangle instead of no-trade
        "Blended_Delta_Premium",    # Target 0.15 delta, but clamp premium to [20, 45] range
        "Dynamic_SL_TP"             # Target 0.15 delta, but stop loss 2.0x near expiry, 3.0x in high VIX
    ]
    
    pnl = {s: [] for s in strategies}
    trades_count = {s: 0 for s in strategies}
    wins = {s: 0 for s in strategies}
    losses = {s: 0 for s in strategies}
    
    trading_data = nifty.iloc[20:-1]
    
    for idx in range(len(trading_data)):
        row = trading_data.iloc[idx]
        next_row = nifty.iloc[nifty.index.get_loc(row.name) + 1]
        
        day_of_week = row.name.weekday()
        days_to_expiry = (3 - day_of_week) % 7
        if days_to_expiry == 0:
            days_to_expiry = 0.1
            
        T_start = days_to_expiry / 365.0
        T_end = max(0.0001, (days_to_expiry - 0.25) / 365.0)
        
        sig = row["Signal"]
        vol = row["Hist_Vol"]
        S_entry = next_row["Open"]
        S_high = next_row["High"]
        S_low = next_row["Low"]
        S_close = next_row["Close"]
        
        trend_aligned = True
        if sig == "BULLISH" and row["Close"] < row["EMA20"]:
            trend_aligned = False
        elif sig == "BEARISH" and row["Close"] > row["EMA20"]:
            trend_aligned = False
            
        # We simulate each strategy
        for strat in strategies:
            # 1. Trend filtering check
            if strat == "Trend_Filtered_Premium" and not trend_aligned and sig != "NEUTRAL":
                pnl[strat].append(0.0)
                continue
                
            # Determine effective signal for Trend_Strangle_Premium
            eff_sig = sig
            if strat == "Trend_Strangle_Premium" and not trend_aligned and sig != "NEUTRAL":
                eff_sig = "NEUTRAL"
                
            # Selection parameters
            sell_pe_strike, sell_ce_strike = None, None
            hedge_pe_strike, hedge_ce_strike = None, None
            
            if eff_sig == "BULLISH":
                # Sell PE, buy PE hedge
                if strat in ["Fixed_Premium_35", "Trend_Filtered_Premium", "Trend_Strangle_Premium"]:
                    sell_pe_strike = find_strike_for_premium(S_entry, 35, T_start, r, vol, "PE")
                    hedge_pe_strike = find_strike_for_premium(S_entry, 10, T_start, r, vol, "PE")
                elif strat == "Fixed_Delta_0.15":
                    sell_pe_strike = find_strike_for_delta(S_entry, -0.15, T_start, r, vol, "PE")
                    hedge_pe_strike = find_strike_for_delta(S_entry, -0.05, T_start, r, vol, "PE")
                elif strat == "Fixed_Delta_0.10":
                    sell_pe_strike = find_strike_for_delta(S_entry, -0.10, T_start, r, vol, "PE")
                    hedge_pe_strike = find_strike_for_delta(S_entry, -0.03, T_start, r, vol, "PE")
                elif strat == "Blended_Delta_Premium":
                    # Find delta 0.15 strike
                    s_str = find_strike_for_delta(S_entry, -0.15, T_start, r, vol, "PE")
                    prem = black_scholes(S_entry, s_str, T_start, r, vol, "PE")
                    if prem < 20:
                        s_str = find_strike_for_premium(S_entry, 20, T_start, r, vol, "PE")
                    elif prem > 45:
                        s_str = find_strike_for_premium(S_entry, 45, T_start, r, vol, "PE")
                    sell_pe_strike = s_str
                    hedge_pe_strike = find_strike_for_premium(S_entry, 7, T_start, r, vol, "PE")
                elif strat == "Dynamic_SL_TP":
                    sell_pe_strike = find_strike_for_delta(S_entry, -0.15, T_start, r, vol, "PE")
                    hedge_pe_strike = find_strike_for_delta(S_entry, -0.05, T_start, r, vol, "PE")
                    
            elif eff_sig == "BEARISH":
                # Sell CE, buy CE hedge
                if strat in ["Fixed_Premium_35", "Trend_Filtered_Premium", "Trend_Strangle_Premium"]:
                    sell_ce_strike = find_strike_for_premium(S_entry, 35, T_start, r, vol, "CE")
                    hedge_ce_strike = find_strike_for_premium(S_entry, 10, T_start, r, vol, "CE")
                elif strat == "Fixed_Delta_0.15":
                    sell_ce_strike = find_strike_for_delta(S_entry, 0.15, T_start, r, vol, "CE")
                    hedge_ce_strike = find_strike_for_delta(S_entry, 0.05, T_start, r, vol, "CE")
                elif strat == "Fixed_Delta_0.10":
                    sell_ce_strike = find_strike_for_delta(S_entry, 0.10, T_start, r, vol, "CE")
                    hedge_ce_strike = find_strike_for_delta(S_entry, 0.03, T_start, r, vol, "CE")
                elif strat == "Blended_Delta_Premium":
                    s_str = find_strike_for_delta(S_entry, 0.15, T_start, r, vol, "CE")
                    prem = black_scholes(S_entry, s_str, T_start, r, vol, "CE")
                    if prem < 20:
                        s_str = find_strike_for_premium(S_entry, 20, T_start, r, vol, "CE")
                    elif prem > 45:
                        s_str = find_strike_for_premium(S_entry, 45, T_start, r, vol, "CE")
                    sell_ce_strike = s_str
                    hedge_ce_strike = find_strike_for_premium(S_entry, 7, T_start, r, vol, "CE")
                elif strat == "Dynamic_SL_TP":
                    sell_ce_strike = find_strike_for_delta(S_entry, 0.15, T_start, r, vol, "CE")
                    hedge_ce_strike = find_strike_for_delta(S_entry, 0.05, T_start, r, vol, "CE")
                    
            else: # NEUTRAL strangle
                if strat in ["Fixed_Premium_35", "Trend_Filtered_Premium", "Trend_Strangle_Premium"]:
                    sell_pe_strike = find_strike_for_premium(S_entry, 35, T_start, r, vol, "PE")
                    sell_ce_strike = find_strike_for_premium(S_entry, 35, T_start, r, vol, "CE")
                elif strat == "Fixed_Delta_0.15":
                    sell_pe_strike = find_strike_for_delta(S_entry, -0.15, T_start, r, vol, "PE")
                    sell_ce_strike = find_strike_for_delta(S_entry, 0.15, T_start, r, vol, "CE")
                elif strat == "Fixed_Delta_0.10":
                    sell_pe_strike = find_strike_for_delta(S_entry, -0.10, T_start, r, vol, "PE")
                    sell_ce_strike = find_strike_for_delta(S_entry, 0.10, T_start, r, vol, "CE")
                elif strat == "Blended_Delta_Premium":
                    sell_pe_strike = find_strike_for_delta(S_entry, -0.15, T_start, r, vol, "PE")
                    sell_ce_strike = find_strike_for_delta(S_entry, 0.15, T_start, r, vol, "CE")
                elif strat == "Dynamic_SL_TP":
                    sell_pe_strike = find_strike_for_delta(S_entry, -0.15, T_start, r, vol, "PE")
                    sell_ce_strike = find_strike_for_delta(S_entry, 0.15, T_start, r, vol, "CE")

            # Trade execution simulation
            trade_pnl = 0.0
            
            # Helper to evaluate P&L for single option leg or spread
            if eff_sig == "NEUTRAL":
                # Strangle: short PE + short CE (no hedges for simple strangle comparison)
                if not sell_pe_strike or not sell_ce_strike:
                    pnl[strat].append(0.0)
                    continue
                pe_entry_prem = black_scholes(S_entry, sell_pe_strike, T_start, r, vol, "PE")
                ce_entry_prem = black_scholes(S_entry, sell_ce_strike, T_start, r, vol, "CE")
                sold_prem = pe_entry_prem + ce_entry_prem
                
                # Intraday worst/close
                pe_max_val = black_scholes(S_low, sell_pe_strike, T_end, r, vol, "PE")
                ce_max_val = black_scholes(S_high, sell_ce_strike, T_end, r, vol, "CE")
                pe_close_val = black_scholes(S_close, sell_pe_strike, T_end, r, vol, "PE")
                ce_close_val = black_scholes(S_close, sell_ce_strike, T_end, r, vol, "CE")
                
                max_combined_prem = pe_max_val + ce_max_val
                min_combined_prem = pe_close_val + ce_close_val
                
                # Determine SL/TP thresholds
                sl_factor = 2.0
                tp_factor = 0.2
                if strat == "Dynamic_SL_TP":
                    # Near expiry (same day or 1 day left), use tighter stop to avoid gamma spikes
                    if days_to_expiry <= 1.5:
                        sl_factor = 1.7
                    # In highly volatile environment, give it a bit more room
                    if vol > 0.18:
                        sl_factor = 2.3
                
                if max_combined_prem >= sl_factor * sold_prem:
                    trade_pnl = -(sl_factor - 1.0) * sold_prem * lots * lot_size
                elif min_combined_prem <= tp_factor * sold_prem:
                    trade_pnl = (1.0 - tp_factor) * sold_prem * lots * lot_size
                else:
                    loss = (pe_close_val + ce_close_val) - sold_prem
                    trade_pnl = -loss * lots * lot_size
            else:
                # Directional credit spread
                sold_strike = sell_pe_strike if eff_sig == "BULLISH" else sell_ce_strike
                hedge_strike = hedge_pe_strike if eff_sig == "BULLISH" else hedge_ce_strike
                option_type = "PE" if eff_sig == "BULLISH" else "CE"
                
                if not sold_strike or not hedge_strike:
                    pnl[strat].append(0.0)
                    continue
                    
                entry_sold = black_scholes(S_entry, sold_strike, T_start, r, vol, option_type)
                entry_hedge = black_scholes(S_entry, hedge_strike, T_start, r, vol, option_type)
                net_credit = entry_sold - entry_hedge
                
                if net_credit <= 0.5: # skip if net credit is too low
                    pnl[strat].append(0.0)
                    continue
                
                S_worst = S_low if eff_sig == "BULLISH" else S_high
                S_best = S_high if eff_sig == "BULLISH" else S_low
                
                worst_sold = black_scholes(S_worst, sold_strike, T_end, r, vol, option_type)
                worst_hedge = black_scholes(S_worst, hedge_strike, T_end, r, vol, option_type)
                worst_net_premium = worst_sold - worst_hedge
                
                best_sold = black_scholes(S_best, sold_strike, T_end, r, vol, option_type)
                best_hedge = black_scholes(S_best, hedge_strike, T_end, r, vol, option_type)
                best_net_premium = best_sold - best_hedge
                
                close_sold = black_scholes(S_close, sold_strike, T_end, r, vol, option_type)
                close_hedge = black_scholes(S_close, hedge_strike, T_end, r, vol, option_type)
                close_net_premium = close_sold - close_hedge
                
                sl_factor = 2.5
                tp_factor = 0.2
                if strat == "Dynamic_SL_TP":
                    if days_to_expiry <= 1.5:
                        sl_factor = 2.0
                    if vol > 0.18:
                        sl_factor = 3.0
                
                if worst_net_premium >= sl_factor * net_credit:
                    trade_pnl = -(sl_factor - 1.0) * net_credit * lots * lot_size
                elif best_net_premium <= tp_factor * net_credit:
                    trade_pnl = (1.0 - tp_factor) * net_credit * lots * lot_size
                else:
                    loss = close_net_premium - net_credit
                    trade_pnl = -loss * lots * lot_size
            
            pnl[strat].append(trade_pnl)
            trades_count[strat] += 1
            if trade_pnl > 0:
                wins[strat] += 1
            elif trade_pnl < 0:
                losses[strat] += 1
                
    # Summary of stats
    print("\n" + "=" * 72)
    print("  STRATEGY IMPROVEMENT COMPARISON")
    print("=" * 72)
    for s in strategies:
        strat_pnl = np.array(pnl[s])
        total_pnl = np.sum(strat_pnl)
        win_rate = (wins[s] / trades_count[s] * 100) if trades_count[s] > 0 else 0
        
        cum_pnl = np.cumsum(strat_pnl)
        max_cum = np.maximum.accumulate(cum_pnl)
        drawdown = np.where(max_cum > 0, max_cum - cum_pnl, 0)
        max_dd = np.max(drawdown)
        
        profit_factor = (total_pnl / max_dd) if max_dd > 0 else float("inf")
        
        print(f"[{s}]")
        print(f"  Total P&L:         Rs.{total_pnl:+12,.2f} ({total_pnl/1000000*100:+.2f}%)")
        print(f"  Trades:            {trades_count[s]} (Wins: {wins[s]}, Losses: {losses[s]}, Win Rate: {win_rate:.1f}%)")
        print(f"  Max Drawdown:      Rs.{max_dd:12,.2f}")
        print(f"  Return/Drawdown:   {profit_factor:11.2f}x")
        print("-" * 50)

run_backtest_simulation()
