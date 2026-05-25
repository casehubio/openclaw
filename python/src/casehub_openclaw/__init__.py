# casehub-openclaw Python SDK
# Provides before_prompt_build hook for OpenClaw agents — injects ChannelContextWindow
# content as appendSystemContext (compaction-safe system prompt injection).
#
# Usage:
#   from casehub_openclaw import register_context_hook
#   register_context_hook(agent, casehub_url="http://localhost:8080")
