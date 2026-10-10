import { useRef, useState } from 'react';
import { useStore } from '../../state/store';
import { exportBackupFile, exportReadableFile, buildAiPromptText, importFromFile, rollbackLastImport, IMPORT_ROLLBACK_KEY } from '../../services/exportImport';
import ClipboardFallback from '../common/ClipboardFallback';

export default function ExportImportSection() {
  const showToast = useStore((s) => s.showToast);
  const fileRef = useRef<HTMLInputElement>(null);
  const [fallbackText, setFallbackText] = useState<string | null>(null);
  const [mode, setMode] = useState<'merge' | 'restore'>('merge');
  const [hasRollback, setHasRollback] = useState(() => { try { return !!localStorage.getItem(IMPORT_ROLLBACK_KEY); } catch { return false; } });

  const handleAiAnalyze = () => {
    const text = buildAiPromptText();
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        () => showToast('🤖 Скопійовано! Встав у ChatGPT, Claude чи інший ШІ.'),
        () => setFallbackText(text)
      );
    } else {
      setFallbackText(text);
    }
  };

  const handleImportFile = async (file: File) => {
    try {
      if (mode === 'restore' && !window.confirm('Замінити поточні дані повною копією з файлу? Перед відновленням збережеться копія для відкату.')) return;
      const added = await importFromFile(file, mode);
      setHasRollback(true);
      window.alert(mode === 'restore' ? 'Усі дані з копії відновлено.' : added > 0 ? `✓ Додано нових елементів: ${added}` : 'Нічого нового — усе з файлу вже є у застосунку');
    } catch (e) {
      window.alert('✗ ' + (e instanceof Error ? e.message : 'Помилка імпорту'));
    }
  };

  return (
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 14, marginBottom: 6 }}>
      <label className="el" style={{ width: '100%' }}>Режим імпорту<select className="ei" value={mode} onChange={e => setMode(e.target.value as typeof mode)}><option value="merge">Додати записи до наявних</option><option value="restore">Повністю відновити копію</option></select></label>
      <p className="section-hint">Повне відновлення повертає також блокнот, досвід, налаштування та історію. Додавання об’єднує записи й залишає поточну статистику.</p>
      <button className="ab" onClick={exportBackupFile} title="Повна копія (.json) — саме її читає імпорт">
        💾 Експорт
      </button>
      <button
        className="ab"
        onClick={() => fileRef.current?.click()}
        title={mode === 'merge' ? 'Додати записи з копії' : 'Повністю відновити з копії'}
      >
        📥 Імпорт
      </button>
      {hasRollback && <button className="ab" onClick={() => { if (window.confirm('Повернути стан перед останнім імпортом?')) showToast(rollbackLastImport() ? 'Попередній стан повернуто' : 'Відновити попередній стан не вдалося'); }}>Скасувати останній імпорт</button>}
      <input
        ref={fileRef}
        type="file"
        accept="application/json,.json"
        style={{ display: 'none' }}
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) handleImportFile(file);
          e.target.value = '';
        }}
      />
      <button className="ab" onClick={exportReadableFile} title="Читабельний звіт (.md) — не бекап">
        📄 Звіт (.md)
      </button>
      <button className="ab" onClick={handleAiAnalyze} title="Копіює готовий промпт разом з даними у буфер">
        🤖 Аналіз ШІ
      </button>
      {fallbackText && <ClipboardFallback text={fallbackText} onClose={() => setFallbackText(null)} />}
    </div>
  );
}
