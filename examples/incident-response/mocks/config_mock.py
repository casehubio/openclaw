"""Mock config API for the incident-response example.

Exposes the payment-service configuration so the Resolver can verify
the current pool size and apply the hot-patch.
"""
from flask import Flask, jsonify, request

app = Flask(__name__)

_config = {"db.pool.size": 5, "db.connection.timeout.ms": 30000}


@app.get("/services/<service>/config")
def get_config(service: str):
    return jsonify(_config)


@app.patch("/services/<service>/config")
def patch_config(service: str):
    updates = request.get_json(silent=True) or {}
    _config.update(updates)
    return jsonify({"updated": True, "config": _config})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5002)
