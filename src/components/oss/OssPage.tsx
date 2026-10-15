import { flowBridge } from '../../bridge/nativeBridge';

const REPOSITORY_ROOT = 'https://github.com/Darksenius/Potik-ADHD';

export default function OssPage({ open, onClose }: { open: boolean; onClose: () => void }) {
  const data = typeof window !== 'undefined' ? window.__OSS__ : null;
  const revision = data?.revision || __SOURCE_REVISION__;
  const repository = data?.github || (revision !== 'unknown' ? `${REPOSITORY_ROOT}/tree/${revision}` : REPOSITORY_ROOT);
  const sourceArchive = data?.sourceArchive || (revision !== 'unknown' ? `${REPOSITORY_ROOT}/archive/${revision}.zip` : REPOSITORY_ROOT);
  const archiveAvailable = !!data?.sourceArchive || revision !== 'unknown';
  const license = data
    ? data.license
    : 'Текст ліцензії AGPL v3 вшивається автоматично при збірці (scripts/embed-oss.js). Повний текст — у файлі LICENSE в репозиторії.';
  const source = data
    ? data.source
    : 'Текстовий перегляд вихідного коду вшивається автоматично при збірці (scripts/embed-oss.js). Для повного архіву відкрий репозиторій на GitHub.';

  return (
    <div id="oss-page" className={open ? 'open' : ''}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
        <button className="edit-back" onClick={onClose}>← Назад</button>
        <h2>Ліцензія та вихідний код</h2>
      </div>
      <p style={{ fontSize: 13, color: 'var(--t1)', fontWeight: 700, marginBottom: 6 }}>Застосунок з ліцензією AGPL v3</p>
      <a className="oss-link" href={repository} target="_blank" rel="noopener noreferrer">
        {revision !== 'unknown' ? 'Код цієї версії на GitHub' : 'Відкрити репозиторій на GitHub'}
      </a>
      {archiveAvailable && <a className="oss-link" href={sourceArchive} target="_blank" rel="noopener noreferrer">Завантажити повний вихідний код (.zip)</a>}
      <pre className="oss-pre">{license}</pre>
      <h3>Вбудований текстовий перегляд вихідного коду</h3>
      <p>{archiveAvailable
        ? 'Це текстовий перегляд файлів у самій апці. Повну копію цієї версії можна завантажити за посиланням на ZIP-архів.'
        : 'Це текстовий перегляд файлів у самій апці. Для повної копії відкрий репозиторій на GitHub.'}</p>
      <pre className="oss-pre">{source}</pre>
    </div>
  );
}

/** appVersion() — рядки 2874-2878, для показу у заголовку Довідки */
export function useAppVersion(): string {
  try {
    const fb = flowBridge();
    if (fb) return fb.appVersion();
  } catch {
    /* браузер без Capacitor — тихо ігноруємо */
  }
  return __APP_VERSION__;
}
