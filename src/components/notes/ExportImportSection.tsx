import { useRef, useState } from 'react';
import { useStore } from '../../state/store';
import { exportBackupFile, exportReadableFile, buildAiPromptText, importFromFile } from '../../services/exportImport';
import ClipboardFallback from '../common/ClipboardFallback';

export default function ExportImportSection() {
  const showToast = useStore((s) => s.showToast);
  const fileRef = useRef<HTMLInputElement>(null);
  const [fallbackText, setFallbackText] = useState<string | null>(null);

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
      const added = await importFromFile(file);
      window.alert(added > 0 ? `✓ Додано нових елементів: ${added}` : 'Нічого нового — усе з файлу вже є у застосунку');
    } catch (e) {
      window.alert('✗ ' + (e instanceof Error ? e.message : 'Помилка імпорту'));
    }
  };

  return (
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 14, marginBottom: 6 }}>
      <button className="ab" onClick={exportBackupFile} title="Повна копія (.json) — саме її читає імпорт">
        💾 Експорт
      </button>
      <button
        className="ab"
        onClick={() => fileRef.current?.click()}
        title="Додає дані з файлу (не замінює наявні)"
      >
        📥 Імпорт
      </button>
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
