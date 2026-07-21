import { test as base } from '@playwright/test';
import { createMockServer, MockServer } from './mock-server';

export { expect } from '@playwright/test';

export const test = base.extend<{}, { mockServer: MockServer }>({
  mockServer: [async ({}, use) => {
    const server = await createMockServer(Number(process.env.MOCK_SERVER_PORT) || 3099);
    await use(server);
    await server.close();
  }, { scope: 'worker' }],

  page: async ({ page, mockServer }, use) => {
    await page.route('/api/**', (route) => {
      const url = new URL(route.request().url());
      url.port = String(mockServer.port);
      return route.continue({ url: url.toString() });
    });
    mockServer.reset();
    await use(page);
  },
});
