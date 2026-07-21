import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  workers: 1,
  globalSetup: './fixtures/setup.ts',
  globalTeardown: './fixtures/teardown.ts',
  use: {
    baseURL: `http://localhost:${process.env.UI_PORT || 3098}`,
  },
});
