"""Mock market data feed for the trading-oversight example.

Serves OHLCV fixture data for NVDA with a strong momentum signal.
The agent reads this and confirms a BUY signal.
"""
from flask import Flask, jsonify

app = Flask(__name__)

NVDA_FEED = {
    "symbol": "NVDA",
    "price": 892.15,
    "change_pct": 2.34,
    "volume": 28_450_000,
    "momentum_score": 0.84,
    "signal": "BUY",
    "ohlcv": [
        {"date": "2026-06-16", "open": 871.20, "high": 895.40, "low": 869.80, "close": 892.15, "volume": 28_450_000},
        {"date": "2026-06-13", "open": 850.00, "high": 874.30, "low": 847.90, "close": 871.20, "volume": 22_100_000},
        {"date": "2026-06-12", "open": 841.50, "high": 855.60, "low": 839.20, "close": 850.00, "volume": 19_800_000},
    ],
}


@app.get("/feed/<symbol>")
def get_feed(symbol: str):
    if symbol.upper() == "NVDA":
        return jsonify(NVDA_FEED)
    return jsonify({"error": f"No feed for symbol {symbol}"}), 404


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5002)
