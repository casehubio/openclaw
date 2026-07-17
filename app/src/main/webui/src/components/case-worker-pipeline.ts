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
      background: var(--pages-neutral-2);
      border: 1px solid var(--pages-neutral-4);
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
      color: var(--pages-neutral-12);
    }

    .worker-role {
      font-size: 13px;
      color: var(--pages-neutral-7);
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
      background: var(--pages-neutral-3);
      color: var(--pages-neutral-7);
    }

    .worker-state.running {
      background: var(--pages-info-9);
      color: var(--pages-info-12);
    }

    .worker-state.completed {
      background: var(--pages-success-9);
      color: var(--pages-success-12);
    }

    .worker-state.failed {
      background: var(--pages-danger-9);
      color: var(--pages-danger-12);
    }

    .worker-state.declined,
    .worker-state.delegated {
      background: var(--pages-warning-9);
      color: var(--pages-warning-12);
    }

    .worker-state.timeout {
      background: var(--pages-danger-9);
      color: var(--pages-danger-12);
    }

    .worker-duration {
      font-size: 13px;
      color: var(--pages-neutral-11);
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
