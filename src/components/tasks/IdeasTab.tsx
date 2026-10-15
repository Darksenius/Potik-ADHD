import { useStore } from '../../state/store';
import { TASK_TYPE_LABELS } from '../../constants';

export default function IdeasTab() {
  const tasks = useStore((s) => s.tasks);
  const fromSomeday = useStore((s) => s.fromSomeday);
  const deleteTask = useStore((s) => s.deleteTask);

  const someday = tasks.filter((t) => t.someday && !t.trashed);

  return (
    <div id="ideas-sec" className="tsec active">
      <div id="someday-ideas" style={{ display: 'block' }}>
        <div className="bl">Колись ({someday.length})</div>
        <div id="someday-list">
          {!someday.length ? (
            <div className="plan-empty" style={{ padding: 12, textAlign: 'center', color: 'var(--t3)', fontSize: 12, lineHeight: 1.5 }}>
              Порожньо. У списку задач натисни 📦 на задачі — і вона відкладеться сюди «на колись».
            </div>
          ) : (
            someday.map((t) => (
              <div className="tc" key={t.id} style={{ borderLeftColor: 'var(--b2)', opacity: 0.7 }}>
                <div className="th">
                  <span className="tt">{t.title}</span>
                  {t.zoneName && <span className="badge zbadge" style={{ borderLeftColor: t.zoneColor || undefined }}>{t.zoneName}</span>}
                  <span className="badge">{TASK_TYPE_LABELS[t.type] || t.type}</span>
                  <div className="acts">
                    <button className="ab" onClick={() => fromSomeday(t.id)} title="↩ Повернути до задач">↩</button>
                    <button className="ab del" onClick={() => deleteTask(t.id)} title="✕">✕</button>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
