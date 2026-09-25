import { useStore } from '../../state/store';

export default function TrashSection() {
  const tasks = useStore(s => s.tasks);
  const restore = useStore(s => s.restoreTask);
  const remove = useStore(s => s.hardDeleteTask);
  const trash = tasks.filter(t => t.trashed);
  return <details style={{ marginTop: 16 }}>
    <summary>Кошик ({trash.length})</summary>
    <p style={{ fontSize: 12, color: 'var(--t2)' }}>Видалені задачі можна відновити протягом 7 днів.</p>
    {trash.map(t => <div className="tc" key={t.id}>
      <div className="tt">{t.title}</div>
      <button className="ab" onClick={() => restore(t.id)}>Відновити</button>
      <button className="ab" onClick={() => { if (window.confirm('Видалити «' + t.title + '» назавжди?')) remove(t.id); }}>Видалити назавжди</button>
    </div>)}
  </details>;
}
