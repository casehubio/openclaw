async function globalTeardown() {
  const server = (globalThis as any).__UI_SERVER__;
  if (server) {
    await new Promise<void>((resolve) => server.close(resolve));
  }
}

export default globalTeardown;
