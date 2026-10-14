import { useState } from 'react';
import { useStore } from '../../state/store';
import { isTaskInInbox } from '../../utils/taskSchedule';
import { saveState } from '../../services/persistence';
import TaskItem from './TaskItem';

export default function InboxTab() {
  const tasks = useStore(s => s.tasks);
  const [text, setText] = useState('');
  const [pendingCaptureId, setPendingCaptureId] = useState<number | null>(null);
  const inbox = tasks.filter(isTaskInInbox);
  function add() {
    if (!text.trim()) return;
    const title = text.trim();
    const pending = pendingCaptureId === null ? undefined : useStore.getState().tasks.find(task =>
      task.id === pendingCaptureId && !task.done && !task.trashed && !task.someday,
    );
    const taskId = pending?.id ?? useStore.getState().createTask(title, 'simple').id;
    if (pending) {
      useStore.setState(state => ({
        tasks: state.tasks.map(task => task.id === taskId ? { ...task, title } : task),
      }));
    } else {
      setPendingCaptureId(taskId);
    }
    if (!saveState()) return;
    setPendingCaptureId(null);
    setText('');
    useStore.getState().showToast('Задача доступна у «Зараз». День можна призначити пізніше.');
  }
  return <section id="inbox-sec" className="tsec active">
    <h2>Вхідні</h2><p className="section-hint">Справи без призначеного дня вже доступні у «Зараз», коли підходять їхні умови. Тут можна вибрати дату або зону, щоб спланувати їх.</p>
    <form className="quick-capture" onSubmit={e => { e.preventDefault(); add(); }}><label className="sr-only" htmlFor="quick-task">Нова проста задача</label><input id="quick-task" className="ei" value={text} onChange={e => { setText(e.target.value); if (!e.target.value.trim()) setPendingCaptureId(null); }} placeholder="Що не хочеться забути?" /><button type="submit">Додати</button></form>
    {inbox.length ? inbox.map(t => <TaskItem key={t.id} t={t} isPriority={false} visibleIds={inbox.map(task => task.id)} />) : <p className="empty">Вхідні порожні</p>}
    <button className="secondary-action" onClick={() => useStore.getState().switchTab('notes')}>Відкрити нотатки</button>
  </section>;
}
