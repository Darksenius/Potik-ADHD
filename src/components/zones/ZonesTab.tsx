import { useStore } from '../../state/store';
import { useState } from 'react';
import ZoneEditor from './ZoneEditor';

const PRIO_LABELS = ['', 'Фон', 'Нарада', 'Критичне'];

export default function ZonesTab() {
  const zones = useStore((s) => s.zones);
  const tasks = useStore((s) => s.tasks);
  const deleteZone = useStore((s) => s.deleteZone);
  const addZone = useStore((s) => s.addZone);
  const toggleZoneOff = useStore((s) => s.toggleZoneOff);
  const [editingId, setEditingId] = useState<number | null>(null);
  const editing = zones.find(z => z.id === editingId);

  return (
    <div id="zones-sec" className="tsec active">
      <button
        id="add-fab"
        onClick={() => {
          const name = window.prompt('Назва нової зони:');
          if (name && name.trim()) setEditingId(addZone(name.trim()));
        }}
      >
        <span className="plus">＋</span> Нова зона
      </button>

      <div id="zones-list">
        {!zones.length ? (
          <div className="empty">
            <div className="empty-i">◐</div>Зон немає
          </div>
        ) : (
          zones.map((z) => {
            const linked = tasks.filter((t) => !t.trashed && t.type === 'zonelinked' && t.zoneId === z.id);
            return (
              <div className="zrow" key={z.id} style={{ borderLeftColor: z.color }}>
                <div className="zdot" style={{ background: z.color }} />
                <div className="zri">
                  <div className="znm">{z.nm}</div>
                  <div className="zslots">
                    {z.slots.map((sl, i) => (
                      <span className="zslot" key={i}>{sl.s}–{sl.e}</span>
                    ))}
                  </div>
                  <span className={'zprio zp' + z.prio}>{PRIO_LABELS[z.prio || 1] || ''}</span>
                  {z.desc && <div className="zdesc">{z.desc}</div>}
                  {!!linked.length && (
                    <div className="zdesc" style={{ color: 'var(--z)' }}>
                      🔗 {linked.map((t) => t.title).join(', ')}
                    </div>
                  )}
                </div>
                <button className="ab" aria-label={'Редагувати зону ' + z.nm} onClick={() => setEditingId(z.id)}>✎</button>
                <button className="ab" aria-label={(z.active === false ? 'Увімкнути ' : 'Вимкнути ') + z.nm} onClick={() => toggleZoneOff(z.id)}>{z.active === false ? '○' : '●'}</button>
                <button className="zdel" aria-label={'Видалити зону ' + z.nm} onClick={() => { if (window.confirm('Видалити зону «' + z.nm + '»?')) deleteZone(z.id); }}>✕</button>
              </div>
            );
          })
        )}
      </div>
      {editing && <ZoneEditor key={editing.id} zone={editing} onClose={() => setEditingId(null)} />}
    </div>
  );
}
