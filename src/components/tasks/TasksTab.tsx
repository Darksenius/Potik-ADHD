import { useState } from 'react';
import { useStore } from '../../state/store';
import { useClock } from '../../hooks/useClock';
import { fmtDate, toMinutes } from '../../utils/date';
import type { Task } from '../../types';
import TaskItem from './TaskItem';
import ZoneTasksBanner from '../zones/ZoneTasksBanner';

/** _appRank(t) — рядки 2179–2190. Ранжування (версія Клод). */
function appRank(t: Task, priorityIds: number[], zoneId: number): number {
  if (t.done) return 100;
  const pi = priorityIds.indexOf(t.id);
  if (pi >= 0) return pi;
  const fired = (t.type === 'alarm' && t.alarmFired) || (t.type === 'sched' && t.firedSched);
  if (fired) return 10;
  if (zoneId && t.zoneId === zoneId) return 20;
  if ((t.type === 'alarm' && !t.alarmFired) || (t.type === 'sched' && !t.firedSched)) return 45;
  if (t.zoneId) return 50;
  return 40;
}

export default function TasksTab() {
  const hm = useClock();
  const tasks = useStore((s) => s.tasks);
  const priorities = useStore((s) => s.priorities);
  const folders = useStore((s) => s.folders);
  const taskFilter = useStore((s) => s.taskFilter);
  const setTaskFilter = useStore((s) => s.setTaskFilter);
  const showDone = useStore((s) => s.showDone);
  const toggleShowDone = useStore((s) => s.toggleShowDone);
  const requestNewTaskEditor = useStore((s) => s.requestNewTaskEditor);
  const pz = useStore.getState().getActiveZones(hm)[0];

  const todayStr = fmtDate(new Date());

  let allTasks = tasks.filter((t) => {
    if (t.trashed || t.someday || t.type === 'zonelinked') return false;
    if (t.planDate && t.planDate > todayStr && !t.done) return false;
    if (t.done && t.doneDate !== todayStr) return false;
    if (t.snoozeUntil && Date.now() < t.snoozeUntil && !t.done) return false;
    if (t.type === 'timewin') {
      if (t.completedToday) return false;
      const curMin = hm.h * 60 + hm.m;
      const wsMin = toMinutes(t.windowStart || '00:00');
      const weMin = toMinutes(t.windowEnd || '23:59');
      if (curMin < wsMin || curMin > weMin) return false;
    }
    return true;
  });

  const doneTasks = allTasks.filter((t) => t.done);
  let visible = showDone ? allTasks : allTasks.filter((t) => !t.done);

  const prioIds = priorities.filter((x): x is number => !!x);
  visible = visible.slice().sort((a, b) => appRank(a, prioIds, pz.id) - appRank(b, prioIds, pz.id));

  if (taskFilter === 'neg') {
    visible = visible.filter((t) => t.type === 'negative');
  } else if (taskFilter.indexOf('f:') === 0) {
    const fid = taskFilter.slice(2);
    visible = visible.filter((t) => t.folderId === fid);
  }

  return (
    <div id="tasks-sec" className="tsec active">
      <ZoneTasksBanner />

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
        <div style={{ fontSize: 10, color: 'var(--t3)' }}>
          {doneTasks.length ? '✓ ' + doneTasks.length + ' виконано' : ''}
        </div>
        <button
          onClick={toggleShowDone}
          style={{ background: 'none', border: '1px solid var(--b2)', borderRadius: 9, padding: '3px 9px', fontSize: 11, color: 'var(--t3)', cursor: 'pointer', fontFamily: "'Syne',sans-serif" }}
        >
          {showDone ? 'Виконані ▲' : 'Виконані (' + doneTasks.length + ') ▼'}
        </button>
      </div>

      <div className="ctx-filter">
        <div className={'ctxf' + (taskFilter === 'all' ? ' act' : '')} onClick={() => setTaskFilter('all')}>Всі</div>
        {folders.map((f) => (
          <div
            key={f.id}
            className={'ctxf' + (taskFilter === 'f:' + f.id ? ' act' : '')}
            onClick={() => setTaskFilter('f:' + f.id)}
            title={f.nm}
          >
            {f.ico || '📁'}
          </div>
        ))}
        <div
          className={'ctxf' + (taskFilter === 'neg' ? ' act' : '')}
          onClick={() => setTaskFilter('neg')}
          style={{ color: 'var(--neg)' }}
          title="Шкідливі"
        >
          ⚠
        </div>
      </div>

      <button id="add-fab" onClick={requestNewTaskEditor}>
        <span className="plus">＋</span> Нова задача
      </button>

      <div id="tasks-list">
        {!visible.length ? (
          <div className="empty">
            <div className="empty-i">📭</div>Задач немає
          </div>
        ) : (
          visible.map((t) => <TaskItem key={t.id} t={t} isPriority={prioIds.indexOf(t.id) >= 0} />)
        )}
      </div>
    </div>
  );
}
