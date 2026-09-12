import { useState } from 'react';
import { useStore } from '../../state/store';
import { TASK_TYPE_LABELS } from '../../constants';
import Modal from '../common/Modal';

export default function PriorityPicker({ slot, onClose }: { slot: number; onClose: () => void }) {
  const [query, setQuery] = useState('');
  const tasks = useStore((s) => s.tasks);
  const priorities = useStore((s) => s.priorities);
  const setPriority = useStore((s) => s.setPriority);

  const q = query.toLowerCase();
  const filtered = tasks.filter(
    (t) => !t.trashed && !t.someday && t.type !== 'negative' && t.type !== 'zonelinked' && (!q || t.title.toLowerCase().includes(q))
  );

  return (
    <Modal title={`Пріоритет #${slot + 1}`} onClose={onClose}>
      <input
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Пошук..."
        autoFocus
        style={{ width: '100%', marginBottom: 8 }}
      />
      <div>
        {!filtered.length ? (
          <div style={{ textAlign: 'center', padding: 14, color: 'var(--t3)', fontSize: 12 }}>Нічого не знайдено</div>
        ) : (
          filtered.map((t) => {
            const sel = priorities.indexOf(t.id) >= 0;
            return (
              <div
                className={'tp-item' + (sel ? ' sel' : '')}
                key={t.id}
                style={{ borderLeftColor: t.zoneColor || 'transparent', cursor: 'pointer' }}
                onClick={() => {
                  setPriority(slot, t.id);
                  onClose();
                }}
              >
                <div>
                  <div className="tp-item-title">
                    {t.done ? <s style={{ opacity: 0.5 }}>{t.title}</s> : t.title}
                  </div>
                  <div className="tp-zone">
                    {t.zoneName ? '◐ ' + t.zoneName + ' · ' : ''}{TASK_TYPE_LABELS[t.type]}
                  </div>
                </div>
                {sel && <span style={{ fontSize: 10, color: 'var(--z)', fontFamily: "'Space Mono',monospace" }}>✓</span>}
              </div>
            );
          })
        )}
      </div>
    </Modal>
  );
}
