import { useState } from 'react';
import { useStore } from '../../state/store';
import { taskPlanDate } from '../../utils/taskSchedule';
import { saveState } from '../../services/persistence';
import TaskItem from './TaskItem';

export default function InboxTab() {
  const tasks = useStore(s => s.tasks);
  const [text, setText] = useState('');
  const inbox = tasks.filter(t => !t.trashed && !t.someday && !t.done && !taskPlanDate(t) && !t.zoneId && t.type === 'simple');
  function add() {
    if (!text.trim()) return;
    useStore.getState().createTask(text.trim(), 'simple');
    if (!saveState()) { setText(''); return; }
    setText('');
    useStore.getState().showToast('Додано у Вхідні');
  }
  return <section id="inbox-sec" className="tsec active">
    <h2>Вхідні</h2><p className="section-hint">Запиши зараз. Дату та інші умови можна вибрати пізніше.</p>
    <form className="quick-capture" onSubmit={e => { e.preventDefault(); add(); }}><label className="sr-only" htmlFor="quick-task">Нова проста задача</label><input id="quick-task" className="ei" value={text} onChange={e => setText(e.target.value)} placeholder="Що не хочеться забути?" /><button type="submit">Додати</button></form>
    {inbox.length ? inbox.map(t => <TaskItem key={t.id} t={t} isPriority={false} />) : <p className="empty">Вхідні порожні</p>}
    <button className="secondary-action" onClick={() => useStore.getState().switchTab('notes')}>Відкрити нотатки</button>
  </section>;
}
