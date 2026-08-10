---
layout: post
title: "The channel context window: three dead ends and a data race"
date: 2026-05-28
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [quarkus, casehub, ring-buffer, concurrency, cursor-design]
---

The ChannelContextWindow is what makes the multi-agent mesh actually work at the LLM level. Without it, an OpenClaw agent waking up to a COMMAND knows only what that message says. It doesn't know that finance-agent posted a budget warning to the observe channel twenty minutes ago, or that the security agent flagged a credential pattern in the same file it's about to review. The integration connects correctly; the agents don't see each other.

The ring buffer itself is simple. The hard part was answering one question first: what channels does a given agent get context from?

Three approaches looked plausible and all failed the same way.

Auto-discovery from `senderId` seemed clever — when the observer sees a message from `grocery-agent`, register that it participates in that channel. The flaw: grocery-agent doesn't learn by sending, it learns by receiving. Finance-agent's budget warning goes to `household/observe`; grocery-agent needs that context even though it's never posted there. Registration based on who sends breaks the primary use case.

Channel name convention was the next candidate. If agentIds follow a naming convention that maps to channel name prefixes, no registration is needed. But the channels here aren't agent-scoped; they're case-scoped. `household/observe` is shared by all agents on the household case. The prefix approach collapses under that model and creates a hidden coupling between `CaseChannelProvider` naming and the window's filtering logic — unenforced, silently broken by any naming change.

Global window is the honest fallback: return all messages to all agents, use the agentId only as a cursor label. For a single household case with three agents, it works. At any real scale it produces cross-case leakage and enormous system prompts. It also forecloses adding access control later.

The right answer is explicit registration. `associate(agentId, channelIds)` is called when WorkerProvisioner provisions an agent — it knows the agentId and which case channels that agent needs. Additive, in-memory, lost on restart. That last part is acceptable; the window is intelligence, not correctness.

Two cursor decisions took more thought.

Qhorus messages have a `sequenceNumber`, but it's per-channel — two messages on different channels can both be sequenceNumber 1. Using it as a cross-channel cursor is ambiguous, and `MessageReceivedEvent` doesn't even carry the field. The window maintains its own `AtomicLong windowSeq`, globally incrementing on every ingested message. The response also includes `currentWindowSeq` (the counter's current value) so the SDK can detect service restarts — if `since > currentWindowSeq`, the counter reset; the client discards its cursor.

The overflow signal needed rethinking too. I'd designed it with a cumulative `evictionCount`. A buffer that overflowed once three hours ago would trigger the overflow notice on every subsequent call, forever. What the SDK actually needs: did overflow happen *after my cursor*? That requires `lastEvictionWindowSeq` — the `windowSeq` of the most recently evicted message. `lastEvictionWindowSeq > since` means the eviction is relevant. Cumulative counts are the wrong unit.

Claude caught a race condition I'd missed. The `agentChannels` map stored a plain `HashSet`. `ConcurrentHashMap.merge()` holds a bin lock during the remapping — the mutation is safe. But `query()` retrieved the stored set and iterated it without any lock. A concurrent `associate()` call mutating that set while query ranged over it would produce `ConcurrentModificationException` or silently skip entries. The fix: `merge()` now returns `Collections.unmodifiableSet(merged)`. Reads see an immutable snapshot.

The window is wired but dormant until WorkerProvisioner calls `associate()`. That's next.
