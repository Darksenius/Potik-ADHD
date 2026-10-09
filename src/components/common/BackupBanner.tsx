import { useStore } from '../../state/store';
import { getRecoverySources } from '../../services/persistence';
import { importState } from '../../services/exportImport';
import { flowBridge } from '../../bridge/nativeBridge';

export default function BackupBanner() {
  const banner = useStore((s) => s.backupBanner);
  const dismiss = useStore((s) => s.dismissBackupBanner);
  const showToast = useStore((s) => s.showToast);

  if (!banner) return null;

  const base: React.CSSProperties = {
    position: 'fixed', top: 0, left: '50%', transform: 'translateX(-50%)', width: '100%', maxWidth: 520,
    background: '#1a2a1a', borderBottom: '2px solid #7ed321', padding: '12px 16px', zIndex: 9999,
    fontFamily: "'Syne',sans-serif", fontSize: 13, color: '#e0e0e0', boxShadow: '0 4px 20px rgba(0,0,0,.6)',
  };

  if (banner === 'unreadable') {
    return (
      <div style={base}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 18 }}>⚠️</span>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 700, color: '#f5c542' }}>Знайдено резервний файл, але він пошкоджений</div>
            <div style={{ fontSize: 11, color: '#bbb', marginTop: 2 }}>Автозбереження призупинено, щоб зберегти оригінал. Збережи файл діагностики та імпортуй справну копію в Налаштуваннях.</div>
            <button onClick={() => {
              const raw = getRecoverySources();
              const fb = flowBridge();
              if (fb) fb.exportTxt('flow-recovery.json', raw);
              else { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([raw], { type: 'application/json' })); a.download = 'flow-recovery.json'; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 1000); }
            }}>Зберегти оригінали</button>
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
    <div style={base}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span style={{ fontSize: 18 }}>💾</span>
        <div style={{ flex: 1 }}>
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
