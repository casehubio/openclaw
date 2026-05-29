from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class ContextMessage(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    window_seq: int = Field(alias="windowSeq")
    channel_id: UUID = Field(alias="channelId")
    channel_name: str | None = Field(alias="channelName")
    message_type: str = Field(alias="messageType")
    sender_id: str = Field(alias="senderId")
    correlation_id: str | None = Field(alias="correlationId")
    content: str
    received_at: datetime = Field(alias="receivedAt")


class WindowContent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    messages: list[ContextMessage]
    last_eviction_window_seq: int = Field(alias="lastEvictionWindowSeq")
    last_window_seq: int = Field(alias="lastWindowSeq")
    current_window_seq: int = Field(alias="currentWindowSeq")
    agent_has_association: bool = Field(alias="agentHasAssociation")
    last_channel_activity: datetime = Field(alias="lastChannelActivity")
