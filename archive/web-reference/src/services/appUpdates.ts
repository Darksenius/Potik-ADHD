export const APP_REPOSITORY_URL = 'https://github.com/Darksenius/Potik-ADHD';
export const LATEST_RELEASE_URL = `${APP_REPOSITORY_URL}/releases/latest`;

const LATEST_RELEASE_API_URL = 'https://api.github.com/repos/Darksenius/Potik-ADHD/releases/latest';
const REQUEST_TIMEOUT_MS = 10_000;

export interface AppVersion {
  major: number;
  minor: number;
  patch: number;
  revision: number;
}

export interface LatestAppRelease {
  version: string;
  date: string | null;
  releaseUrl: string;
  apkUrl: string | null;
}

/** Parses Potik versions such as 1.7.6.r10, v1.7.6.r10, or 1.7.6-r10. */
export function parseAppVersion(value: string): AppVersion | null {
  const match = /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.r|-r)(0|[1-9]\d*)$/i.exec(value.trim());
  if (!match) return null;
  const parts = match.slice(1).map(Number);
  if (parts.some(part => !Number.isSafeInteger(part))) return null;
  const [major, minor, patch, revision] = parts;
  if (minor > 99 || patch > 99 || revision > 99) return null;
  const versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + revision;
  if (!Number.isSafeInteger(versionCode) || versionCode < 1 || versionCode > 2_100_000_000) return null;
  return { major, minor, patch, revision };
}

/** Compares Potik's numeric version tuple; returns -1, 0, or 1. */
export function compareAppVersions(left: string, right: string): number | null {
  const a = parseAppVersion(left);
  const b = parseAppVersion(right);
  if (!a || !b) return null;
  for (const key of ['major', 'minor', 'patch', 'revision'] as const) {
    if (a[key] !== b[key]) return a[key] < b[key] ? -1 : 1;
  }
  return 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Validates GitHub's latest-release payload and only builds a trusted repo APK URL. */
export function parseLatestRelease(payload: unknown): LatestAppRelease {
  if (!isRecord(payload) || payload.draft !== false || payload.prerelease !== false) {
    throw new Error('Invalid release');
  }
  if (typeof payload.tag_name !== 'string') throw new Error('Invalid release tag');
  const parsed = parseAppVersion(payload.tag_name);
  if (!parsed) throw new Error('Unsupported release version');

  const version = `${parsed.major}.${parsed.minor}.${parsed.patch}.r${parsed.revision}`;
  const tag = `v${version}`;
  if (payload.tag_name !== tag && payload.tag_name !== version) throw new Error('Non-canonical release tag');
  const releaseUrl = `${APP_REPOSITORY_URL}/releases/tag/${encodeURIComponent(tag)}`;
  const publishedAt = typeof payload.published_at === 'string' ? payload.published_at : '';
  const createdAt = typeof payload.created_at === 'string' ? payload.created_at : '';
  const dateValue = publishedAt || createdAt;
  const date = dateValue && Number.isFinite(Date.parse(dateValue)) ? new Date(dateValue).toISOString() : null;

  const apkName = `Potik-v${version}.apk`;
  const assets = Array.isArray(payload.assets) ? payload.assets : [];
  const hasUploadedApk = assets.some(asset => isRecord(asset)
    && asset.name === apkName
    && asset.state === 'uploaded'
    && Number.isSafeInteger(asset.id)
    && (asset.id as number) > 0
    && (asset.content_type === 'application/vnd.android.package-archive' || asset.content_type === 'application/octet-stream'));
  const apkUrl = hasUploadedApk
    ? `${APP_REPOSITORY_URL}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(apkName)}`
    : null;

  return { version, date, releaseUrl, apkUrl };
}

export async function fetchLatestAppRelease(): Promise<LatestAppRelease> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(LATEST_RELEASE_API_URL, {
      method: 'GET',
      headers: { Accept: 'application/vnd.github+json' },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error('Release check failed');
    return parseLatestRelease(await response.json() as unknown);
  } finally {
    window.clearTimeout(timeout);
  }
}
