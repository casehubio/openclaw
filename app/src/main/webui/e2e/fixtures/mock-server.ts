import { createServer, IncomingMessage, ServerResponse } from 'http';

interface RecordedCall {
  method: string;
  path: string;
  body: any;
}

interface NextResponse {
  statusCode: number;
  body?: object;
}

export class MockServer {
  private server: ReturnType<typeof createServer>;
  private sseClients = new Set<ServerResponse>();
  private sseAccepting = true;
  private sseConnectionCount = 0;
  private scenarios: any[] = [];
  private stateSnapshots = new Map<string, any>();
  private recordedCalls: RecordedCall[] = [];
  private nextResponses = new Map<string, NextResponse>();
  readonly port: number;

  constructor(port: number) {
    this.port = port;
    this.server = createServer((req, res) => this.handleRequest(req, res));
  }

  async start(): Promise<void> {
    return new Promise((resolve) => {
      this.server.listen(this.port, () => resolve());
    });
  }

  async close(): Promise<void> {
    this.disconnectSSE();
    return new Promise((resolve) => {
      this.server.close(() => resolve());
    });
  }

  setScenarios(list: any[]) { this.scenarios = list; }
  setStateSnapshot(id: string, snapshot: any) { this.stateSnapshots.set(id, snapshot); }
  getRecordedCalls() { return [...this.recordedCalls]; }

  setNextResponse(path: string, statusCode: number, body?: object) {
    this.nextResponses.set(path, { statusCode, body });
  }

  reset() {
    this.scenarios = [];
    this.stateSnapshots.clear();
    this.recordedCalls = [];
    this.nextResponses.clear();
    this.disconnectSSE();
    this.sseAccepting = true;
  }

  emitEvent(event: any) {
    const data = `data: ${JSON.stringify(event)}\n\n`;
    for (const client of this.sseClients) {
      client.write(data);
    }
  }

  async emitSequence(events: any[], intervalMs = 100) {
    for (const event of events) {
      this.emitEvent(event);
      if (intervalMs > 0) await new Promise(r => setTimeout(r, intervalMs));
    }
  }

  disconnectSSE() {
    for (const client of this.sseClients) {
      client.end();
    }
    this.sseClients.clear();
    this.sseAccepting = false;
  }

  reconnectSSE() {
    this.sseAccepting = true;
  }

  waitForSSEClient(timeoutMs = 5000): Promise<void> {
    if (this.sseClients.size > 0) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const interval = setInterval(() => {
        if (this.sseClients.size > 0) {
          clearInterval(interval);
          clearTimeout(timer);
          resolve();
        }
      }, 50);
      const timer = setTimeout(() => {
        clearInterval(interval);
        reject(new Error('Timed out waiting for SSE client'));
      }, timeoutMs);
    });
  }

  waitForFreshSSEClient(timeoutMs = 5000): Promise<void> {
    const countBefore = this.sseConnectionCount;
    return new Promise((resolve, reject) => {
      const interval = setInterval(() => {
        if (this.sseConnectionCount > countBefore) {
          clearInterval(interval);
          clearTimeout(timer);
          resolve();
        }
      }, 50);
      const timer = setTimeout(() => {
        clearInterval(interval);
        reject(new Error('Timed out waiting for fresh SSE client'));
      }, timeoutMs);
    });
  }

  private async handleRequest(req: IncomingMessage, res: ServerResponse) {
    const url = new URL(req.url!, `http://localhost:${this.port}`);
    const path = url.pathname;

    // Check for one-shot response override (non-GET only)
    if (req.method !== 'GET') {
      const override = this.nextResponses.get(path);
      if (override) {
        this.nextResponses.delete(path);
        const body = await readBody(req);
        this.recordedCalls.push({ method: req.method!, path, body });
        res.writeHead(override.statusCode, { 'Content-Type': 'application/json' });
        res.end(override.body ? JSON.stringify(override.body) : '');
        return;
      }
    }

    // SSE endpoint
    if (path === '/api/scenarios/events' && req.method === 'GET') {
      if (!this.sseAccepting) {
        res.writeHead(503);
        res.end();
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
      });
      res.write('\n');
      this.sseClients.add(res);
      this.sseConnectionCount++;
      req.on('close', () => this.sseClients.delete(res));
      return;
    }

    // GET /api/scenarios
    if (path === '/api/scenarios' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(this.scenarios));
      return;
    }

    // GET /api/scenarios/{id}/state
    const stateMatch = path.match(/^\/api\/scenarios\/([^/]+)\/state$/);
    if (stateMatch && req.method === 'GET') {
      const snapshot = this.stateSnapshots.get(stateMatch[1]);
      res.writeHead(snapshot ? 200 : 404, { 'Content-Type': 'application/json' });
      res.end(snapshot ? JSON.stringify(snapshot) : '{}');
      return;
    }

    // POST /api/scenarios/{id}/start
    const startMatch = path.match(/^\/api\/scenarios\/([^/]+)\/start$/);
    if (startMatch && req.method === 'POST') {
      this.recordedCalls.push({ method: 'POST', path, body: null });
      res.writeHead(202);
      res.end();
      return;
    }

    // PUT /api/scenarios/{id}/workitems/{gateId}/complete
    const gateMatch = path.match(/^\/api\/scenarios\/([^/]+)\/workitems\/([^/]+)\/complete$/);
    if (gateMatch && req.method === 'PUT') {
      const body = await readBody(req);
      this.recordedCalls.push({ method: 'PUT', path, body });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'ok' }));
      return;
    }

    res.writeHead(404);
    res.end();
  }
}

function readBody(req: IncomingMessage): Promise<any> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString();
      try { resolve(JSON.parse(raw)); }
      catch { resolve(raw || null); }
    });
  });
}

export async function createMockServer(port: number): Promise<MockServer> {
  const server = new MockServer(port);
  await server.start();
  return server;
}
