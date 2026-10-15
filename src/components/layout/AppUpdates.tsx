import { useState } from 'react';
import { APP_REPOSITORY_URL, LATEST_RELEASE_URL, compareAppVersions, fetchLatestAppRelease } from '../../services/appUpdates';
import type { LatestAppRelease } from '../../services/appUpdates';
import '../../styles/app-updates.css';

export default function AppUpdates({ installedVersion }: { installedVersion: string }) {
  const [release, setRelease] = useState<LatestAppRelease | null>(null);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState('');

  async function checkForUpdates() {
    setChecking(true);
    setError('');
    setRelease(null);
    try {
      setRelease(await fetchLatestAppRelease());
    } catch {
      setError('Не вдалося перевірити оновлення. Перевір з’єднання або відкрий сторінку релізів GitHub.');
    } finally {
      setChecking(false);
    }
  }

  const comparison = release ? compareAppVersions(release.version, installedVersion) : null;
  const releaseDate = release?.date
    ? new Date(release.date).toLocaleDateString('uk-UA', { year: 'numeric', month: 'long', day: 'numeric' })
    : 'дата невідома';

  return <section className="app-updates" aria-label="Оновлення застосунку">
    <div className="app-updates-links">
      <a href={APP_REPOSITORY_URL} target="_blank" rel="noopener noreferrer">Репозиторій на GitHub</a>
      <a href={LATEST_RELEASE_URL} target="_blank" rel="noopener noreferrer">Сторінка останнього релізу</a>
    </div>
    <p className="app-updates-installed">Встановлена версія: <strong>{installedVersion}</strong></p>
    <button type="button" onClick={() => { void checkForUpdates(); }} disabled={checking}>
      {checking ? 'Перевіряю…' : 'Перевірити оновлення'}
    </button>
    {checking && <p role="status">Звертаюся до GitHub…</p>}
    {error && <p role="alert">{error}</p>}
    {release && !checking && <div className="app-updates-result" aria-live="polite">
      <p>Остання версія: <strong>{release.version}</strong> · {releaseDate}</p>
      {comparison === 1 && <>
        <p>Доступне оновлення.</p>
        {release.apkUrl
          ? <a className="app-updates-download" href={release.apkUrl} target="_blank" rel="noopener noreferrer" download>Завантажити APK</a>
          : <p>APK у цьому релізі не знайдено. <a href={release.releaseUrl} target="_blank" rel="noopener noreferrer">Відкрити реліз GitHub</a>.</p>}
      </>}
      {comparison === 0 && <p>У тебе вже встановлена остання версія.</p>}
      {comparison === -1 && <p>Встановлена версія новіша за останній реліз GitHub.</p>}
      {comparison === null && <p>Не вдалося порівняти версії. Переглянь сторінку релізу GitHub.</p>}
    </div>}
  </section>;
}
