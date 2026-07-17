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
      color: var(--pages-neutral-12);
      margin-bottom: 8px;
    }

    .subtitle {
      font-size: 16px;
      color: var(--pages-neutral-7);
      margin-bottom: 32px;
    }

    .scenarios {
      display: grid;
      gap: 20px;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    }

    .scenario-card {
      background: var(--pages-neutral-2);
      border: 1px solid var(--pages-neutral-4);
      border-radius: 8px;
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .scenario-name {
      font-size: 18px;
      font-weight: 600;
      color: var(--pages-neutral-12);
    }

    .scenario-description {
      font-size: 14px;
      color: var(--pages-neutral-11);
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
      background: var(--pages-neutral-3);
      color: var(--pages-neutral-7);
    }

    .scenario-status.running {
      background: var(--pages-warning-9);
      color: var(--pages-warning-12);
    }

    .scenario-status.completed {
      background: var(--pages-success-9);
      color: var(--pages-success-12);
    }

    .scenario-status.failed {
      background: var(--pages-danger-9);
      color: var(--pages-danger-12);
    }

    button {
      margin-top: auto;
      padding: 10px 16px;
      background: var(--pages-accent-9);
      border: none;
      border-radius: 6px;
      color: var(--pages-neutral-12);
      cursor: pointer;
      font-size: 14px;
      font-weight: 500;
      transition: background 150ms;
    }

    button:hover:not(:disabled) {
      background: var(--pages-accent-10);
    }

    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .view-button {
      background: var(--pages-neutral-3);
    }

    .view-button:hover {
      background: var(--pages-neutral-4);
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
