import { build } from 'esbuild';
import { cpSync, mkdirSync } from 'fs';

mkdirSync('dist', { recursive: true });
cpSync('src/index.html', 'dist/index.html');

await build({
  entryPoints: ['src/index.ts'],
  bundle: true,
  outdir: 'dist',
  format: 'esm',
  minify: process.env.NODE_ENV === 'production',
  sourcemap: true,
});
