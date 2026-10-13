import { useState } from 'react';
import { useStore } from '../../state/store';
import { isTaskInInbox } from '../../utils/taskSchedule';
import { saveState } from '../../services/persistence';
import TaskItem from './TaskItem';

export default function InboxTab() {
  const tasks = useStore(s => s.tasks);
  const [text, setText] = useState('');
  const inbox = tasks.filter(isTaskInInbox);
  function add() {
    if (!text.trim()) return;
    useStore.getState().createTask(text.trim(), 'simple');
    if (!saveState()) { setText(''); return; }
    setText('');
    useStore.getState().showToast('Задача доступна у «Зараз». День можна призначити пізніше.');
  }
  return <section id="inbox-sec" className="tsec active">
    <h2>Вхідні</h2><p className="section-hint">Справи без призначеного дня вже доступні у «Зараз», коли підходять їхні умови. Тут можна вибрати дату або зону, щоб спланувати їх.</p>
    <form className="quick-capture" onSubmit={e => { e.preventDefault(); add(); }}><label className="sr-only" htmlFor="quick-task">Нова проста задача</label><input id="quick-task" className="ei" value={text} onChange={e => setText(e.target.value)} placeholder="Що не хочеться забути?" /><button type="submit">Додати</button></form>
    {inbox.length ? inbox.map(t => <TaskItem key={t.id} t={t} isPriority={false} visibleIds={inbox.map(task => task.id)} />) : <p className="empty">Вхідні порожні</p>}
    <button className="secondary-action" onClick={() => useStore.getState().switchTab('notes')}>Відкрити нотатки</button>
  </section>;
}
