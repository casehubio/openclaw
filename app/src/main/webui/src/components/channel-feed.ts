import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { ChannelMessageEvent } from '../types/events.js';

@customElement('channel-feed')
export class ChannelFeed extends LitElement {
  @property({ type: Array }) messages: ChannelMessageEvent[] = [];

  static styles = css`
    :host {
      display: block;
      height: 100%;
    }

    .feed {
      height: 100%;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 16px;
      background: var(--blocks-surface-2, #16213e);
      border: 1px solid var(--blocks-border, #2d3748);
      border-radius: 8px;
    }

    .message {
      background: var(--blocks-surface, #1a1a2e);
      border-left: 3px solid var(--blocks-primary, #3b82f6);
      padding: 12px;
      border-radius: 4px;
    }

    .message-header {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      margin-bottom: 8px;
    }

    .message-sender {
      font-size: 14px;
      font-weight: 600;
      color: var(--blocks-text-bright, #ffffff);
    }

    .message-role {
      font-size: 12px;
      color: var(--blocks-text-dim, #a0aec0);
      margin-left: 8px;
    }

    .message-time {
      font-size: 11px;
      color: var(--blocks-text-dim, #a0aec0);
    }

    .message-content {
      font-size: 13px;
      color: var(--blocks-text, #e0e0e0);
      line-height: 1.5;
      white-space: pre-wrap;
    }

    @media (prefers-reduced-motion: reduce) {
      .feed {
        scroll-behavior: auto;
      }
    }
  `;

  updated(changedProperties: Map<string, unknown>) {
    super.updated(changedProperties);
    if (changedProperties.has('messages')) {
      this.scrollToBottom();
    }
  }

  private scrollToBottom() {
    const feed = this.shadowRoot?.querySelector('.feed');
    if (feed) {
      feed.scrollTop = feed.scrollHeight;
    }
  }

  private formatTime(isoString: string): string {
    try {
      const date = new Date(isoString);
      return date.toLocaleTimeString('en-US', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      });
    } catch {
      return '';
    }
  }

  render() {
    return html`
      <div class="feed" role="log" aria-live="polite" aria-label="Channel message feed">
        ${this.messages.map(msg => html`
          <div class="message">
            <div class="message-header">
              <div>
                <span class="message-sender">${msg.agentId}</span>
                <span class="message-role">${msg.role}</span>
              </div>
              <span class="message-time">${this.formatTime(msg.occurredAt)}</span>
            </div>
            <div class="message-content">${msg.content}</div>
          </div>
        `)}
      </div>
    `;
  }
}
