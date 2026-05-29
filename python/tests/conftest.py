import pytest


@pytest.fixture
def full_window_json():
    return {
        "messages": [
            {
                "windowSeq": 42,
                "channelId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "channelName": "household/observe",
                "messageType": "EVENT",
                "senderId": "finance-agent",
                "correlationId": None,
                "content": "Monthly budget exhausted.",
                "receivedAt": "2026-05-29T10:00:00Z",
            }
        ],
        "lastEvictionWindowSeq": -1,
        "lastWindowSeq": 42,
        "currentWindowSeq": 100,
        "agentHasAssociation": True,
        "lastChannelActivity": "2026-05-29T10:00:00Z",
    }


@pytest.fixture
def no_association_json():
    return {
        "messages": [],
        "lastEvictionWindowSeq": -1,
        "lastWindowSeq": 0,
        "currentWindowSeq": 0,
        "agentHasAssociation": False,
        "lastChannelActivity": "1970-01-01T00:00:00Z",
    }
