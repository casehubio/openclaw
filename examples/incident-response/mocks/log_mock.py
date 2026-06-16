"""Mock log server for the incident-response example.

Returns pre-recorded log fixtures for payment-service showing DB connection pool
exhaustion correlating with a deploy at 02:31 UTC.
"""
from flask import Flask, jsonify, request

app = Flask(__name__)

LOG_FIXTURE = [
    {"ts": "2026-06-16T02:31:15Z", "level": "INFO",  "service": "payment-service", "msg": "Deploy 7f3a2c1 applied — pool size changed from 20 to 5"},
    {"ts": "2026-06-16T02:31:30Z", "level": "WARN",  "service": "payment-service", "msg": "DB connection pool at 80% capacity (4/5)"},
    {"ts": "2026-06-16T02:32:10Z", "level": "ERROR", "service": "payment-service", "msg": "HikariCP: Connection pool exhausted — timeout after 30000ms"},
    {"ts": "2026-06-16T02:32:11Z", "level": "ERROR", "service": "payment-service", "msg": "HTTP 503 Service Unavailable — could not acquire connection"},
    {"ts": "2026-06-16T02:32:15Z", "level": "ERROR", "service": "payment-service", "msg": "HTTP 503 Service Unavailable — could not acquire connection"},
    {"ts": "2026-06-16T02:32:20Z", "level": "ERROR", "service": "payment-service", "msg": "HTTP 503 Service Unavailable — could not acquire connection"},
    {"ts": "2026-06-16T02:47:01Z", "level": "ERROR", "service": "payment-service", "msg": "Error rate: 34.2% (threshold: 5%) — alert triggered"},
]

DEPLOY_FIXTURE = [
    {
        "deployId": "deploy-7f3a2c1",
        "commit": "7f3a2c1",
        "service": "payment-service",
        "timestamp": "2026-06-16T02:31:00Z",
        "changes": ["Reduced DB connection pool size: 20 → 5 (cost-optimisation attempt)"],
        "author": "auto-deploy-pipeline",
    }
]


@app.get("/logs")
def get_logs():
    service = request.args.get("service", "")
    return jsonify([l for l in LOG_FIXTURE if not service or l["service"] == service])


@app.get("/deploys")
def get_deploys():
    service = request.args.get("service", "")
    return jsonify([d for d in DEPLOY_FIXTURE if not service or d["service"] == service])


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001)
