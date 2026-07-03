import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { GateState } from '../types/events.js';

@customElement('gate-approval-modal')
export class GateApprovalModal extends LitElement {
  @property({ type: Object }) gate: GateState | null = null;
  @property({ type: String }) scenarioId: string = '';
  @state() private submitting = false;

  static styles = css`
    :host {
      display: none;
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      z-index: 1000;
      align-items: center;
      justify-content: center;
    }

    :host([open]) {
      display: flex;
    }

    .backdrop {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(0, 0, 0, 0.7);
    }

    .modal {
      position: relative;
      background: var(--blocks-surface, #1a1a2e);
      border: 1px solid var(--blocks-border, #2d3748);
      border-radius: 12px;
      padding: 32px;
      max-width: 600px;
      width: 90%;
      max-height: 80vh;
      overflow-y: auto;
      box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5);
    }

    h2 {
      font-size: 24px;
      font-weight: 700;
      color: var(--blocks-text-bright, #ffffff);
      margin-bottom: 8px;
    }

    .subtitle {
      font-size: 14px;
      color: var(--blocks-text-dim, #a0aec0);
      margin-bottom: 24px;
    }

    .field {
      margin-bottom: 20px;
    }

    .field-label {
      font-size: 12px;
      font-weight: 600;
      text-transform: uppercase;
      color: var(--blocks-text-dim, #a0aec0);
      margin-bottom: 8px;
    }

    .field-value {
      font-size: 15px;
      color: var(--blocks-text, #e0e0e0);
      line-height: 1.5;
    }

    .classification {
      display: inline-block;
      padding: 6px 12px;
      border-radius: 12px;
      font-size: 13px;
      font-weight: 600;
      background: var(--blocks-warning-bg, #fbbf24);
      color: var(--blocks-warning-text, #78350f);
    }

    .actions {
      display: flex;
      gap: 12px;
      margin-top: 32px;
    }

    button {
      flex: 1;
      padding: 12px 24px;
      border: none;
      border-radius: 6px;
      font-size: 15px;
      font-weight: 600;
      cursor: pointer;
      transition: background 150ms;
    }

    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .approve {
      background: var(--blocks-success-bg, #10b981);
      color: var(--blocks-success-text, #064e3b);
    }

    .approve:hover:not(:disabled) {
      background: var(--blocks-success-hover, #059669);
    }

    .reject {
      background: var(--blocks-error-bg, #ef4444);
      color: var(--blocks-error-text, #7f1d1d);
    }

    .reject:hover:not(:disabled) {
      background: var(--blocks-error-hover, #dc2626);
    }

    @media (prefers-reduced-motion: reduce) {
      button {
        transition: none;
      }
    }
  `;

  connectedCallback() {
    super.connectedCallback();
    this.addEventListener('keydown', this.handleKeydown);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener('keydown', this.handleKeydown);
  }

  private handleKeydown = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && this.gate) {
      this.handleReject();
    }
  };

  private async handleApprove() {
    if (!this.gate || this.submitting) return;
    this.submitting = true;

    try {
      const response = await fetch(`/api/scenarios/${this.scenarioId}/gate/${this.gate.gateId}/approve`, {
        method: 'POST',
      });
      if (!response.ok) throw new Error('Failed to approve gate');
    } catch (error) {
      console.error('Failed to approve gate:', error);
    } finally {
      this.submitting = false;
    }
  }

  private async handleReject() {
    if (!this.gate || this.submitting) return;
    this.submitting = true;

    try {
      const response = await fetch(`/api/scenarios/${this.scenarioId}/gate/${this.gate.gateId}/reject`, {
        method: 'POST',
      });
      if (!response.ok) throw new Error('Failed to reject gate');
    } catch (error) {
      console.error('Failed to reject gate:', error);
    } finally {
      this.submitting = false;
    }
  }

  render() {
    if (!this.gate) return html``;

    return html`
      <div class="backdrop" @click=${this.handleReject}></div>
      <div
        class="modal"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        @click=${(e: Event) => e.stopPropagation()}
      >
        <h2 id="modal-title">Oversight Gate</h2>
        <p class="subtitle">Review and approve or reject this action</p>

        <div class="field">
          <div class="field-label">Action</div>
          <div class="field-value">${this.gate.action}</div>
        </div>

        <div class="field">
          <div class="field-label">Classification</div>
          <div class="classification">${this.gate.classification}</div>
        </div>

        <div class="field">
          <div class="field-label">Agent</div>
          <div class="field-value">${this.gate.agentId}</div>
        </div>

        <div class="field">
          <div class="field-label">Prior Agents</div>
          <div class="field-value">${this.gate.priorAgents || 'None'}</div>
        </div>

        <div class="actions">
          <button
            class="approve"
            @click=${this.handleApprove}
            ?disabled=${this.submitting}
            aria-label="Approve gate"
          >
            ${this.submitting ? 'Submitting...' : 'Approve'}
          </button>
          <button
            class="reject"
            @click=${this.handleReject}
            ?disabled=${this.submitting}
            aria-label="Reject gate"
          >
            Reject
          </button>
        </div>
      </div>
    `;
  }

  updated(changedProperties: Map<string, unknown>) {
    super.updated(changedProperties);
    if (changedProperties.has('gate')) {
      if (this.gate) {
        this.setAttribute('open', '');
        this.focusModal();
      } else {
        this.removeAttribute('open');
      }
    }
  }

  private focusModal() {
    requestAnimationFrame(() => {
      const approveButton = this.shadowRoot?.querySelector('.approve') as HTMLElement;
      approveButton?.focus();
    });
  }
}
