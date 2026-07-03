import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import type { ScenarioDef, CaseExecutionEvent } from '../types/events.js';

@customElement('demo-launcher')
export class DemoLauncher extends LitElement {
  @state() private scenarios: ScenarioDef[] = [];
  private eventSource: EventSource | null = null;

  static styles = css`
    :host {
      display: block;
    }

    h1 {
      font-size: 28px;
      font-weight: 700;
      color: var(--blocks-text-bright, #ffffff);
      margin-bottom: 8px;
    }

    .subtitle {
      font-size: 16px;
      color: var(--blocks-text-dim, #a0aec0);
      margin-bottom: 32px;
    }

    .scenarios {
      display: grid;
      gap: 20px;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    }

    .scenario-card {
      background: var(--blocks-surface-2, #16213e);
      border: 1px solid var(--blocks-border, #2d3748);
      border-radius: 8px;
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .scenario-name {
      font-size: 18px;
      font-weight: 600;
      color: var(--blocks-text-bright, #ffffff);
    }

    .scenario-description {
      font-size: 14px;
      color: var(--blocks-text, #e0e0e0);
      line-height: 1.5;
    }

    .scenario-status {
      display: inline-block;
      padding: 4px 12px;
      border-radius: 12px;
      font-size: 12px;
      font-weight: 500;
      text-transform: uppercase;
    }

    .scenario-status.idle {
      background: var(--blocks-surface-3, #2d3748);
      color: var(--blocks-text-dim, #a0aec0);
    }

    .scenario-status.running {
      background: var(--blocks-warning-bg, #fbbf24);
      color: var(--blocks-warning-text, #78350f);
    }

    .scenario-status.completed {
      background: var(--blocks-success-bg, #10b981);
      color: var(--blocks-success-text, #064e3b);
    }

    .scenario-status.failed {
      background: var(--blocks-error-bg, #ef4444);
      color: var(--blocks-error-text, #7f1d1d);
    }

    button {
      margin-top: auto;
      padding: 10px 16px;
      background: var(--blocks-primary, #3b82f6);
      border: none;
      border-radius: 6px;
      color: var(--blocks-text-bright, #ffffff);
      cursor: pointer;
      font-size: 14px;
      font-weight: 500;
      transition: background 150ms;
    }

    button:hover:not(:disabled) {
      background: var(--blocks-primary-hover, #2563eb);
    }

    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .view-button {
      background: var(--blocks-surface-3, #2d3748);
    }

    .view-button:hover {
      background: var(--blocks-surface-hover, #374151);
    }

    @media (prefers-reduced-motion: reduce) {
      button {
        transition: none;
      }
    }
  `;

  connectedCallback() {
    super.connectedCallback();
    this.loadScenarios();
    this.subscribeToSSE();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.eventSource?.close();
  }

  private async loadScenarios() {
    try {
      const response = await fetch('/api/scenarios');
      if (!response.ok) throw new Error('Failed to load scenarios');
      this.scenarios = await response.json();
    } catch (error) {
      console.error('Failed to load scenarios:', error);
    }
  }

  private subscribeToSSE() {
    this.eventSource = new EventSource('/api/scenarios/events');
    this.eventSource.onmessage = (e) => {
      try {
        const event = JSON.parse(e.data) as CaseExecutionEvent;
        this.handleSSEEvent(event);
      } catch (error) {
        console.error('Failed to parse SSE event:', error);
      }
    };
    this.eventSource.onerror = (error) => {
      console.error('SSE connection error:', error);
    };
  }

  private handleSSEEvent(event: CaseExecutionEvent) {
    const index = this.scenarios.findIndex(s => s.id === event.scenarioId);
    if (index === -1) return;

    this.scenarios = this.scenarios.map((s, i) => {
      if (i !== index) return s;

      if (event.type === 'SCENARIO_STARTED') {
        return { ...s, status: 'running' as const };
      } else if (event.type === 'SCENARIO_COMPLETED') {
        return { ...s, status: 'completed' as const };
      } else if (event.type === 'SCENARIO_FAILED') {
        return { ...s, status: 'failed' as const };
      }
      return s;
    });
  }

  private async startScenario(scenarioId: string) {
    try {
      const response = await fetch(`/api/scenarios/${scenarioId}/start`, {
        method: 'POST',
      });
      if (response.status === 409) {
        console.warn('Scenario already running');
      } else if (!response.ok) {
        throw new Error('Failed to start scenario');
      }
    } catch (error) {
      console.error('Failed to start scenario:', error);
    }
  }

  private viewScenario(scenarioId: string) {
    this.dispatchEvent(new CustomEvent('scenario-selected', {
      detail: { scenarioId },
      bubbles: true,
      composed: true,
    }));
  }

  render() {
    return html`
      <h1>Demo Scenarios</h1>
      <p class="subtitle">
        Explore CaseHub's accountability primitives through multi-agent workflows
      </p>
      <div class="scenarios">
        ${this.scenarios.map(scenario => html`
          <div class="scenario-card">
            <div class="scenario-name">${scenario.name}</div>
            <div class="scenario-description">${scenario.description}</div>
            <span class="scenario-status ${scenario.status}">${scenario.status}</span>
            ${scenario.status === 'idle'
              ? html`<button @click=${() => this.startScenario(scenario.id)}>Start</button>`
              : html`<button class="view-button" @click=${() => this.viewScenario(scenario.id)}>View</button>`}
          </div>
        `)}
      </div>
    `;
  }
}
