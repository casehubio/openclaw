import { injectTheme, applyThemeMode, DEFAULT_THEME } from '@casehubio/pages-ui-tokens';
import './app-shell.js';
import '@casehubio/mosaic-split-workbench';
import './components/demo-launcher.js';
import './components/case-execution-view.js';
import './components/case-worker-pipeline.js';
import '@casehubio/mosaic-channel-activity';
import '@casehubio/mosaic-approval-gate';
import '@casehubio/pages-primitives';

injectTheme(DEFAULT_THEME);
applyThemeMode(document.documentElement, 'dark');
