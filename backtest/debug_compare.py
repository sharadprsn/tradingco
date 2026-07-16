import math
from backtest_improvements import black_scholes, find_strike_for_premium, find_strike_for_delta

S = 24000
T_start = 3 / 365.0
r = 0.07
vol = 0.15

# Method 1: Fixed 150-pt hedge (from backtest_options_simulation.py)
sell_strike_1 = find_strike_for_premium(S, 35, T_start, r, vol, "PE")
hedge_strike_1 = round((S - 150) / 50) * 50
p_sell_1 = black_scholes(S, sell_strike_1, T_start, r, vol, "PE")
p_hedge_1 = black_scholes(S, hedge_strike_1, T_start, r, vol, "PE")
net_credit_1 = p_sell_1 - p_hedge_1

# Method 2: Premium 10 hedge (from backtest_improvements.py)
sell_strike_2 = find_strike_for_premium(S, 35, T_start, r, vol, "PE")
hedge_strike_2 = find_strike_for_premium(S, 10, T_start, r, vol, "PE")
p_sell_2 = black_scholes(S, sell_strike_2, T_start, r, vol, "PE")
p_hedge_2 = black_scholes(S, hedge_strike_2, T_start, r, vol, "PE")
net_credit_2 = p_sell_2 - p_hedge_2

print(f"S = {S}, T_start = {T_start:.5f}")
print("Method 1 (Fixed 150-pt hedge):")
print(f"  Sell Strike: {sell_strike_1} (Premium: {p_sell_1:.2f})")
print(f"  Hedge Strike: {hedge_strike_1} (Premium: {p_hedge_1:.2f})")
print(f"  Net Credit: {net_credit_1:.2f}")
print("Method 2 (Premium 10 hedge):")
print(f"  Sell Strike: {sell_strike_2} (Premium: {p_sell_2:.2f})")
print(f"  Hedge Strike: {hedge_strike_2} (Premium: {p_hedge_2:.2f})")
print(f"  Net Credit: {net_credit_2:.2f}")
