import { useStore } from '../../state/store';
import { continueWithCurrentState } from '../../services/persistence';
import { importState, exportRecoveryFile } from '../../services/exportImport';

export default function BackupBanner() {
  const banner = useStore((s) => s.backupBanner);
  const dismiss = useStore((s) => s.dismissBackupBanner);
  const showToast = useStore((s) => s.showToast);

  if (!banner) return null;

  const base: React.CSSProperties = {
    position: 'fixed', top: 0, left: '50%', transform: 'translateX(-50%)', width: '100%', maxWidth: 520,
    background: '#1a2a1a', borderBottom: '2px solid #7ed321', padding: '12px 16px', zIndex: 9999, maxHeight: '85vh', overflowY: 'auto',
    fontFamily: "'Syne',sans-serif", fontSize: 13, color: '#e0e0e0', boxShadow: '0 4px 20px rgba(0,0,0,.6)',
  };

  if (banner === 'unreadable') {
    return (
      <div className="recovery-banner" style={base}>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 18 }}>⚠️</span>
          <div style={{ flex: 1, minWidth: 180 }}>
            <div style={{ fontWeight: 700, color: '#f5c542' }}>Не вдалося автоматично архівувати дані</div>
            <div style={{ fontSize: 11, color: '#bbb', marginTop: 2 }}>Не вдалося записати архів або нові дані. Старі оригінали не перезаписано. Повтори спробу або збережи їх у файл.</div>
            <button onClick={() => exportRecoveryFile()}>Зберегти оригінали</button>
            <button onClick={() => continueWithCurrentState()}>Повторити архівацію</button>
          </div>
          <button
            onClick={dismiss}
            style={{ background: 'none', border: '1px solid rgba(255,255,255,.2)', borderRadius: 7, padding: '5px 12px', color: '#e0e0e0', cursor: 'pointer', fontFamily: "'Syne',sans-serif" }}
          >
            OK
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="recovery-banner" style={base}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
        <span style={{ fontSize: 18 }}>💾</span>
        <div style={{ flex: 1, minWidth: 180 }}>
          <div style={{ fontWeight: 700, color: '#7ed321' }}>Знайдено резервну копію</div>
          <div style={{ fontSize: 11, color: '#aaa', marginTop: 2 }}>{banner.saveDate} · {banner.taskCount} задач</div>
        </div>
        <button
          onClick={() => {
            try { importState(banner.data, 'restore'); showToast('✓ Дані відновлено!'); dismiss(); }
            catch (e) { showToast(e instanceof Error ? e.message : 'Не вдалося відновити дані'); }
          }}
          style={{ background: '#7ed321', border: 'none', borderRadius: 7, padding: '6px 14px', color: '#0d1117', fontFamily: "'Syne',sans-serif", fontWeight: 700, cursor: 'pointer', fontSize: 12 }}
        >
          Імпорт
        </button>
        <button onClick={() => continueWithCurrentState()}>Повторити архівацію</button>
        <button
          onClick={dismiss}
          style={{ background: 'none', border: '1px solid rgba(255,255,255,.2)', borderRadius: 7, padding: '5px 10px', color: '#e0e0e0', cursor: 'pointer', fontFamily: "'Syne',sans-serif", marginLeft: 6 }}
        >
          ✕
        </button>
      </div>
    </div>
  );
}
