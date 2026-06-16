"""Mock CI server for the multi-agent-dev-team example."""
from flask import Flask, jsonify, request

app = Flask(__name__)


@app.post("/ci/runs")
def create_run():
    return jsonify({"runId": "run-001", "status": "passed", "tests": 47, "duration_ms": 3241})


@app.get("/ci/runs/<run_id>")
def get_run(run_id):
    return jsonify({"runId": run_id, "status": "passed", "tests": 47, "duration_ms": 3241})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5002)
