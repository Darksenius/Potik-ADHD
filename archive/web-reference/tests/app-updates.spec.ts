import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { compareAppVersions, parseAppVersion, parseLatestRelease } from '../src/services/appUpdates';

const releasePayload = (overrides: Record<string, unknown> = {}) => ({
  tag_name: 'v1.7.6.r10',
  draft: false,
  prerelease: false,
  published_at: '2026-10-15T10:30:00Z',
  assets: [{
    id: 42,
    name: 'Potik-v1.7.6.r10.apk',
    state: 'uploaded',
    content_type: 'application/vnd.android.package-archive',
    browser_download_url: 'https://attacker.invalid/fake.apk',
  }],
  ...overrides,
});

test('parses canonical Potik versions, including revision zero, and rejects unrevisioned or out-of-range versions', () => {
  expect(parseAppVersion('v1.7.6.r10')).toEqual({ major: 1, minor: 7, patch: 6, revision: 10 });
  expect(parseAppVersion('1.7.6.r0')).toEqual({ major: 1, minor: 7, patch: 6, revision: 0 });
  expect(parseAppVersion('1.7.7')).toBeNull();
  expect(parseAppVersion('1.7.6.r100')).toBeNull();
  expect(parseAppVersion('1.100.6.r0')).toBeNull();
});

test('compares revisions numerically so r10 is newer than r9', () => {
  expect(compareAppVersions('1.7.6.r10', '1.7.6.r9')).toBe(1);
  expect(compareAppVersions('1.7.6.r9', '1.7.6.r10')).toBe(-1);
});

test('compares patch and major components before revision', () => {
  expect(compareAppVersions('1.7.7.r0', '1.7.6.r99')).toBe(1);
  expect(compareAppVersions('2.0.0.r0', '1.99.99.r99')).toBe(1);
});

test('ignores payload download URLs and builds the APK link from the trusted repo and exact asset name', () => {
  const result = parseLatestRelease({
    ...releasePayload(),
    html_url: 'https://attacker.invalid/release',
  });
  expect(result.apkUrl).toBe('https://github.com/Darksenius/Potik-ADHD/releases/download/v1.7.6.r10/Potik-v1.7.6.r10.apk');
  expect(result.releaseUrl).toBe('https://github.com/Darksenius/Potik-ADHD/releases/tag/v1.7.6.r10');
  expect(result.date).toBe('2026-10-15T10:30:00.000Z');
});

test('keeps the trusted release page when the exact APK asset is missing', () => {
  const result = parseLatestRelease(releasePayload({ assets: [] }));
  expect(result.apkUrl).toBeNull();
  expect(result.releaseUrl).toBe('https://github.com/Darksenius/Potik-ADHD/releases/tag/v1.7.6.r10');
});

test('rejects draft, prerelease, and malformed release payloads', () => {
  expect(() => parseLatestRelease(releasePayload({ draft: true }))).toThrow();
  expect(() => parseLatestRelease(releasePayload({ prerelease: true }))).toThrow();
  expect(() => parseLatestRelease(releasePayload({ tag_name: '1.7.7' }))).toThrow();
  expect(() => parseLatestRelease({ tag_name: 'v1.7.6.r10', assets: [] })).toThrow();
});

test('checks releases only on request and keeps repository links available after a network error', async ({ page }) => {
  const apiUrl = 'https://api.github.com/repos/Darksenius/Potik-ADHD/releases/latest';
  const installedMajor = Number(JSON.parse(readFileSync('package.json', 'utf8')).version.split('.')[0]);
  const newerVersion = `${installedMajor + 1}.0.0.r1`;
  const apkUrl = `https://github.com/Darksenius/Potik-ADHD/releases/download/v${newerVersion}/Potik-v${newerVersion}.apk`;
  let requestCount = 0;
  let failRequests = false;
  await page.route(apiUrl, async route => {
    requestCount++;
    if (failRequests) {
      await route.abort('failed');
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        tag_name: `v${newerVersion}`,
        draft: false,
        prerelease: false,
        published_at: '2026-10-15T10:30:00Z',
        assets: [{
          id: 21,
          name: `Potik-v${newerVersion}.apk`,
          state: 'uploaded',
          content_type: 'application/vnd.android.package-archive',
          browser_download_url: 'https://attacker.invalid/untrusted.apk',
        }],
      }),
    });
  });

  const openSettingsAndAbout = async () => {
    await page.locator('#nav button').nth(1).click();
    const about = page.locator('#settings-page details.settings-group').filter({ hasText: 'Про застосунок і допомога' });
    await about.locator('summary').click();
    return about;
  };

  await page.goto('/');
  expect(requestCount).toBe(0);
  let about = await openSettingsAndAbout();
  expect(requestCount).toBe(0);
  const links = about.locator('.app-updates-links a');
  await expect(links).toHaveCount(2);
  await expect(links.nth(0)).toHaveAttribute('href', 'https://github.com/Darksenius/Potik-ADHD');
  await expect(links.nth(1)).toHaveAttribute('href', 'https://github.com/Darksenius/Potik-ADHD/releases/latest');

  const firstResponse = page.waitForResponse(apiUrl);
  await about.getByRole('button', { name: 'Перевірити оновлення' }).click();
  const response = await firstResponse;
  expect(response.status()).toBe(200);
  await expect(about.locator('.app-updates-result')).toContainText(`Остання версія: ${newerVersion}`);
  await expect(about.getByRole('link', { name: 'Завантажити APK' })).toHaveAttribute('href', apkUrl);
  expect(requestCount).toBe(1);

  failRequests = true;
  await page.reload();
  expect(requestCount).toBe(1);
  about = await openSettingsAndAbout();
  expect(requestCount).toBe(1);
  await about.getByRole('button', { name: 'Перевірити оновлення' }).click();
  await expect(about.getByRole('alert')).toContainText('Не вдалося перевірити оновлення');
  expect(requestCount).toBe(2);
  await expect(about.locator('.app-updates-links a')).toHaveCount(2);
});
