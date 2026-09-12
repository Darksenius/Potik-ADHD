import { flowBridge } from '../../bridge/nativeBridge';

export default function OssPage({ open, onClose }: { open: boolean; onClose: () => void }) {
  const data = typeof window !== 'undefined' ? window.__OSS__ : null;
  const license = data
    ? data.license
    : 'Текст ліцензії AGPL v3 вшивається автоматично при збірці (scripts/embed-oss.js). Повний текст — у файлі LICENSE в репозиторії.';
  const source = data
    ? data.source
    : 'Повний вихідний код вшивається автоматично при збірці (scripts/embed-oss.js). Див. репозиторій на GitHub за посиланням вище.';

  return (
    <div id="oss-page" className={open ? 'open' : ''}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
        <button className="edit-back" onClick={onClose}>← Назад</button>
        <h2>Ліцензія та вихідний код</h2>
      </div>
      <p style={{ fontSize: 13, color: 'var(--t1)', fontWeight: 700, marginBottom: 6 }}>Застосунок з ліцензією AGPL v3</p>
      <a className="oss-link" href="https://github.com/Darksenius/Potik-ADHD" target="_blank" rel="noopener noreferrer">
        github.com/Darksenius/Potik-ADHD
      </a>
      <pre className="oss-pre">{license}</pre>
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
  return '';
}
