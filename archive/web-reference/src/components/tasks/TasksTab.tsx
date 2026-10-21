import { useMemo, useState } from 'react';
import { useStore } from '../../state/store';
import { useClock } from '../../hooks/useClock';
import { fmtDate } from '../../utils/date';
import { getQuestTaskAvailability, buildQuestTimeline, selectQuestTasks } from '../../utils/questPolicy';
import { taskDisplayPlanDate } from '../../utils/taskSchedule';
import { describeTaskAvailability } from '../../utils/taskAvailabilityLabel';
import TaskItem from './TaskItem';
import TrashSection from './TrashSection';

export default function TasksTab({ all = false }: { all?: boolean }) {
  const hm = useClock();
  const tasks = useStore(s => s.tasks);
  const priorities = useStore(s => s.priorities);
  const folders = useStore(s => s.folders);
  const zones = useStore(s => s.zones);
  const planDayOff = useStore(s => s.planDayOff);
  const planDayZones = useStore(s => s.planDayZones);
  const planRules = useStore(s => s.planRules);
  const dayTemplates = useStore(s => s.dayTemplates);
  const dayBlocks = useStore(s => s.dayBlocks);
  const taskFilter = useStore(s => s.taskFilter);
  const setTaskFilter = useStore(s => s.setTaskFilter);
  const showDone = useStore(s => s.showDone);
  const toggleShowDone = useStore(s => s.toggleShowDone);
  const requestNewTaskEditor = useStore(s => s.requestNewTaskEditor);
  const [search, setSearch] = useState('');
  const [scope, setScope] = useState('all');
  const [sort, setSort] = useState<'manual' | 'priority'>('manual');
  const now = new Date();
  const today = fmtDate(now);
  const timeline = useMemo(() => buildQuestTimeline({
    zones,
    planDayOff,
    dayBlocks: day => dayBlocks(day),
    now: new Date(),
  }), [hm.h, hm.m, zones, planDayOff, planDayZones, planRules, dayTemplates, dayBlocks]);
  const questContext = { now, timeline, zones, priorities };
  const priorityIds = priorities.filter((id): id is number => id !== null);

  const questCandidates = tasks.filter(t => !t.trashed);
  let filtered = questCandidates;
  if (all && scope === 'future') filtered = filtered.filter(t => {
    const displayDate = taskDisplayPlanDate(t, questContext);
    return !!displayDate && displayDate > today && !t.done;
  });
  if (all && scope === 'undated') filtered = filtered.filter(t => !taskDisplayPlanDate(t, questContext) && !t.done && !t.someday);
  if (all && scope === 'overdue') filtered = filtered.filter(t => {
    const displayDate = taskDisplayPlanDate(t, questContext);
    return !!displayDate && displayDate < today && !t.done && !t.someday;
  });
  if (all && scope === 'someday') filtered = filtered.filter(t => t.someday);
  if (all && scope === 'done') filtered = filtered.filter(t => t.done);
  if (all && taskFilter === 'neg') filtered = filtered.filter(t => t.type === 'negative');
  if (all && taskFilter.startsWith('f:')) filtered = filtered.filter(t => t.folderId === taskFilter.slice(2));
  if (all && search.trim()) {
    const q = search.trim().toLocaleLowerCase('uk');
    filtered = filtered.filter(t => [t.title, t.note || '', ...(t.tags || [])].join(' ').toLocaleLowerCase('uk').includes(q));
  }

  const currentQuests = selectQuestTasks(all ? filtered : questCandidates, questContext, 5);
  const completedToday = showDone && !all
    ? filtered.filter(t => t.done && t.doneDate === today).slice(0, 5)
    : [];
  let visible = all
    ? filtered
    : [...currentQuests, ...completedToday];
  if (all && sort === 'priority') visible = visible.slice().sort((a, b) => {
    const ar = priorityIds.indexOf(a.id), br = priorityIds.indexOf(b.id);
    return (ar < 0 ? Number.MAX_SAFE_INTEGER : ar) - (br < 0 ? Number.MAX_SAFE_INTEGER : br);
  });
  const availabilityTasks = all ? filtered : questCandidates;
  const availabilityById = new Map(availabilityTasks.map(task => [task.id, getQuestTaskAvailability(task, questContext)]));
  const eligibleTasks = availabilityTasks.filter(task => availabilityById.get(task.id)?.eligible);
  const eligibleIds = new Set(eligibleTasks.map(t => t.id));
  const doneCount = filtered.filter(t => t.done && t.doneDate === today).length;
  const currentZoneId = currentQuests[0]
    ? getQuestTaskAvailability(currentQuests[0], questContext).currentZoneId
    : (timeline.find(segment => segment.startAt <= now.getTime() && now.getTime() < segment.endAt)?.zoneId ?? 0);
  const currentZone = zones.find(zone => zone.id === currentZoneId);

  return <section id="tasks-sec" className="tsec active">
    <div className="section-title"><h2>{all ? 'Усі задачі' : 'Зараз'}</h2>{!all && <button onClick={() => useStore.getState().switchTab('alltasks')}>Усі задачі</button>}</div>
    <p className="section-hint">{all
      ? 'Тут можна знайти також майбутні, відкладені та задачі з недоступними зонами.'
      : 'До п’яти доречних справ для зони «' + (currentZone?.nm || 'Вільний час') + '». Майбутні та інші зони лишаються в «Усі задачі» і Плані.'}</p>
    <button id="add-fab" onClick={() => requestNewTaskEditor(all ? undefined : today)}><span className="plus">＋</span> Нова задача</button>
    {all && <><label className="sr-only" htmlFor="task-search">Пошук задач</label><input id="task-search" className="ei" value={search} onChange={e => setSearch(e.target.value)} placeholder="Знайти за назвою, нотаткою чи тегом" /><label className="el">Показати<select className="ei" value={scope} onChange={e => setScope(e.target.value)}><option value="all">Усі, включно з виконаними</option><option value="future">Майбутні</option><option value="undated">Без дати</option><option value="overdue">Прострочені</option><option value="someday">Колись</option><option value="done">Виконані</option></select></label></>}
    <div className="list-options">{all && <label>Порядок<select aria-label="Порядок задач" value={sort} onChange={e => setSort(e.target.value as typeof sort)}><option value="manual">Вручну</option><option value="priority">Пріоритети спочатку</option></select></label>}{!all && <button onClick={toggleShowDone}>{showDone ? 'Сховати виконані' : 'Виконані (' + doneCount + ')'}</button>}</div>
    {all && <label className="task-folder-filter">Папка<select aria-label="Папка задач" value={taskFilter} onChange={e => setTaskFilter(e.target.value)}><option value="all">Усі папки</option>{folders.map(f => <option key={f.id} value={'f:' + f.id}>{f.nm}</option>)}<option value="neg">Журнал подій</option></select></label>}
    {currentQuests.length > 0 && !all && <div className="quest-list-label">Поточні квести · {currentQuests.length}/5</div>}
    <div id="tasks-list">{visible.length ? visible.map(t => <TaskItem
      key={t.id}
      t={t}
      isPriority={priorityIds.includes(t.id)}
      visibleIds={visible.map(task => task.id)}
      allowMove={all && sort === 'manual'}
      questCard={!all && !t.done}
      canDefer={!t.done && eligibleIds.has(t.id)}
      allView={all}
      availabilityMessage={all && availabilityById.get(t.id)?.reason === 'wrong-zone' ? 'Не доступно в поточній зоні' : undefined}
      availabilityExplanation={describeTaskAvailability(t, availabilityById.get(t.id) || getQuestTaskAvailability(t, questContext), questContext)}
      />) : <p className="empty">{all && (search || taskFilter !== 'all') ? 'За цими умовами задач немає' : all ? 'Задач немає' : 'Зараз немає доречних квестів. Невиконані задачі залишаються в «Усі задачі» та Плані.'}</p>}</div>
    {!all && eligibleTasks.length > currentQuests.length && <button className="secondary-action quest-more" onClick={() => useStore.getState().switchTab('alltasks')}>Інші доречні задачі · {eligibleTasks.length - currentQuests.length}</button>}
    <TrashSection />
  </section>;
}
