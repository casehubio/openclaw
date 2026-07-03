import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type {
  CaseExecutionEvent,
  AgentState,
  ChannelMessageEvent,
  GateState,
  ScenarioStateSnapshot,
} from '../types/events.js';

@customElement('case-execution-view')
export class CaseExecutionView extends LitElement {
  @property({ type: String }) scenarioId: string = '';
  @state() private agents: AgentState[] = [];
  @state() private messages: ChannelMessageEvent[] = [];
  @state() private pendingGate: GateState | null = null;
  @state() private status: 'idle' | 'running' | 'completed' | 'failed' = 'idle';
  private eventSource: EventSource | null = null;

  static styles = css`
    :host {
      display: block;
      height: 100%;
    }

    .status-banner {
      padding: 16px;
      margin-bottom: 20px;
      border-radius: 8px;
      font-size: 14px;
      font-weight: 600;
    }

    .status-banner.running {
      background: var(--blocks-info-bg, #3b82f6);
      color: var(--blocks-info-text, #eff6ff);
    }

    .status-banner.completed {
      background: var(--blocks-success-bg, #10b981);
      color: var(--blocks-success-text, #064e3b);
    }

    .status-banner.failed {
      background: var(--blocks-error-bg, #ef4444);
      color: var(--blocks-error-text, #7f1d1d);
    }

    .status-banner.idle {
      background: var(--blocks-surface-2, #16213e);
      color: var(--blocks-text, #e0e0e0);
    }

    .layout {
      display: grid;
      grid-template-columns: 40% 60%;
      gap: 20px;
      height: calc(100% - 70px);
    }

    h3 {
      font-size: 18px;
      font-weight: 600;
      color: var(--blocks-text-bright, #ffffff);
      margin-bottom: 16px;
    }

    .panel {
      display: flex;
      flex-direction: column;
    }

    .panel-content {
      flex: 1;
      overflow: auto;
    }

    @media (max-width: 1024px) {
      .layout {
        grid-template-columns: 1fr;
        grid-template-rows: 400px 1fr;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      * {
        transition: none;
      }
    }
  `;

  connectedCallback() {
    super.connectedCallback();
    this.loadState();
    this.subscribeToSSE();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.eventSource?.close();
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
    this.eventSource = new EventSource('/api/scenarios/events');
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
      <div class="status-banner ${this.status}">
        Scenario: ${this.scenarioId} — ${this.status.toUpperCase()}
      </div>

      <div class="layout">
        <div class="panel">
          <h3>Agent Pipeline</h3>
          <div class="panel-content">
            <case-worker-pipeline .workers=${this.agents}></case-worker-pipeline>
          </div>
        </div>

        <div class="panel">
          <h3>Channel Feed</h3>
          <div class="panel-content">
            <channel-feed .messages=${this.messages}></channel-feed>
          </div>
        </div>
      </div>

      <gate-approval-modal
        .gate=${this.pendingGate}
        .scenarioId=${this.scenarioId}
      ></gate-approval-modal>
    `;
  }
}
