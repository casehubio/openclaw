"""Mock GitHub API for the multi-agent-dev-team example."""
from flask import Flask, jsonify

app = Flask(__name__)

ISSUE_FIXTURE = {
    "number": 42,
    "title": "NullPointerException in PaymentService.process()",
    "body": "When `payment` is null, PaymentService.process() throws NPE at line 87.",
    "state": "open",
}

FILE_FIXTURE = {
    "name": "PaymentService.java",
    "path": "src/main/java/com/demo/PaymentService.java",
    "content": (
        "public class PaymentService {\n"
        "    public void process(Payment payment) {\n"
        "        // BUG: no null check\n"
        "        String id = payment.getId();\n"
        "    }\n"
        "}\n"
    ),
}

PULL_FILES_FIXTURE = [
    {
        "filename": "src/main/java/com/demo/PaymentService.java",
        "status": "modified",
        "additions": 3,
        "deletions": 1,
        "patch": (
            "@@ -1,5 +1,7 @@\n"
            " public class PaymentService {\n"
            "     public void process(Payment payment) {\n"
            "+        if (payment == null) {\n"
            "+            throw new IllegalArgumentException(\"payment must not be null\");\n"
            "+        }\n"
            "         String id = payment.getId();\n"
            "     }\n"
            " }\n"
        ),
    }
]


@app.get("/repos/demo/app/issues/42")
def get_issue():
    return jsonify(ISSUE_FIXTURE)


@app.get("/repos/demo/app/contents/PaymentService.java")
def get_file():
    return jsonify(FILE_FIXTURE)


@app.get("/repos/demo/app/pulls/1/files")
def get_pull_files():
    return jsonify(PULL_FILES_FIXTURE)


@app.put("/repos/demo/app/pulls/1/merge")
def merge_pull():
    return jsonify({"merged": True, "sha": "abc123def456", "message": "Pull Request successfully merged"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001)
