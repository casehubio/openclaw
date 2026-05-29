# python/src/casehub_openclaw/channel_client.py
from urllib.parse import quote

import httpx

from .models import WindowContent


class ChannelClient:
    """HTTP client for the ChannelContextWindow REST endpoint.

    Uses top-level httpx.get() — one connection per call. For skill scripts
    that call get_context once per execution this is appropriate. For scripts
    making repeated calls, use httpx.Client as a context manager directly.

    Raises:
        httpx.HTTPStatusError: on non-2xx response.
        httpx.TimeoutException: when the request exceeds ``timeout`` seconds.
    """

    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def get_context(self, agent_id: str, since: int = 0) -> WindowContent:
        url = f"{self._base_url}/channel-context/{quote(agent_id, safe='')}"
        resp = httpx.get(url, params={"since": since}, timeout=self._timeout)
        resp.raise_for_status()
        return WindowContent.model_validate(resp.json())
