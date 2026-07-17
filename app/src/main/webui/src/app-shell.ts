import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { applyThemeMode } from '@casehubio/pages-ui-tokens';

@customElement('app-shell')
export class AppShell extends LitElement {
  @state() private currentView = 'overview';
  @state() private themeMode: 'dark' | 'light' = 'dark';

  static styles = css`
    :host {
      display: flex;
      height: 100vh;
      color: var(--pages-neutral-11);
      background: var(--pages-neutral-1);
      font-family: var(--pages-font-family);
    }

    nav {
      width: 240px;
      background: var(--pages-neutral-2);
      padding: 24px 16px;
      display: flex;
      flex-direction: column;
      gap: 24px;
      border-right: 1px solid var(--pages-neutral-4);
    }

    h2 {
      font-size: 18px;
      font-weight: 600;
      color: var(--pages-neutral-12);
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
      color: var(--pages-neutral-7);
      transition: background 150ms, color 150ms;
    }

    li:hover {
      background: var(--pages-neutral-4);
      color: var(--pages-neutral-11);
    }

    li.active {
      background: var(--pages-accent-9);
      color: var(--pages-neutral-12);
    }

    button {
      margin-top: auto;
      padding: 10px 16px;
      background: var(--pages-neutral-3);
      border: 1px solid var(--pages-neutral-4);
      border-radius: 6px;
      color: var(--pages-neutral-11);
      cursor: pointer;
      font-size: 14px;
      transition: background 150ms;
    }

    button:hover {
      background: var(--pages-neutral-4);
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
    this.themeMode = this.themeMode === 'dark' ? 'light' : 'dark';
    applyThemeMode(document.documentElement, this.themeMode);
  }
}
