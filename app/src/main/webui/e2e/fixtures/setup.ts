import { execSync } from 'child_process';
import { createServer } from 'http';
import { readFileSync, existsSync } from 'fs';
import { join, extname } from 'path';

const MIME_TYPES: Record<string, string> = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.map': 'application/json',
};

async function globalSetup() {
  const webui = join(__dirname, '../..');
  const dist = join(webui, 'dist');

  execSync('node esbuild.config.mjs', { cwd: webui, stdio: 'pipe' });

  const port = Number(process.env.UI_PORT) || 3098;
  const server = createServer((req, res) => {
    const filePath = join(dist, req.url === '/' ? 'index.html' : req.url!);
    if (!existsSync(filePath)) {
      res.writeHead(404);
      res.end();
      return;
    }
    const ext = extname(filePath);
    res.writeHead(200, { 'Content-Type': MIME_TYPES[ext] || 'application/octet-stream' });
    res.end(readFileSync(filePath));
  });

  await new Promise<void>((resolve) => server.listen(port, resolve));
  (globalThis as any).__UI_SERVER__ = server;
}

export default globalSetup;
