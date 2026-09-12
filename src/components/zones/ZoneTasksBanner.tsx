import { useStore } from '../../state/store';
import { useClock } from '../../hooks/useClock';
import { REPEAT_LABELS } from '../../constants';

export default function ZoneTasksBanner() {
  const hm = useClock();
  useStore((s) => s.tasks);
  useStore((s) => s.zones);
  const pz = useStore.getState().getActiveZones(hm)[0];
  const tasks = useStore((s) => s.tasks);
  const toggleZoneDoneTask = useStore((s) => s.toggleZoneDoneTask);
  const requestEditTask = useStore((s) => s.requestEditTask);
  const deleteTask = useStore((s) => s.deleteTask);

  const linked = tasks.filter((t) => t.type === 'zonelinked' && t.zoneId === pz.id);
  if (!linked.length || pz.id === 0) return null;

  return (
    <div id="zone-tasks-banner" style={{ display: 'block', borderLeftColor: pz.color }}>
      <div className="ztb-head">
        <span className="ztb-lbl">Зараз активна зона</span>
        <span className="ztb-zone" style={{ color: pz.color }}>{pz.nm}</span>
      </div>
      <div className="ztb-list">
        {linked.map((t) => (
          <div className="ztb-item" key={t.id}>
            <button className={'ztb-ck' + (t.zoneDoneToday ? ' on' : '')} onClick={() => toggleZoneDoneTask(t.id)}>
              {t.zoneDoneToday ? '✓' : ''}
            </button>
            <span className={'ztb-title' + (t.zoneDoneToday ? ' done-t' : '')}>{t.title}</span>
            <span className="ztb-rep">{REPEAT_LABELS[t.repeat] || ''}</span>
            <button className="ab eb" onClick={() => requestEditTask(t.id)} title="Редагувати" style={{ flexShrink: 0 }}>✎</button>
            <button className="ab del" onClick={() => deleteTask(t.id)} title="Видалити" style={{ flexShrink: 0 }}>✕</button>
          </div>
        ))}
      </div>
    </div>
  );
}
