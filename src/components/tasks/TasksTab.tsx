import { useState } from 'react';
import { useStore } from '../../state/store';
import { useClock } from '../../hooks/useClock';
import { fmtDate } from '../../utils/date';
import { isTaskAvailable, taskPlanDate } from '../../utils/taskSchedule';
import TaskItem from './TaskItem';
import ZoneTasksBanner from '../zones/ZoneTasksBanner';
import TrashSection from './TrashSection';

export default function TasksTab({ all = false }: { all?: boolean }) {
  useClock();
  const tasks = useStore(s => s.tasks);
  const priorities = useStore(s => s.priorities);
  const folders = useStore(s => s.folders);
  const taskFilter = useStore(s => s.taskFilter);
  const setTaskFilter = useStore(s => s.setTaskFilter);
  const showDone = useStore(s => s.showDone);
  const toggleShowDone = useStore(s => s.toggleShowDone);
  const requestNewTaskEditor = useStore(s => s.requestNewTaskEditor);
  const [search, setSearch] = useState('');
  const [scope, setScope] = useState('all');
  const [sort, setSort] = useState<'manual' | 'priority'>('manual');
  const today = fmtDate(new Date());
  const priorityIds = priorities.filter((id): id is number => id !== null);
  const pool = tasks.filter(t => !t.trashed && (all || (isTaskAvailable(t) && t.type !== 'zonelinked' && (!t.done || t.doneDate === today))));
  const doneCount = pool.filter(t => t.done).length;
  let visible = pool.filter(t => all || showDone || !t.done);
  if (scope === 'future') visible = visible.filter(t => (taskPlanDate(t) || '') > today && !t.done);
  if (scope === 'undated') visible = visible.filter(t => !taskPlanDate(t) && !t.done && !t.someday);
  if (scope === 'overdue') visible = visible.filter(t => !!taskPlanDate(t) && taskPlanDate(t)! < today && !t.done && !t.someday);
  if (scope === 'someday') visible = visible.filter(t => t.someday);
  if (scope === 'done') visible = visible.filter(t => t.done);
  if (taskFilter === 'neg') visible = visible.filter(t => t.type === 'negative');
  if (taskFilter.startsWith('f:')) visible = visible.filter(t => t.folderId === taskFilter.slice(2));
  if (search.trim()) { const q = search.trim().toLocaleLowerCase('uk'); visible = visible.filter(t => [t.title, t.note || '', ...(t.tags || [])].join(' ').toLocaleLowerCase('uk').includes(q)); }
  if (sort === 'priority') visible = visible.slice().sort((a, b) => { const ar = priorityIds.indexOf(a.id), br = priorityIds.indexOf(b.id); return (ar < 0 ? 100 : ar) - (br < 0 ? 100 : br); });
  return <section id="tasks-sec" className="tsec active">
    <div className="section-title"><h2>{all ? 'Усі задачі' : 'Сьогодні'}</h2>{!all && <button onClick={() => useStore.getState().switchTab('alltasks')}>Усі задачі</button>}</div>
    <p className="section-hint">{all ? 'Тут можна знайти також майбутні, відкладені та зональні задачі.' : 'Справи на цей день і задачі без дати. Майбутні — у Плані.'}</p>
    {!all && <ZoneTasksBanner />}
    <button id="add-fab" onClick={() => requestNewTaskEditor()}><span className="plus">＋</span> Нова задача</button>
    {all && <><label className="sr-only" htmlFor="task-search">Пошук задач</label><input id="task-search" className="ei" value={search} onChange={e => setSearch(e.target.value)} placeholder="Знайти за назвою, нотаткою чи тегом" /><label className="el">Показати<select className="ei" value={scope} onChange={e => setScope(e.target.value)}><option value="all">Усі, включно з виконаними</option><option value="future">Майбутні</option><option value="undated">Без дати</option><option value="overdue">Прострочені</option><option value="someday">Колись</option><option value="done">Виконані</option></select></label></>}
    <div className="list-options"><label>Порядок<select aria-label="Порядок задач" value={sort} onChange={e => setSort(e.target.value as typeof sort)}><option value="manual">Вручну</option><option value="priority">Пріоритети спочатку</option></select></label>{!all && <button onClick={toggleShowDone}>{showDone ? 'Сховати виконані' : `Виконані (${doneCount})`}</button>}</div>
    <label className="task-folder-filter">Папка<select aria-label="Папка задач" value={taskFilter} onChange={e => setTaskFilter(e.target.value)}><option value="all">Усі папки</option>{folders.map(f => <option key={f.id} value={'f:' + f.id}>{f.ico || '📁'} {f.nm}</option>)}<option value="neg">Шкідливі звички</option></select></label>
    <div id="tasks-list">{visible.length ? visible.map(t => <TaskItem key={t.id} t={t} isPriority={priorityIds.includes(t.id)} allowMove={sort === 'manual'} />) : <p className="empty">{search || taskFilter !== 'all' ? 'За цими умовами задач немає' : 'Задач немає'}</p>}</div>
    <TrashSection />
  </section>;
}
