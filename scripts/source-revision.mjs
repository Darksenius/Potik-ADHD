import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

const exactRevision = value => typeof value === 'string' && /^[a-f0-9]{40}$/i.test(value.trim());

/** Release archives carry the same revision without needing a .git directory. */
export function getSourceRevision(root, env = process.env) {
  if (env.SOURCE_REVISION) {
    if (!exactRevision(env.SOURCE_REVISION)) throw new Error('SOURCE_REVISION must be a full 40-character Git SHA.');
    return env.SOURCE_REVISION.trim().toLowerCase();
  }
  try {
    if (!existsSync(path.join(root, '.git'))) throw new Error('No Git metadata in this source directory.');
    const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    if (exactRevision(revision)) return revision.toLowerCase();
  } catch { /* Source archives do not require Git. */ }
  try {
    const revision = readFileSync(path.join(root, 'SOURCE_REVISION'), 'utf8').trim();
    if (!exactRevision(revision)) throw new Error('SOURCE_REVISION archive marker is not a full Git SHA.');
    return revision.toLowerCase();
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  return 'unknown';
}
