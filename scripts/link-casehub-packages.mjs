#!/usr/bin/env node
import { readdirSync, readFileSync, mkdirSync, symlinkSync, existsSync, lstatSync } from 'fs';
import { join, relative } from 'path';

const webui = process.cwd();
const packagesDir = join(webui, '.casehub-packages', 'packages');
const nodeModules = join(webui, 'node_modules', '@casehubio');

if (!existsSync(packagesDir)) {
  console.log('No .casehub-packages/packages — skipping');
  process.exit(0);
}

mkdirSync(nodeModules, { recursive: true });

let linked = 0;
for (const dir of readdirSync(packagesDir)) {
  const pkgJsonPath = join(packagesDir, dir, 'package.json');
  if (!existsSync(pkgJsonPath)) continue;
  const pkg = JSON.parse(readFileSync(pkgJsonPath, 'utf8'));
  const name = pkg.name;
  if (!name || !name.startsWith('@casehubio/')) continue;
  const shortName = name.replace('@casehubio/', '');
  const targetDir = join(nodeModules, shortName);
  if (existsSync(targetDir)) {
    const stat = lstatSync(targetDir);
    if (stat.isSymbolicLink() || stat.isDirectory()) continue;
  }
  const relPath = relative(nodeModules, join(packagesDir, dir));
  symlinkSync(relPath, targetDir);
  linked++;
}
console.log(`Linked ${linked} @casehubio packages into node_modules`);
