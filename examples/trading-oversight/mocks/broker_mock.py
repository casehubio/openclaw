"""Mock broker API for the trading-oversight example."""
from flask import Flask, jsonify, request

app = Flask(__name__)

PORTFOLIO = {
    "positions": [
        {"symbol": "AAPL", "shares": 50, "value": 9250.00},
        {"symbol": "MSFT", "shares": 20, "value": 7640.00},
    ],
    "cash": 15000.00,
    "total_value": 31890.00,
    "daily_pnl": 320.00,
    "daily_pnl_limit": 5000.00,
    "daily_headroom": 4680.00,
}


@app.get("/broker/portfolio")
def get_portfolio():
    return jsonify(PORTFOLIO)


@app.post("/broker/orders")
def place_order():
    body = request.get_json(silent=True) or {}
    symbol = body.get("symbol", "NVDA")
    shares = body.get("shares", 100)
    side = body.get("side", "BUY")
    # Simulate slight slippage
    fill_price = 891.73 if side == "BUY" else 892.27
    return jsonify({
        "orderId": "ord-demo-001",
        "symbol": symbol,
        "shares": shares,
        "side": side,
        "filled": True,
        "fillPrice": fill_price,
        "totalValue": round(shares * fill_price, 2),
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001)
