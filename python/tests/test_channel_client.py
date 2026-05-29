# python/tests/test_channel_client.py  (model tests — client tests added in Task 8)
from datetime import datetime, timezone
from uuid import UUID

import httpx
import pytest
import respx
from casehub_openclaw import ChannelClient, ContextMessage, WindowContent


class TestWindowContentDeserialization:
    def test_full_window_parses_all_fields(self, full_window_json):
        result = WindowContent.model_validate(full_window_json)
        assert result.agent_has_association is True
        assert result.current_window_seq == 100
        assert result.last_window_seq == 42
        assert result.last_eviction_window_seq == -1
        assert len(result.messages) == 1

    def test_camelcase_aliases_map_to_snake_case(self, full_window_json):
        result = WindowContent.model_validate(full_window_json)
        msg = result.messages[0]
        assert msg.window_seq == 42
        assert msg.sender_id == "finance-agent"
        assert msg.message_type == "EVENT"
        assert str(msg.channel_id) == "3fa85f64-5717-4562-b3fc-2c963f66afa6"

    def test_null_correlation_id_and_channel_name_become_none(self):
        data = {
            "windowSeq": 1,
            "channelId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "channelName": None,
            "messageType": "EVENT",
            "senderId": "agent",
            "correlationId": None,
            "content": "hello",
            "receivedAt": "2026-05-29T10:00:00Z",
        }
        msg = ContextMessage.model_validate(data)
        assert msg.channel_name is None
        assert msg.correlation_id is None

    def test_epoch_last_channel_activity_parses_as_epoch_datetime(self, no_association_json):
        result = WindowContent.model_validate(no_association_json)
        epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
        assert result.last_channel_activity == epoch

    def test_empty_messages_list_is_valid(self, no_association_json):
        result = WindowContent.model_validate(no_association_json)
        assert result.messages == []
        assert result.agent_has_association is False

    def test_last_eviction_window_seq_minus_one_deserialises_correctly(self, full_window_json):
        result = WindowContent.model_validate(full_window_json)
        assert result.last_eviction_window_seq == -1


class TestChannelClientGetContext:
    @respx.mock
    def test_successful_response_returns_window_content(self, full_window_json):
        respx.get("http://localhost:8080/channel-context/home-agent").mock(
            return_value=httpx.Response(200, json=full_window_json)
        )
        client = ChannelClient("http://localhost:8080")
        result = client.get_context("home-agent", since=0)
        assert result.agent_has_association is True
        assert result.current_window_seq == 100

    @respx.mock
    def test_http_503_raises_http_status_error(self):
        respx.get("http://localhost:8080/channel-context/home-agent").mock(
            return_value=httpx.Response(503)
        )
        client = ChannelClient("http://localhost:8080")
        with pytest.raises(httpx.HTTPStatusError):
            client.get_context("home-agent", since=0)

    @respx.mock
    def test_agent_id_with_slash_is_url_encoded(self, full_window_json):
        # Raw slash in agent_id must be percent-encoded in the URL path
        respx.get("http://localhost:8080/channel-context/agent%2Fwith%2Fslash").mock(
            return_value=httpx.Response(200, json=full_window_json)
        )
        client = ChannelClient("http://localhost:8080")
        # This would raise a ConnectionError if URL encoding is wrong
        # (the mock only matches the encoded URL)
        result = client.get_context("agent/with/slash", since=0)
        assert result.agent_has_association is True

    @respx.mock
    def test_timeout_raises_timeout_exception(self):
        respx.get("http://localhost:8080/channel-context/home-agent").mock(
            side_effect=httpx.TimeoutException("timed out")
        )
        client = ChannelClient("http://localhost:8080")
        with pytest.raises(httpx.TimeoutException):
            client.get_context("home-agent", since=0)
