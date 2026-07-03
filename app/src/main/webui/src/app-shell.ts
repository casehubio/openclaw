import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';

@customElement('app-shell')
export class AppShell extends LitElement {
  @state() private currentView = 'overview';

  static styles = css`
    :host {
      display: flex;
      height: 100vh;
      color: var(--blocks-text, #e0e0e0);
      background: var(--blocks-surface, #1a1a2e);
      font-family: var(--blocks-font, system-ui, sans-serif);
    }

    nav {
      width: 240px;
      background: var(--blocks-surface-2, #16213e);
      padding: 24px 16px;
      display: flex;
      flex-direction: column;
      gap: 24px;
      border-right: 1px solid var(--blocks-border, #2d3748);
    }

    h2 {
      font-size: 18px;
      font-weight: 600;
      color: var(--blocks-text-bright, #ffffff);
      margin-bottom: 8px;
    }

    ul {
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    li {
      padding: 12px 16px;
      border-radius: 6px;
      cursor: pointer;
      color: var(--blocks-text-dim, #a0aec0);
      transition: background 150ms, color 150ms;
    }

    li:hover {
      background: var(--blocks-surface-hover, #243447);
      color: var(--blocks-text, #e0e0e0);
    }

    li.active {
      background: var(--blocks-primary, #3b82f6);
      color: var(--blocks-text-bright, #ffffff);
    }

    button {
      margin-top: auto;
      padding: 10px 16px;
      background: var(--blocks-surface-3, #2d3748);
      border: 1px solid var(--blocks-border, #4a5568);
      border-radius: 6px;
      color: var(--blocks-text, #e0e0e0);
      cursor: pointer;
      font-size: 14px;
      transition: background 150ms;
    }

    button:hover {
      background: var(--blocks-surface-hover, #374151);
    }

    main {
      flex: 1;
      overflow: auto;
      padding: 24px;
    }

    @media (prefers-reduced-motion: reduce) {
      li,
      button {
        transition: none;
      }
    }
  `;

  render() {
    return html`
      <nav>
        <div>
          <h2>CaseHub Demo</h2>
          <ul>
            <li class=${this.currentView === 'overview' ? 'active' : ''} @click=${() => this.currentView = 'overview'}>
              Overview
            </li>
            <li class=${this.currentView === 'trading-oversight' ? 'active' : ''} @click=${() => this.currentView = 'trading-oversight'}>
              Trading Oversight
            </li>
            <li class=${this.currentView === 'multi-agent-dev-team' ? 'active' : ''} @click=${() => this.currentView = 'multi-agent-dev-team'}>
              Dev Team
            </li>
            <li class=${this.currentView === 'incident-response' ? 'active' : ''} @click=${() => this.currentView = 'incident-response'}>
              Incident Response
            </li>
          </ul>
        </div>
        <button @click=${this.toggleTheme}>Toggle Theme</button>
      </nav>
      <main>
        ${this.currentView === 'overview'
          ? html`<demo-launcher @scenario-selected=${this.handleScenarioSelected}></demo-launcher>`
          : html`<case-execution-view .scenarioId=${this.currentView}></case-execution-view>`}
      </main>
    `;
  }

  private handleScenarioSelected(e: CustomEvent<{ scenarioId: string }>) {
    this.currentView = e.detail.scenarioId;
  }

  private toggleTheme() {
    document.documentElement.classList.toggle('light');
  }
}
