import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { AgentState } from '../types/events.js';

@customElement('case-worker-pipeline')
export class CaseWorkerPipeline extends LitElement {
  @property({ type: Array }) workers: AgentState[] = [];

  static styles = css`
    :host {
      display: block;
    }

    .pipeline {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .worker {
      background: var(--blocks-surface-2, #16213e);
      border: 1px solid var(--blocks-border, #2d3748);
      border-radius: 8px;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .worker-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .worker-name {
      font-size: 16px;
      font-weight: 600;
      color: var(--blocks-text-bright, #ffffff);
    }

    .worker-role {
      font-size: 13px;
      color: var(--blocks-text-dim, #a0aec0);
    }

    .worker-state {
      display: inline-block;
      padding: 4px 10px;
      border-radius: 12px;
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
    }

    .worker-state.waiting {
      background: var(--blocks-surface-3, #2d3748);
      color: var(--blocks-text-dim, #a0aec0);
    }

    .worker-state.running {
      background: var(--blocks-info-bg, #3b82f6);
      color: var(--blocks-info-text, #eff6ff);
    }

    .worker-state.completed {
      background: var(--blocks-success-bg, #10b981);
      color: var(--blocks-success-text, #064e3b);
    }

    .worker-state.failed {
      background: var(--blocks-error-bg, #ef4444);
      color: var(--blocks-error-text, #7f1d1d);
    }

    .worker-state.declined,
    .worker-state.delegated {
      background: var(--blocks-warning-bg, #fbbf24);
      color: var(--blocks-warning-text, #78350f);
    }

    .worker-state.timeout {
      background: var(--blocks-error-bg, #ef4444);
      color: var(--blocks-error-text, #7f1d1d);
    }

    .worker-duration {
      font-size: 13px;
      color: var(--blocks-text, #e0e0e0);
    }

    @media (prefers-reduced-motion: reduce) {
      * {
        transition: none;
      }
    }
  `;

  render() {
    return html`
      <div class="pipeline" role="list" aria-label="Agent pipeline">
        ${this.workers.map(worker => html`
          <div class="worker" role="listitem">
            <div class="worker-header">
              <div>
                <div class="worker-name">${worker.agentId}</div>
                <div class="worker-role">${worker.role}</div>
              </div>
              <span class="worker-state ${worker.state}">${worker.state}</span>
            </div>
            ${worker.durationMs !== null ? html`
              <div class="worker-duration">
                Completed in ${(worker.durationMs / 1000).toFixed(1)}s
              </div>
            ` : ''}
          </div>
        `)}
      </div>
    `;
  }
}
