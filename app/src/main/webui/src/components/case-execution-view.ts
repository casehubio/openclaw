import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { emitPagesEvent, onPagesEvent } from '@casehubio/blocks-ui-core';
import type { QhorusMessage } from '@casehubio/blocks-ui-channel-activity';
import type {
  CaseExecutionEvent,
  AgentState,
  ChannelMessageEvent,
  GateState,
  ScenarioStateSnapshot,
} from '../types/events.js';

let nextMessageId = 0;

function toQhorusMessage(event: ChannelMessageEvent): QhorusMessage {
  return {
    id: String(nextMessageId++),
    channelId: '',
    sender: event.agentId,
    messageType: 'STATUS',
    actorType: 'AGENT',
    content: event.content,
    topic: '',
    replyCount: 0,
    artefactRefs: [],
    createdAt: event.occurredAt,
  };
}

@customElement('case-execution-view')
export class CaseExecutionView extends LitElement {
  @property({ type: String }) scenarioId: string = '';
  @state() private agents: AgentState[] = [];
  @state() private messages: ChannelMessageEvent[] = [];
  @state() private pendingGate: GateState | null = null;
  @state() private status: 'idle' | 'running' | 'completed' | 'failed' = 'idle';
  private eventSource: EventSource | null = null;
  private _unsubGateDecided?: () => void;
  private _sseInitialOpen = true;

  static styles = css`
    :host {
      display: block;
      height: 100%;
    }

    .status-banner {
      padding: 16px;
      border-radius: 8px;
      font-size: 14px;
      font-weight: 600;
    }

    .status-banner.running {
      background: var(--pages-info-9);
      color: var(--pages-info-12);
    }

    .status-banner.completed {
      background: var(--pages-success-9);
      color: var(--pages-success-12);
    }

    .status-banner.failed {
      background: var(--pages-danger-9);
      color: var(--pages-danger-12);
    }

    .status-banner.idle {
      background: var(--pages-neutral-2);
      color: var(--pages-neutral-11);
    }

    @media (prefers-reduced-motion: reduce) {
      * {
        transition: none;
      }
    }
  `;

  firstUpdated() {
    emitPagesEvent(document, 'openclaw-scenario:selected', {});
  }

  connectedCallback() {
    super.connectedCallback();
    this.loadState();
    this.subscribeToSSE();
    this._unsubGateDecided = onPagesEvent(document, 'gate.decided', () => {
      this.pendingGate = null;
    });
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.eventSource?.close();
    this._unsubGateDecided?.();
  }

  private async loadState() {
    if (!this.scenarioId) return;

    try {
      const response = await fetch(`/api/scenarios/${this.scenarioId}/state`);
      if (!response.ok) throw new Error('Failed to load state');
      const snapshot: ScenarioStateSnapshot = await response.json();

      this.agents = snapshot.agents;
      this.messages = snapshot.recentMessages;
      this.pendingGate = snapshot.pendingGate;
      this.status = snapshot.status;
    } catch (error) {
      console.error('Failed to load state:', error);
    }
  }

  private subscribeToSSE() {
    this._sseInitialOpen = true;
    this.eventSource = new EventSource('/api/scenarios/events');
    this.eventSource.onopen = () => {
      if (this._sseInitialOpen) {
        this._sseInitialOpen = false;
        return;
      }
      this.loadState();
    };
    this.eventSource.onmessage = (e) => {
      try {
        const event = JSON.parse(e.data) as CaseExecutionEvent;
        if (event.scenarioId === this.scenarioId) {
          this.handleSSEEvent(event);
        }
      } catch (error) {
        console.error('Failed to parse SSE event:', error);
      }
    };
    this.eventSource.onerror = (error) => {
      console.error('SSE connection error:', error);
    };
  }

  private handleSSEEvent(event: CaseExecutionEvent) {
    switch (event.type) {
      case 'SCENARIO_STARTED':
        this.status = 'running';
        break;

      case 'SCENARIO_COMPLETED':
        this.status = 'completed';
        break;

      case 'SCENARIO_FAILED':
        this.status = 'failed';
        break;

      case 'AGENT_STARTED': {
        const existing = this.agents.find(a => a.agentId === event.agentId);
        if (existing) {
          this.agents = this.agents.map(a =>
            a.agentId === event.agentId
              ? { ...a, state: 'running' as const }
              : a
          );
        } else {
          this.agents = [
            ...this.agents,
            {
              agentId: event.agentId,
              role: event.role,
              state: 'running',
              durationMs: null,
            },
          ];
        }
        break;
      }

      case 'AGENT_COMPLETED':
        this.agents = this.agents.map(a =>
          a.agentId === event.agentId
            ? {
                ...a,
                state: event.outcome,
                durationMs: event.durationMs,
              }
            : a
        );
        break;

      case 'CHANNEL_MESSAGE':
        this.messages = [...this.messages, event];
        break;

      case 'GATE_PENDING':
        this.pendingGate = {
          gateId: event.gateId,
          agentId: event.agentId,
          action: event.action,
          classification: event.classification,
          priorAgents: event.priorAgents,
        };
        break;

      case 'GATE_RESOLVED':
        this.pendingGate = null;
        break;

      case 'COMMITMENT_UPDATED':
        // Future: update commitment state display
        break;
    }
  }

  render() {
    return html`
      <split-workbench selection-topic="openclaw-scenario">
        <div slot="header" class="status-banner ${this.status}">
          Scenario: ${this.scenarioId} — ${this.status.toUpperCase()}
        </div>
        <div slot="list">
          <case-worker-pipeline .workers=${this.agents}></case-worker-pipeline>
        </div>
        <div slot="detail">
          <channel-feed .messages=${this.messages.map(toQhorusMessage)}></channel-feed>
        </div>
      </split-workbench>

      ${this.pendingGate ? html`
        <pages-modal
          variant="alertdialog"
          no-close-button
          .open=${true}
          @pages-modal-cancel=${(e: Event) => e.preventDefault()}
        >
          <span slot="header">Oversight Gate</span>
          <approval-gate
            gate-id=${this.pendingGate.gateId}
            endpoint="/api/scenarios/${this.scenarioId}"
            prompt=${this.pendingGate.action}
            context-text=${this.pendingGate.classification}
            .data=${{ agent: this.pendingGate.agentId, priorAgents: this.pendingGate.priorAgents }}
          ></approval-gate>
        </pages-modal>
      ` : ''}
    `;
  }
}
